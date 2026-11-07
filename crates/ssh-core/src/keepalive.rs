//! Keepalives whose interval can change while the connection is up.
//!
//! russh has a keepalive timer of its own, but it reads the interval once, at
//! connect time. That interval is the main reason an idle session wakes the
//! radio: each firing is a request and a reply, and on mobile data every one
//! of them keeps the modem powered for its tail of several seconds. A terminal
//! that is on screen wants a dead link noticed quickly; one left running behind
//! another app only needs its NAT mapping held open, which takes far less. So
//! russh's timer is switched off and this one runs instead, with an interval
//! the app can move as it comes and goes.
//!
//! Dead-peer detection keeps russh's meaning: [`MAX_UNANSWERED`] requests in a
//! row without a reply, and without anything else arriving, end the connection.

use std::io;
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Weak};
use std::task::{Context, Poll, Waker};
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::watch;
use tokio::time::Instant;

/// Requests that may go unanswered in a row before the peer is taken for dead;
/// the value russh uses for its own timer.
pub const MAX_UNANSWERED: u32 = 3;

/// The interval a session in the background never goes below.
///
/// It is only as long as a *persistent* connection can safely go quiet. TCP
/// mappings in carrier-grade NATs and stateful firewalls are commonly dropped
/// after four to five idle minutes (Azure's load balancer after four, many
/// mobile carriers after five, AWS's NLB just under six), and a session whose
/// mapping has gone is silently dead: the first keystroke after coming back
/// finds out, not the keepalive. Three minutes stays under all of those with a
/// margin for a timer that fires late in Doze. A user who configured something
/// longer, or data saver's stretched interval, is kept as it is.
pub const BACKGROUND_FLOOR: Duration = Duration::from_secs(180);

/// The interval for a session in the background, given the one it uses on
/// screen. Off stays off: that was the user's choice, not ours.
pub fn background_interval(foreground: Option<Duration>) -> Option<Duration> {
    foreground.map(|every| every.max(BACKGROUND_FLOOR))
}

/// When anything was last heard from the server.
///
/// Stamped on every piece of channel data, so it has to be cheap: one clock
/// read and one relaxed store, no lock. Milliseconds from a per-connection
/// epoch fit a `u64` for longer than any session lives.
pub(crate) struct Activity {
    epoch: Instant,
    last_ms: AtomicU64,
}

impl Activity {
    pub(crate) fn new() -> Self {
        Self { epoch: Instant::now(), last_ms: AtomicU64::new(0) }
    }

    pub(crate) fn stamp(&self) {
        self.last_ms.store(self.epoch.elapsed().as_millis() as u64, Ordering::Relaxed);
    }

    fn last(&self) -> Instant {
        self.epoch + Duration::from_millis(self.last_ms.load(Ordering::Relaxed))
    }
}

/// The app's side of one connection's keepalive: what it runs at on screen,
/// and the channel the running task listens on.
pub(crate) struct Control {
    foreground: Option<Duration>,
    interval: watch::Sender<Option<Duration>>,
}

impl Control {
    pub(crate) fn new(foreground: Option<Duration>) -> (Arc<Self>, watch::Receiver<Option<Duration>>) {
        let (interval, rx) = watch::channel(foreground);
        (Arc::new(Self { foreground, interval }), rx)
    }

    pub(crate) fn set_background(&self, background: bool) {
        let every = if background { background_interval(self.foreground) } else { self.foreground };
        // send_if_modified, so telling a session what it already knows does not
        // wake its task to recompute the same deadline.
        self.interval.send_if_modified(|current| {
            let changed = *current != every;
            *current = every;
            changed
        });
    }
}

/// A handle on a connection's keepalive that does not keep the connection.
///
/// Whoever tracks sessions for the app holds these; once the client is dropped
/// [`KeepaliveSwitch::set_background`] says so and the entry can go.
#[derive(Clone)]
pub struct KeepaliveSwitch(pub(crate) Weak<Control>);

impl KeepaliveSwitch {
    /// Move the connection to its background or foreground interval. False
    /// when the connection is gone.
    pub fn set_background(&self, background: bool) -> bool {
        match self.0.upgrade() {
            Some(control) => {
                control.set_background(background);
                true
            }
            None => false,
        }
    }
}

/// Why [`run`] stopped.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Ended {
    /// The client was dropped or the connection closed; nothing to do.
    Gone,
    /// [`MAX_UNANSWERED`] requests went unanswered: the peer is not there.
    Unanswered,
}

/// The keepalive loop for one connection.
///
/// `ping` sends a request that wants a reply and resolves once it has one,
/// with false when the connection is already closed. The loop sleeps until the
/// last thing heard plus the interval, so a connection with output flowing
/// never sends a keepalive at all, and a change of interval takes effect at
/// once rather than after the sleep already started.
pub(crate) async fn run<P, F>(mut interval: watch::Receiver<Option<Duration>>, activity: &Activity, mut ping: P) -> Ended
where
    P: FnMut() -> F,
    F: std::future::Future<Output = bool>,
{
    let mut unanswered = 0u32;
    loop {
        let Some(every) = *interval.borrow_and_update() else {
            if interval.changed().await.is_err() {
                return Ended::Gone;
            }
            continue;
        };
        let due = activity.last() + every;
        if due > Instant::now() {
            tokio::select! {
                () = tokio::time::sleep_until(due) => {}
                changed = interval.changed() => if changed.is_err() { return Ended::Gone },
            }
            // Either way the deadline is worked out again: data may have come
            // in while this slept, or the interval moved.
            continue;
        }

        let sent = Instant::now();
        // The reply gets as long as russh would have given it: until the next
        // request was due.
        let mut deadline = sent + every;
        let reply = ping();
        tokio::pin!(reply);
        let answered = loop {
            tokio::select! {
                r = &mut reply => break Some(r),
                () = tokio::time::sleep_until(deadline) => break None,
                changed = interval.changed() => {
                    if changed.is_err() {
                        return Ended::Gone;
                    }
                    // A dead link is almost always waiting here, on a request
                    // that will never be answered. Coming back on screen has to
                    // shorten that wait too, or the user looks at a frozen
                    // terminal for the rest of a background-length one.
                    if let Some(now_every) = *interval.borrow_and_update() {
                        deadline = deadline.min(sent + now_every);
                    }
                }
            }
        };
        match answered {
            Some(true) => {
                unanswered = 0;
                activity.stamp();
            }
            Some(false) => return Ended::Gone,
            // Data counts as an answer, as it does for russh: a server busy
            // sending output is plainly alive, whatever became of the request.
            None if activity.last() >= sent => unanswered = 0,
            None => {
                unanswered += 1;
                if unanswered >= MAX_UNANSWERED {
                    return Ended::Unanswered;
                }
            }
        }
    }
}

/// Lets the keepalive end a connection whose peer has gone quiet.
///
/// Asking russh to disconnect is not enough. Its session loop stops taking
/// requests while a channel has output waiting on the server's window, and a
/// dead server never opens that window again; and a full socket buffer leaves
/// it waiting on the write. Failing the stream underneath reaches the loop in
/// either case, and it ends the way it would have on russh's own keepalive
/// timeout: every channel closes and the terminal says the connection dropped.
pub(crate) struct Cut {
    severed: AtomicBool,
    /// Whoever is parked on a read and on a write, woken when the cut is made.
    wakers: std::sync::Mutex<[Option<Waker>; 2]>,
}

const READ: usize = 0;
const WRITE: usize = 1;

impl Cut {
    pub(crate) fn new() -> Arc<Self> {
        Arc::new(Self { severed: AtomicBool::new(false), wakers: std::sync::Mutex::new([None, None]) })
    }

    pub(crate) fn sever(&self) {
        self.severed.store(true, Ordering::SeqCst);
        let wakers = std::mem::take(&mut *self.wakers.lock().unwrap());
        for w in wakers.into_iter().flatten() {
            w.wake();
        }
    }

    fn is_severed(&self) -> bool {
        self.severed.load(Ordering::SeqCst)
    }

    /// Remember who to wake, then look again, so a cut made between the first
    /// look and parking is not missed.
    fn park(&self, slot: usize, waker: &Waker) -> bool {
        {
            let mut wakers = self.wakers.lock().unwrap();
            match &wakers[slot] {
                Some(w) if w.will_wake(waker) => {}
                _ => wakers[slot] = Some(waker.clone()),
            }
        }
        self.is_severed()
    }
}

fn severed() -> io::Error {
    io::Error::new(io::ErrorKind::TimedOut, "the server stopped answering keepalives")
}

/// The transport, with a [`Cut`] that can fail it from outside.
pub(crate) struct Severable<S> {
    inner: S,
    cut: Arc<Cut>,
}

impl<S> Severable<S> {
    pub(crate) fn new(inner: S, cut: Arc<Cut>) -> Self {
        Self { inner, cut }
    }
}

impl<S: AsyncRead + Unpin> AsyncRead for Severable<S> {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        if self.cut.is_severed() {
            return Poll::Ready(Err(severed()));
        }
        // Parking only when the read would wait keeps the lock off the path
        // where data is flowing.
        match Pin::new(&mut self.inner).poll_read(cx, buf) {
            Poll::Pending if self.cut.park(READ, cx.waker()) => Poll::Ready(Err(severed())),
            other => other,
        }
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for Severable<S> {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        if self.cut.is_severed() {
            return Poll::Ready(Err(severed()));
        }
        match Pin::new(&mut self.inner).poll_write(cx, buf) {
            Poll::Pending if self.cut.park(WRITE, cx.waker()) => Poll::Ready(Err(severed())),
            other => other,
        }
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        if self.cut.is_severed() {
            return Poll::Ready(Err(severed()));
        }
        match Pin::new(&mut self.inner).poll_flush(cx) {
            Poll::Pending if self.cut.park(WRITE, cx.waker()) => Poll::Ready(Err(severed())),
            other => other,
        }
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.inner).poll_shutdown(cx)
    }

    fn poll_write_vectored(mut self: Pin<&mut Self>, cx: &mut Context<'_>, bufs: &[io::IoSlice<'_>]) -> Poll<io::Result<usize>> {
        if self.cut.is_severed() {
            return Poll::Ready(Err(severed()));
        }
        match Pin::new(&mut self.inner).poll_write_vectored(cx, bufs) {
            Poll::Pending if self.cut.park(WRITE, cx.waker()) => Poll::Ready(Err(severed())),
            other => other,
        }
    }

    fn is_write_vectored(&self) -> bool {
        self.inner.is_write_vectored()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicU32;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    const FG: Duration = Duration::from_secs(30);

    /// A server that answers every request at once, counting them.
    fn answering(count: &Arc<AtomicU32>) -> impl FnMut() -> std::future::Ready<bool> {
        let count = count.clone();
        move || {
            count.fetch_add(1, Ordering::SeqCst);
            std::future::ready(true)
        }
    }

    /// Run the loop for `until` of paused time and report how many requests went out.
    async fn pings_within(rx: watch::Receiver<Option<Duration>>, activity: Arc<Activity>, until: Duration) -> (Arc<AtomicU32>, tokio::task::JoinHandle<Ended>) {
        let count = Arc::new(AtomicU32::new(0));
        let ping = answering(&count);
        let task = tokio::spawn(async move { run(rx, &activity, ping).await });
        tokio::time::sleep(until).await;
        (count, task)
    }

    #[test]
    fn the_background_interval_is_never_shorter_than_the_floor_and_off_stays_off() {
        assert_eq!(background_interval(Some(FG)), Some(BACKGROUND_FLOOR));
        assert_eq!(background_interval(Some(Duration::from_secs(300))), Some(Duration::from_secs(300)));
        assert_eq!(background_interval(None), None);
    }

    #[tokio::test(start_paused = true)]
    async fn an_idle_connection_is_pinged_once_per_interval() {
        let (control, rx) = Control::new(Some(FG));
        let (count, task) = pings_within(rx, Arc::new(Activity::new()), Duration::from_secs(95)).await;
        assert_eq!(count.load(Ordering::SeqCst), 3);
        drop(control);
        assert_eq!(task.await.unwrap(), Ended::Gone);
    }

    #[tokio::test(start_paused = true)]
    async fn incoming_data_puts_the_keepalive_off() {
        let (_control, rx) = Control::new(Some(FG));
        let activity = Arc::new(Activity::new());
        let count = Arc::new(AtomicU32::new(0));
        let ping = answering(&count);
        let a = activity.clone();
        tokio::spawn(async move { run(rx, &a, ping).await });
        // Output every twenty seconds: the thirty-second deadline never arrives.
        for _ in 0..6 {
            tokio::time::sleep(Duration::from_secs(20)).await;
            activity.stamp();
        }
        assert_eq!(count.load(Ordering::SeqCst), 0);
        tokio::time::sleep(Duration::from_secs(31)).await;
        assert_eq!(count.load(Ordering::SeqCst), 1);
    }

    #[tokio::test(start_paused = true)]
    async fn going_to_the_background_stretches_the_sleep_already_under_way() {
        let (control, rx) = Control::new(Some(FG));
        let (count, _task) = pings_within(rx, Arc::new(Activity::new()), Duration::from_secs(10)).await;
        control.set_background(true);
        // Past where the foreground interval would have fired, short of the floor.
        tokio::time::sleep(Duration::from_secs(160)).await;
        assert_eq!(count.load(Ordering::SeqCst), 0);
        tokio::time::sleep(Duration::from_secs(11)).await;
        assert_eq!(count.load(Ordering::SeqCst), 1);
    }

    #[tokio::test(start_paused = true)]
    async fn coming_back_checks_a_link_that_has_been_quiet_longer_than_the_foreground_interval() {
        let (control, rx) = Control::new(Some(FG));
        control.set_background(true);
        let (count, _task) = pings_within(rx, Arc::new(Activity::new()), Duration::from_secs(100)).await;
        assert_eq!(count.load(Ordering::SeqCst), 0);
        control.set_background(false);
        tokio::time::sleep(Duration::from_millis(1)).await;
        assert_eq!(count.load(Ordering::SeqCst), 1);
    }

    #[tokio::test(start_paused = true)]
    async fn off_sends_nothing_until_an_interval_is_set() {
        let (control, rx) = Control::new(None);
        control.set_background(true);
        let (count, _task) = pings_within(rx, Arc::new(Activity::new()), Duration::from_secs(3600)).await;
        assert_eq!(count.load(Ordering::SeqCst), 0);
    }

    #[tokio::test(start_paused = true)]
    async fn a_server_that_never_answers_is_given_up_on_after_three_requests() {
        let (_control, rx) = Control::new(Some(FG));
        let activity = Activity::new();
        let count = Arc::new(AtomicU32::new(0));
        let c = count.clone();
        let start = Instant::now();
        let ended = run(rx, &activity, move || {
            c.fetch_add(1, Ordering::SeqCst);
            std::future::pending::<bool>()
        })
        .await;
        assert_eq!(ended, Ended::Unanswered);
        assert_eq!(count.load(Ordering::SeqCst), MAX_UNANSWERED);
        // The first request at thirty seconds, then each one given thirty to answer.
        assert_eq!(start.elapsed(), FG * (MAX_UNANSWERED + 1));
    }

    #[tokio::test(start_paused = true)]
    async fn coming_back_shortens_the_wait_for_a_reply_already_under_way() {
        let (control, rx) = Control::new(Some(FG));
        control.set_background(true);
        let activity = Activity::new();
        let start = Instant::now();
        let back = tokio::spawn({
            let control = control.clone();
            async move {
                // The first background request goes out at the floor; come back
                // a minute into the wait for its reply.
                tokio::time::sleep(BACKGROUND_FLOOR + Duration::from_secs(60)).await;
                control.set_background(false);
            }
        });
        let ended = run(rx, &activity, || std::future::pending::<bool>()).await;
        back.await.unwrap();
        assert_eq!(ended, Ended::Unanswered);
        // The first request is already past the foreground wait and fails at
        // once; the other two get thirty seconds each. Without that it is
        // another two minutes, then two more full background waits.
        assert_eq!(start.elapsed(), BACKGROUND_FLOOR + Duration::from_secs(60) + FG * (MAX_UNANSWERED - 1));
    }

    #[tokio::test(start_paused = true)]
    async fn output_arriving_counts_as_an_answer() {
        let (_control, rx) = Control::new(Some(FG));
        let activity = Arc::new(Activity::new());
        let a = activity.clone();
        let task = tokio::spawn(async move { run(rx, &a, || std::future::pending::<bool>()).await });
        // The server never replies to the request, but keeps sending output
        // now and then — slower than the interval, faster than giving up.
        for _ in 0..10 {
            tokio::time::sleep(Duration::from_secs(45)).await;
            activity.stamp();
        }
        assert!(!task.is_finished());
    }

    #[tokio::test(start_paused = true)]
    async fn a_closed_connection_ends_the_loop() {
        let (_control, rx) = Control::new(Some(FG));
        let ended = run(rx, &Activity::new(), || std::future::ready(false)).await;
        assert_eq!(ended, Ended::Gone);
    }

    #[tokio::test]
    async fn a_cut_fails_a_read_that_is_already_waiting() {
        let (ours, mut theirs) = tokio::io::duplex(64);
        let cut = Cut::new();
        let mut stream = Severable::new(ours, cut.clone());
        let reader = tokio::spawn(async move {
            let mut buf = [0u8; 8];
            let first = stream.read(&mut buf).await.map(|n| buf[..n].to_vec());
            let second = stream.read(&mut buf).await;
            (first, second)
        });
        theirs.write_all(b"hi").await.unwrap();
        tokio::task::yield_now().await;
        cut.sever();
        let (first, second) = reader.await.unwrap();
        assert_eq!(first.unwrap(), b"hi");
        assert_eq!(second.unwrap_err().kind(), io::ErrorKind::TimedOut);
    }
}
