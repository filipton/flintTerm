//! A session whose transport lives on the Kotlin side.
//!
//! Used for USB serial: Android owns the device handle, so the app pushes the
//! bytes it reads into [`ExternalPipe::push`] and receives the terminal's
//! keystrokes through [`ExternalSink`]. From the session's point of view this
//! is just another byte pipe.

use std::sync::Arc;

use bytes::Bytes;
use parking_lot::Mutex;
use tokio::sync::mpsc::{unbounded_channel, UnboundedReceiver, UnboundedSender};

use crate::transport::Event;

/// Implemented on the Kotlin side; the other half of an external transport.
#[uniffi::export(foreign)]
pub trait ExternalSink: Send + Sync {
    /// Bytes typed in the terminal, to be written to the device.
    fn on_input(&self, data: Vec<u8>);
    /// The terminal grid changed size (serial consoles can pass this on with `stty`).
    fn on_resize(&self, cols: u16, rows: u16);
    /// The session is going away; release the device.
    fn on_close(&self);
}

/// Shared endpoint between the app's device thread and the session's pump.
pub struct ExternalPipe {
    tx: UnboundedSender<Event>,
    rx: Mutex<Option<UnboundedReceiver<Event>>>,
    sink: Mutex<Option<Arc<dyn ExternalSink>>>,
}

impl ExternalPipe {
    pub fn new() -> Self {
        let (tx, rx) = unbounded_channel();
        Self { tx, rx: Mutex::new(Some(rx)), sink: Mutex::new(None) }
    }

    pub fn set_sink(&self, sink: Arc<dyn ExternalSink>) {
        *self.sink.lock() = Some(sink);
    }

    /// Bytes read from the device, on their way to the emulator.
    pub fn push(&self, data: Vec<u8>) {
        let _ = self.tx.send(Event::Data(Bytes::from(data)));
    }

    /// The device went away (unplugged, closed).
    pub fn finish(&self) {
        let _ = self.tx.send(Event::Closed);
    }

    /// Taken once, when the session connects.
    pub fn take_reader(&self) -> Option<ExternalReader> {
        self.rx.lock().take().map(|rx| ExternalReader { rx })
    }

    fn sink(&self) -> Option<Arc<dyn ExternalSink>> {
        self.sink.lock().clone()
    }
}

impl Default for ExternalPipe {
    fn default() -> Self {
        Self::new()
    }
}

pub struct ExternalReader {
    rx: UnboundedReceiver<Event>,
}

impl ExternalReader {
    pub async fn next(&mut self) -> Event {
        self.rx.recv().await.unwrap_or(Event::Closed)
    }
}

#[derive(Clone)]
pub struct ExternalWriter {
    pipe: Arc<ExternalPipe>,
}

impl ExternalWriter {
    pub fn new(pipe: Arc<ExternalPipe>) -> Self {
        Self { pipe }
    }

    pub fn write(&self, bytes: Vec<u8>) -> bool {
        match self.pipe.sink() {
            Some(s) => {
                s.on_input(bytes);
                true
            }
            // Nothing attached yet: drop the keystroke rather than stall the pump.
            None => true,
        }
    }

    pub fn resize(&self, cols: u16, rows: u16) {
        if let Some(s) = self.pipe.sink() {
            s.on_resize(cols, rows);
        }
    }

    pub fn close(&self) {
        if let Some(s) = self.pipe.sink() {
            s.on_close();
        }
        self.pipe.finish();
    }
}
