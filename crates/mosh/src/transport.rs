//! The state machine: which instruction to send, what to acknowledge, and
//! which arriving diff may be applied.
//!
//! The client keeps a single received state rather than mosh's list of them.
//! An instruction is applied only when its `old_num` matches the state we are
//! actually on, and we only acknowledge what we have applied — so the server,
//! which always diffs from the last state it saw acknowledged, will keep
//! re-sending until it sends one we can use. That converges to the same screen
//! as the reference client while costing at most a retransmit under loss, and
//! it means the terminal never has to be rolled back, which matters because a
//! real emulator cannot be un-fed bytes.

use std::collections::VecDeque;

use crate::fragment::{Fragment, FragmentAssembly, Fragmenter};
use crate::packet::{timestamp16, timestamp_diff, Packet, TIMESTAMP_NONE};
use crate::proto::{decode_host_message, encode_user_message, HostEvent, Instruction, UserEvent, MOSH_PROTOCOL_VERSION};
use crate::{Direction, MoshError, Session};

/// Milliseconds between frames when there is something to send.
pub const SEND_INTERVAL_MIN: u64 = 20;
/// Milliseconds between frames when the link is idle.
pub const SEND_INTERVAL_MAX: u64 = 250;
/// Send an otherwise empty instruction at least this often, to keep the link
/// alive and the peer's idea of our acknowledgement fresh.
pub const ACK_INTERVAL: u64 = 3000;
/// How long to sit on new input, collecting more before sending.
pub const SEND_MINDELAY: u64 = 8;
/// How long to sit on a new state before acknowledging it. The server will not
/// move past a state it has not seen acknowledged, so this cannot be skipped.
pub const ACK_DELAY: u64 = 100;
/// Give up on a silent server after this long.
pub const ACTIVE_RETRY_TIMEOUT: u64 = 10_000;

/// Payload budget for one datagram, leaving room for IP, UDP and the AEAD tag.
pub const DEFAULT_MTU: usize = 1280;

/// What arriving data meant.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TransportEvent {
    /// Terminal output to feed the emulator, as an escape-sequence stream.
    Output(Vec<u8>),
    /// The server's window size changed.
    Resize { width: i32, height: i32 },
    /// Our input has been echoed through to this state number.
    EchoAck(u64),
    /// The server is shutting down (the shell exited); nothing more will come.
    Shutdown,
}

/// The state number mosh uses to mean "I am shutting down": every field that
/// would carry a state number is set to all-ones instead.
pub const SHUTDOWN_NUM: u64 = u64::MAX;

/// One state of the stream we send: everything the user has done so far.
struct SentState {
    num: u64,
    /// Events in this state that were not in the one before it.
    events: Vec<UserEvent>,
}

pub struct Transport {
    session: Session,
    fragmenter: Fragmenter,
    assembly: FragmentAssembly,

    /// Monotonic nonce counter. Every datagram we send gets a fresh sequence.
    next_seq: u64,

    // --- what we send ---
    sent: VecDeque<SentState>,
    next_state_num: u64,
    /// Highest state of ours the server has acknowledged.
    sent_acked: u64,
    /// Events not yet folded into a state.
    pending: Vec<UserEvent>,
    pending_since: Option<u64>,

    // --- what we receive ---
    /// The state number our terminal currently reflects.
    received_num: u64,
    /// Highest state we have applied and told the server about.
    last_ack_sent: u64,
    /// When we applied a state we have not acknowledged yet.
    ack_pending_since: Option<u64>,

    // --- timing ---
    last_send: u64,
    /// A server only learns the client's address from a datagram, so the first
    /// tick always sends, whatever the timers say.
    ever_sent: bool,
    last_heard: Option<u64>,
    /// The most recent timestamp the peer sent us, and when we saw it.
    saved_timestamp: Option<(u16, u64)>,
    /// Smoothed round-trip time in milliseconds; None until a reply comes back.
    srtt_ms: Option<f64>,
    mtu: usize,
}

impl Transport {
    /// A session needs a window size before the server's terminal will produce
    /// anything at all, so it is taken here rather than left to the caller to
    /// remember: the size is queued as the first thing we send.
    pub fn new(session: Session, cols: u16, rows: u16) -> Self {
        let mut t = Self::without_size(session);
        t.push(UserEvent::Resize { width: cols as i32, height: rows as i32 }, 0);
        t
    }

    fn without_size(session: Session) -> Self {
        Self {
            session,
            fragmenter: Fragmenter::new(),
            assembly: FragmentAssembly::new(),
            next_seq: 0,
            sent: VecDeque::new(),
            next_state_num: 1,
            sent_acked: 0,
            pending: Vec::new(),
            pending_since: None,
            received_num: 0,
            last_ack_sent: 0,
            ack_pending_since: None,
            last_send: 0,
            ever_sent: false,
            last_heard: None,
            saved_timestamp: None,
            srtt_ms: None,
            mtu: DEFAULT_MTU,
        }
    }

    /// Queue user input. It is sent on the next tick, after a short collection
    /// delay so a burst of typing becomes one instruction.
    pub fn push(&mut self, event: UserEvent, now: u64) {
        self.pending.push(event);
        self.pending_since.get_or_insert(now);
    }

    /// True once the server has acknowledged everything we have sent.
    pub fn all_acked(&self) -> bool {
        self.pending.is_empty() && self.sent_acked + 1 == self.next_state_num
    }

    /// The state number of the newest thing we have sent.
    pub fn latest_sent(&self) -> u64 {
        self.next_state_num - 1
    }

    /// The state our terminal reflects.
    pub fn received_state(&self) -> u64 {
        self.received_num
    }

    /// When [`tick`] should next be called, in milliseconds from `now`.
    pub fn wait_time(&self, now: u64) -> u64 {
        if !self.ever_sent {
            return 0;
        }
        let deadline = if !self.pending.is_empty() {
            self.pending_since.unwrap_or(now) + SEND_MINDELAY
        } else if let Some(t) = self.ack_pending_since {
            t + ACK_DELAY
        } else if !self.all_acked() {
            self.last_send + SEND_INTERVAL_MIN
        } else {
            self.last_send + ACK_INTERVAL.min(SEND_INTERVAL_MAX.max(ACK_INTERVAL))
        };
        deadline.saturating_sub(now)
    }

    /// Whether the server has gone quiet long enough to call the link dead.
    pub fn timed_out(&self, now: u64) -> bool {
        matches!(self.last_heard, Some(t) if now.saturating_sub(t) > ACTIVE_RETRY_TIMEOUT)
    }

    /// Smoothed round-trip time, once a timestamp of ours has come back. This is
    /// what decides whether predictive echo is worth switching on.
    pub fn srtt_ms(&self) -> Option<f64> {
        self.srtt_ms
    }

    /// Milliseconds since anything was heard from the server, if ever.
    pub fn silent_for(&self, now: u64) -> Option<u64> {
        self.last_heard.map(|t| now.saturating_sub(t))
    }

    /// Datagrams to send right now. Empty when it is not yet time.
    pub fn tick(&mut self, now: u64) -> Result<Vec<Vec<u8>>, MoshError> {
        let input_ready = !self.pending.is_empty()
            && (!self.ever_sent || now.saturating_sub(self.pending_since.unwrap_or(now)) >= SEND_MINDELAY);
        let retransmit = !self.all_acked() && now.saturating_sub(self.last_send) >= SEND_INTERVAL_MIN;
        let heartbeat = now.saturating_sub(self.last_send) >= ACK_INTERVAL;
        let ack_due = matches!(self.ack_pending_since, Some(t) if now.saturating_sub(t) >= ACK_DELAY);
        // A shutdown ack is the one ack worth sending straight away: the server
        // is waiting for it before it exits, and nothing else is coming.
        let closing = self.received_num == SHUTDOWN_NUM && self.ack_pending_since.is_some();
        if !(input_ready || retransmit || heartbeat || ack_due || closing || !self.ever_sent) {
            return Ok(Vec::new());
        }

        if input_ready {
            let events = std::mem::take(&mut self.pending);
            self.pending_since = None;
            self.sent.push_back(SentState { num: self.next_state_num, events });
            self.next_state_num += 1;
        }

        // Diff from the last state the server acknowledged: everything since.
        let diff_events: Vec<UserEvent> =
            self.sent.iter().filter(|s| s.num > self.sent_acked).flat_map(|s| s.events.iter().cloned()).collect();

        let instruction = Instruction {
            protocol_version: MOSH_PROTOCOL_VERSION,
            old_num: self.sent_acked,
            new_num: self.latest_sent(),
            ack_num: self.received_num,
            throwaway_num: self.sent_acked,
            diff: encode_user_message(&diff_events),
            chaff: Vec::new(),
        };

        self.last_send = now;
        self.ever_sent = true;
        self.last_ack_sent = self.received_num;
        self.ack_pending_since = None;
        let fragments = self.fragmenter.make_fragments(&instruction.encode(), self.mtu)?;
        fragments.into_iter().map(|f| self.wrap(f, now)).collect()
    }

    /// Encrypt one fragment into a datagram.
    fn wrap(&mut self, fragment: Fragment, now: u64) -> Result<Vec<u8>, MoshError> {
        // Echo the peer's last timestamp, corrected for how long we held it, so
        // its round-trip estimate excludes our own think time.
        let reply = match self.saved_timestamp {
            Some((ts, at)) if now.saturating_sub(at) < 1000 => ts.wrapping_add(timestamp16(now.saturating_sub(at))),
            _ => TIMESTAMP_NONE,
        };
        let packet = Packet::new(timestamp16(now), reply, fragment.encode());
        let seq = self.next_seq;
        self.next_seq += 1;
        self.session.encrypt(Direction::ToServer, seq, &packet.encode())
    }

    /// Feed one received datagram. Returns what it meant for the terminal.
    pub fn receive(&mut self, datagram: &[u8], now: u64) -> Result<Vec<TransportEvent>, MoshError> {
        let (direction, _seq, plaintext) = self.session.decrypt(datagram)?;
        if direction != Direction::ToClient {
            return Err(MoshError::Protocol("datagram was not addressed to the client".into()));
        }
        let packet = Packet::decode(&plaintext)?;
        self.last_heard = Some(now);
        self.saved_timestamp = Some((packet.timestamp, now));
        // The server echoes our timestamp back, corrected for how long it held
        // it, so the difference is the round trip without its think time.
        if packet.timestamp_reply != TIMESTAMP_NONE {
            let rtt = timestamp_diff(timestamp16(now), packet.timestamp_reply) as f64;
            // Ignore an implausible sample: the 16-bit clock wraps every 65s.
            if rtt < 10_000.0 {
                self.srtt_ms = Some(match self.srtt_ms {
                    Some(prev) => prev * 7.0 / 8.0 + rtt / 8.0,
                    None => rtt,
                });
            }
        }

        if !self.assembly.add(Fragment::decode(&packet.payload)?) {
            return Ok(Vec::new());
        }
        let instruction = Instruction::decode(&self.assembly.take()?)?;
        if instruction.protocol_version != MOSH_PROTOCOL_VERSION {
            return Err(MoshError::Protocol(format!(
                "server speaks protocol version {}, we speak {MOSH_PROTOCOL_VERSION}",
                instruction.protocol_version
            )));
        }

        // The server tells us how much of our input it has taken.
        if instruction.ack_num > self.sent_acked {
            self.sent_acked = instruction.ack_num;
            self.sent.retain(|s| s.num > instruction.ack_num);
        }

        // A shutdown is announced by an all-ones state number, and repeated
        // until acknowledged. Report it once; later copies are only acked.
        let shutdown = instruction.new_num == SHUTDOWN_NUM;
        if shutdown && self.received_num == SHUTDOWN_NUM {
            self.ack_pending_since.get_or_insert(now);
            return Ok(Vec::new());
        }
        if instruction.new_num <= self.received_num {
            return Ok(Vec::new()); // already applied
        }
        if instruction.old_num != self.received_num {
            // We are missing the state this diff is against. Staying silent
            // about it makes the server diff from what we last acknowledged.
            return Ok(Vec::new());
        }

        self.received_num = instruction.new_num;
        // The server waits for this before it will send the next state.
        self.ack_pending_since.get_or_insert(now);
        let mut events: Vec<TransportEvent> = decode_host_message(&instruction.diff)?
            .into_iter()
            .map(|e| match e {
                HostEvent::Bytes(b) => TransportEvent::Output(b),
                HostEvent::Resize { width, height } => TransportEvent::Resize { width, height },
                HostEvent::EchoAck(n) => TransportEvent::EchoAck(n),
            })
            .collect();
        // The last output first, then the news that this was the last of it.
        if shutdown {
            events.push(TransportEvent::Shutdown);
        }
        Ok(events)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::{HostEvent, MOSH_PROTOCOL_VERSION};
    use crate::Base64Key;

    fn key() -> Base64Key {
        Base64Key::parse("AAAAAAAAAAAAAAAAAAAAAA").unwrap()
    }

    fn transport() -> Transport {
        Transport::without_size(Session::new(&key()))
    }

    /// The shell exiting has to end the session there and then. Before this was
    /// handled, the client sat waiting for the link to time out — a minute of
    /// "still connected" after the shell was gone.
    #[test]
    fn a_server_shutdown_ends_the_session_and_is_acked_at_once() {
        let mut t = transport();
        let last = server_datagram(0, SHUTDOWN_NUM, 0, &[HostEvent::Bytes(b"logout\r\n".to_vec())], 1);
        let events = t.receive(&last, 10).unwrap();
        assert_eq!(
            events,
            vec![TransportEvent::Output(b"logout\r\n".to_vec()), TransportEvent::Shutdown],
            "the final output comes first, then the shutdown",
        );
        // The ack must not wait for the usual delay: the server exits on it.
        let datagrams = t.tick(10).unwrap();
        assert!(!datagrams.is_empty(), "the shutdown should be acknowledged immediately");
        // Retries of the same shutdown are acked but not reported twice.
        assert!(t.receive(&server_datagram(0, SHUTDOWN_NUM, 0, &[], 2), 20).unwrap().is_empty());
    }

    /// Build a server-to-client datagram the way a real server would.
    fn server_datagram(old_num: u64, new_num: u64, ack_num: u64, events: &[HostEvent], seq: u64) -> Vec<u8> {
        let mut diff = Vec::new();
        for e in events {
            let mut instruction = Vec::new();
            match e {
                HostEvent::Bytes(b) => {
                    let mut hb = Vec::new();
                    push_bytes(&mut hb, 4, b);
                    push_bytes(&mut instruction, 2, &hb);
                }
                HostEvent::Resize { width, height } => {
                    let mut r = Vec::new();
                    push_varint(&mut r, 5, *width as u64);
                    push_varint(&mut r, 6, *height as u64);
                    push_bytes(&mut instruction, 3, &r);
                }
                HostEvent::EchoAck(n) => {
                    let mut e = Vec::new();
                    push_varint(&mut e, 8, *n);
                    push_bytes(&mut instruction, 7, &e);
                }
            }
            push_bytes(&mut diff, 1, &instruction);
        }
        let inst = Instruction {
            protocol_version: MOSH_PROTOCOL_VERSION,
            old_num,
            new_num,
            ack_num,
            throwaway_num: 0,
            diff,
            chaff: Vec::new(),
        };
        let mut fragmenter = Fragmenter::new();
        let frags = fragmenter.make_fragments(&inst.encode(), 4096).unwrap();
        assert_eq!(frags.len(), 1, "test helper only builds single-fragment instructions");
        let packet = Packet::new(1, TIMESTAMP_NONE, frags[0].encode());
        Session::new(&key()).encrypt(Direction::ToClient, seq, &packet.encode()).unwrap()
    }

    fn push_varint(out: &mut Vec<u8>, field: u32, v: u64) {
        let mut key = ((field as u64) << 3) as u64;
        write_varint(out, &mut key);
        let mut v = v;
        write_varint(out, &mut v);
    }
    fn push_bytes(out: &mut Vec<u8>, field: u32, v: &[u8]) {
        let mut key = (((field as u64) << 3) | 2) as u64;
        write_varint(out, &mut key);
        let mut len = v.len() as u64;
        write_varint(out, &mut len);
        out.extend_from_slice(v);
    }
    fn write_varint(out: &mut Vec<u8>, v: &mut u64) {
        loop {
            let b = (*v & 0x7f) as u8;
            *v >>= 7;
            if *v == 0 {
                out.push(b);
                return;
            }
            out.push(b | 0x80);
        }
    }

    #[test]
    fn a_new_transport_leads_with_the_window_size() {
        let mut t = Transport::new(Session::new(&key()), 80, 24);
        let dg = t.tick(0).unwrap().pop().expect("the opening datagram");
        let (_, _, plaintext) = Session::new(&key()).decrypt(&dg).unwrap();
        let packet = Packet::decode(&plaintext).unwrap();
        let mut asm = FragmentAssembly::new();
        assert!(asm.add(Fragment::decode(&packet.payload).unwrap()));
        let inst = Instruction::decode(&asm.take().unwrap()).unwrap();
        assert_eq!(inst.new_num, 1, "the size is state 1");
        assert_eq!(inst.diff, encode_user_message(&[UserEvent::Resize { width: 80, height: 24 }]));
    }

    #[test]
    fn the_first_tick_always_announces_us_to_the_server() {
        let mut t = transport();
        assert_eq!(t.wait_time(0), 0, "we owe the server a datagram before anything can happen");
        assert_eq!(t.tick(0).unwrap().len(), 1, "the server cannot reply until it has heard from us");
        assert_eq!(t.wait_time(0), ACK_INTERVAL, "then an idle client next speaks at the heartbeat");
    }

    #[test]
    fn nothing_to_say_produces_nothing_until_the_heartbeat() {
        let mut t = transport();
        t.tick(0).unwrap(); // the opening datagram
        assert!(t.tick(1).unwrap().is_empty());
        assert!(t.tick(100).unwrap().is_empty());
        assert!(!t.tick(ACK_INTERVAL + 1).unwrap().is_empty(), "a heartbeat is due");
    }

    #[test]
    fn input_is_collected_before_being_sent() {
        let mut t = transport();
        t.tick(0).unwrap();
        t.push(UserEvent::Keystroke(b"a".to_vec()), 0);
        assert!(t.tick(1).unwrap().is_empty(), "should still be collecting");
        t.push(UserEvent::Keystroke(b"b".to_vec()), 2);
        let out = t.tick(SEND_MINDELAY + 1).unwrap();
        assert_eq!(out.len(), 1);
        assert_eq!(t.latest_sent(), 1, "both keystrokes went into one state");
    }

    #[test]
    fn unacked_input_is_retransmitted() {
        let mut t = transport();
        t.tick(0).unwrap();
        t.push(UserEvent::Keystroke(b"x".to_vec()), 0);
        assert_eq!(t.tick(SEND_MINDELAY).unwrap().len(), 1);
        assert!(!t.all_acked());
        assert!(t.tick(SEND_MINDELAY + 1).unwrap().is_empty(), "too soon to retry");
        assert_eq!(t.tick(SEND_MINDELAY + SEND_INTERVAL_MIN).unwrap().len(), 1, "retransmit is due");
    }

    #[test]
    fn an_ack_stops_the_retransmits() {
        let mut t = transport();
        t.tick(0).unwrap();
        t.push(UserEvent::Keystroke(b"x".to_vec()), 0);
        t.tick(SEND_MINDELAY).unwrap();
        assert!(!t.all_acked());

        let dg = server_datagram(0, 1, 1, &[HostEvent::Bytes(b"ok".to_vec())], 0);
        t.receive(&dg, 100).unwrap();
        assert!(t.all_acked(), "state 1 was acknowledged");
    }

    #[test]
    fn output_reaches_the_caller() {
        let mut t = transport();
        let dg = server_datagram(0, 1, 0, &[HostEvent::Bytes(b"\x1b[2Jhello".to_vec())], 0);
        let events = t.receive(&dg, 10).unwrap();
        assert_eq!(events, vec![TransportEvent::Output(b"\x1b[2Jhello".to_vec())]);
        assert_eq!(t.received_state(), 1);
    }

    /// The server will not advance past a state it has not seen acknowledged,
    /// so applying one must schedule an ack rather than wait for the heartbeat.
    #[test]
    fn applying_a_state_makes_an_ack_due_promptly() {
        let mut t = transport();
        t.tick(0).unwrap();
        let dg = server_datagram(0, 1, 0, &[HostEvent::Bytes(b"hi".to_vec())], 0);
        t.receive(&dg, 10).unwrap();

        assert_eq!(t.wait_time(10), ACK_DELAY, "an ack should be pending");
        assert!(t.tick(10).unwrap().is_empty(), "not yet: the ack is delayed to coalesce");
        let out = t.tick(10 + ACK_DELAY).unwrap();
        assert_eq!(out.len(), 1, "the ack must go out well before the heartbeat");
        assert!(ACK_DELAY < ACK_INTERVAL, "otherwise the ack is pointless");
    }

    #[test]
    fn the_ack_carries_the_state_we_applied() {
        let mut t = transport();
        t.tick(0).unwrap();
        t.receive(&server_datagram(0, 4, 0, &[], 0), 10).unwrap();
        let dg = t.tick(10 + ACK_DELAY).unwrap().pop().expect("an ack");
        // Unwrap it the way the server would, to read the ack_num back out.
        let (_, _, plaintext) = Session::new(&key()).decrypt(&dg).unwrap();
        let packet = Packet::decode(&plaintext).unwrap();
        let mut asm = FragmentAssembly::new();
        assert!(asm.add(Fragment::decode(&packet.payload).unwrap()));
        let inst = Instruction::decode(&asm.take().unwrap()).unwrap();
        assert_eq!(inst.ack_num, 4, "we must tell the server which state we reached");
    }

    #[test]
    fn a_repeated_state_is_applied_once() {
        let mut t = transport();
        let dg = server_datagram(0, 1, 0, &[HostEvent::Bytes(b"once".to_vec())], 0);
        assert_eq!(t.receive(&dg, 10).unwrap().len(), 1);
        let again = server_datagram(0, 1, 0, &[HostEvent::Bytes(b"once".to_vec())], 1);
        assert!(t.receive(&again, 11).unwrap().is_empty(), "must not apply the same state twice");
        assert_eq!(t.received_state(), 1);
    }

    #[test]
    fn a_diff_against_a_state_we_do_not_have_is_ignored() {
        let mut t = transport();
        // Jumps from state 5, which we never saw.
        let dg = server_datagram(5, 6, 0, &[HostEvent::Bytes(b"lost".to_vec())], 0);
        assert!(t.receive(&dg, 10).unwrap().is_empty());
        assert_eq!(t.received_state(), 0, "we stay where we are so the server re-diffs");

        // The server falls back to diffing from what we acknowledged.
        let recovery = server_datagram(0, 6, 0, &[HostEvent::Bytes(b"found".to_vec())], 1);
        assert_eq!(t.receive(&recovery, 11).unwrap(), vec![TransportEvent::Output(b"found".to_vec())]);
        assert_eq!(t.received_state(), 6);
    }

    #[test]
    fn states_apply_in_order() {
        let mut t = transport();
        for (old, new, text) in [(0u64, 1u64, "one"), (1, 2, "two"), (2, 3, "three")] {
            let dg = server_datagram(old, new, 0, &[HostEvent::Bytes(text.as_bytes().to_vec())], new);
            assert_eq!(t.receive(&dg, 10 * new).unwrap(), vec![TransportEvent::Output(text.as_bytes().to_vec())]);
        }
        assert_eq!(t.received_state(), 3);
    }

    #[test]
    fn resize_and_echo_ack_come_through() {
        let mut t = transport();
        let dg = server_datagram(
            0,
            1,
            0,
            &[HostEvent::Resize { width: 100, height: 40 }, HostEvent::EchoAck(3)],
            0,
        );
        assert_eq!(
            t.receive(&dg, 10).unwrap(),
            vec![TransportEvent::Resize { width: 100, height: 40 }, TransportEvent::EchoAck(3)],
        );
    }

    #[test]
    fn a_datagram_going_the_wrong_way_is_refused() {
        let mut t = transport();
        // Same key, but marked client-to-server: a reflection, not a reply.
        let packet = Packet::new(1, TIMESTAMP_NONE, vec![0u8; 10]);
        let dg = Session::new(&key()).encrypt(Direction::ToServer, 1, &packet.encode()).unwrap();
        assert!(t.receive(&dg, 10).is_err());
    }

    #[test]
    fn a_wrong_protocol_version_is_reported() {
        let mut t = transport();
        let inst = Instruction { protocol_version: 99, new_num: 1, ..Default::default() };
        let mut f = Fragmenter::new();
        let frags = f.make_fragments(&inst.encode(), 4096).unwrap();
        let packet = Packet::new(1, TIMESTAMP_NONE, frags[0].encode());
        let dg = Session::new(&key()).encrypt(Direction::ToClient, 0, &packet.encode()).unwrap();
        let err = t.receive(&dg, 10).unwrap_err().to_string();
        assert!(err.contains("protocol version 99"), "{err}");
    }

    #[test]
    fn a_returned_timestamp_gives_a_round_trip_estimate() {
        let mut t = transport();
        t.tick(0).unwrap();
        assert_eq!(t.srtt_ms(), None, "nothing measured yet");

        // A reply echoing the timestamp we sent at t=0, arriving at t=40.
        let mut diff = Vec::new();
        let inst = Instruction { protocol_version: MOSH_PROTOCOL_VERSION, new_num: 1, ..Default::default() };
        diff.extend_from_slice(&inst.encode());
        let mut f = Fragmenter::new();
        let frags = f.make_fragments(&diff, 4096).unwrap();
        let packet = Packet::new(7, timestamp16(0), frags[0].encode());
        let dg = Session::new(&key()).encrypt(Direction::ToClient, 0, &packet.encode()).unwrap();
        t.receive(&dg, 40).unwrap();
        assert_eq!(t.srtt_ms(), Some(40.0));
    }

    #[test]
    fn the_link_is_declared_dead_only_after_silence() {
        let mut t = transport();
        assert!(!t.timed_out(999_999), "never having heard anything is not a timeout");
        let dg = server_datagram(0, 1, 0, &[], 0);
        t.receive(&dg, 1000).unwrap();
        assert!(!t.timed_out(1000 + ACTIVE_RETRY_TIMEOUT));
        assert!(t.timed_out(1001 + ACTIVE_RETRY_TIMEOUT));
        assert_eq!(t.silent_for(2000), Some(1000));
    }

    #[test]
    fn every_datagram_uses_a_fresh_sequence_number() {
        let mut t = transport();
        let mut seqs = Vec::new();
        for i in 0..4u64 {
            t.push(UserEvent::Keystroke(vec![b'a' + i as u8]), i * 100);
            for dg in t.tick(i * 100 + SEND_MINDELAY).unwrap() {
                let (_, seq, _) = Session::new(&key()).decrypt(&dg).unwrap();
                seqs.push(seq);
            }
        }
        let mut sorted = seqs.clone();
        sorted.sort_unstable();
        sorted.dedup();
        assert_eq!(sorted.len(), seqs.len(), "sequence numbers must never repeat: {seqs:?}");
    }
}
