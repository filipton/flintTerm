//! The two 16-bit timestamps every encrypted payload carries in front of the
//! transport instruction. They are what the link-quality estimate is built on:
//! each side echoes back the last timestamp it saw, corrected for how long it
//! sat on it, so a round trip can be measured without a clock in common.

use crate::MoshError;

/// The timestamp value meaning "I have nothing recent to echo".
pub const TIMESTAMP_NONE: u16 = u16::MAX;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Packet {
    pub timestamp: u16,
    pub timestamp_reply: u16,
    pub payload: Vec<u8>,
}

impl Packet {
    pub fn new(timestamp: u16, timestamp_reply: u16, payload: Vec<u8>) -> Self {
        Self { timestamp, timestamp_reply, payload }
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(4 + self.payload.len());
        out.extend_from_slice(&self.timestamp.to_be_bytes());
        out.extend_from_slice(&self.timestamp_reply.to_be_bytes());
        out.extend_from_slice(&self.payload);
        out
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, MoshError> {
        if bytes.len() < 4 {
            return Err(MoshError::Protocol("packet shorter than its timestamps".into()));
        }
        Ok(Self {
            timestamp: u16::from_be_bytes([bytes[0], bytes[1]]),
            timestamp_reply: u16::from_be_bytes([bytes[2], bytes[3]]),
            payload: bytes[4..].to_vec(),
        })
    }
}

/// Milliseconds since an arbitrary start, wrapped into the 16 bits the
/// protocol carries. Only differences are meaningful.
pub fn timestamp16(millis: u64) -> u16 {
    (millis % 65536) as u16
}

/// Difference between two wrapped timestamps, tolerating one wrap.
pub fn timestamp_diff(later: u16, earlier: u16) -> u16 {
    later.wrapping_sub(earlier)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips() {
        let p = Packet::new(0x1234, 0xabcd, b"instruction".to_vec());
        let bytes = p.encode();
        assert_eq!(&bytes[..4], &[0x12, 0x34, 0xab, 0xcd]);
        assert_eq!(Packet::decode(&bytes).unwrap(), p);
    }

    #[test]
    fn an_empty_payload_is_legal() {
        let p = Packet::new(1, TIMESTAMP_NONE, Vec::new());
        assert_eq!(Packet::decode(&p.encode()).unwrap(), p);
    }

    #[test]
    fn a_truncated_packet_is_rejected() {
        assert!(Packet::decode(&[0, 1, 2]).is_err());
    }

    #[test]
    fn timestamps_wrap() {
        assert_eq!(timestamp16(65536 + 5), 5);
        assert_eq!(timestamp_diff(5, 65535), 6);
        assert_eq!(timestamp_diff(300, 100), 200);
    }
}
