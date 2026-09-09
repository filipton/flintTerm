//! An independent client implementation of mosh's State Synchronization
//! Protocol, speaking to the stock `mosh-server` (`MOSH_PROTOCOL_VERSION = 2`).
//!
//! Written against the wire format rather than ported from mosh's C++, so this
//! crate carries the workspace's own license. The layers stack bottom-up:
//!
//! 1. [`crypto`]   — the base64 session key and the AES-128-OCB3 datagram.
//! 2. [`packet`]   — the two 16-bit timestamps inside every payload.
//! 3. [`fragment`] — zlib, and cutting an instruction to fit the MTU.
//! 4. [`proto`]    — the protobuf messages, hand-coded.
//! 5. [`transport`] — the state machine: what to send, what to ack, what to apply.
//!
//! The host's terminal diff is an escape-sequence stream, not a screen dump, so
//! it is handed straight to whatever emulator the caller already has. That is
//! why there is no framebuffer in here.
//!
//! Predictive local echo lives in [`predict`]; it holds only guesses and never
//! feeds the emulator, so a bad guess cannot corrupt the authoritative screen.

pub mod crypto;
pub mod fragment;
pub mod packet;
pub mod predict;
pub mod proto;
pub mod transport;

pub use crypto::{Base64Key, Direction, Session};
pub use packet::Packet;
pub use predict::{PredictMode, Prediction, Predictor};
pub use proto::{HostEvent, Instruction, UserEvent, MOSH_PROTOCOL_VERSION};
pub use transport::{Transport, TransportEvent};

#[derive(Debug, thiserror::Error)]
pub enum MoshError {
    #[error("session key: {0}")]
    Key(String),
    #[error("crypto: {0}")]
    Crypto(String),
    #[error("protocol: {0}")]
    Protocol(String),
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
}
