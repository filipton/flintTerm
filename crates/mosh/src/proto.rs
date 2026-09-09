//! The handful of protobuf messages the protocol uses.
//!
//! mosh's `.proto` files declare the per-message extensions as ordinary fields
//! of the enclosing `Instruction`, and an extension is wire-identical to a
//! normal field of the same number, so the messages here are written directly
//! against the field numbers rather than through a generated extension API.
//! The set is small enough that hand-rolled coding beats a `protoc` build step.

use crate::MoshError;

// ---------------------------------------------------------------------------
// Minimal protobuf primitives
// ---------------------------------------------------------------------------

const WIRE_VARINT: u32 = 0;
const WIRE_FIXED64: u32 = 1;
const WIRE_BYTES: u32 = 2;
const WIRE_FIXED32: u32 = 5;

fn put_varint(out: &mut Vec<u8>, mut v: u64) {
    loop {
        let byte = (v & 0x7f) as u8;
        v >>= 7;
        if v == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

fn put_tag(out: &mut Vec<u8>, field: u32, wire: u32) {
    put_varint(out, ((field as u64) << 3) | wire as u64);
}

fn put_varint_field(out: &mut Vec<u8>, field: u32, v: u64) {
    put_tag(out, field, WIRE_VARINT);
    put_varint(out, v);
}

fn put_bytes_field(out: &mut Vec<u8>, field: u32, v: &[u8]) {
    put_tag(out, field, WIRE_BYTES);
    put_varint(out, v.len() as u64);
    out.extend_from_slice(v);
}

/// Walks the fields of a message, skipping anything it does not know.
struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

enum Value<'a> {
    Varint(u64),
    Bytes(&'a [u8]),
}

impl<'a> Reader<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    fn varint(&mut self) -> Result<u64, MoshError> {
        let mut result = 0u64;
        for shift in 0..10 {
            let byte = *self.buf.get(self.pos).ok_or_else(|| MoshError::Protocol("truncated varint".into()))?;
            self.pos += 1;
            result |= ((byte & 0x7f) as u64) << (shift * 7);
            if byte & 0x80 == 0 {
                return Ok(result);
            }
        }
        Err(MoshError::Protocol("varint longer than 10 bytes".into()))
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8], MoshError> {
        let end = self.pos.checked_add(n).ok_or_else(|| MoshError::Protocol("length overflow".into()))?;
        let slice = self.buf.get(self.pos..end).ok_or_else(|| MoshError::Protocol("truncated field".into()))?;
        self.pos = end;
        Ok(slice)
    }

    /// Next (field number, value), or None at the end of the message.
    fn next_field(&mut self) -> Option<Result<(u32, Value<'a>), MoshError>> {
        if self.pos >= self.buf.len() {
            return None;
        }
        Some((|| {
            let key = self.varint()?;
            let field = (key >> 3) as u32;
            match (key & 7) as u32 {
                WIRE_VARINT => Ok((field, Value::Varint(self.varint()?))),
                WIRE_BYTES => {
                    let len = self.varint()? as usize;
                    Ok((field, Value::Bytes(self.take(len)?)))
                }
                WIRE_FIXED64 => {
                    self.take(8)?;
                    Ok((field, Value::Varint(0)))
                }
                WIRE_FIXED32 => {
                    self.take(4)?;
                    Ok((field, Value::Varint(0)))
                }
                other => Err(MoshError::Protocol(format!("unsupported wire type {other}"))),
            }
        })())
    }
}

// ---------------------------------------------------------------------------
// TransportBuffers.Instruction — the envelope every datagram carries
// ---------------------------------------------------------------------------

/// The protocol version this client speaks; the server rejects anything else.
pub const MOSH_PROTOCOL_VERSION: u32 = 2;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Instruction {
    pub protocol_version: u32,
    /// The state this diff is against.
    pub old_num: u64,
    /// The state this diff produces.
    pub new_num: u64,
    /// The highest state number the sender has received from us.
    pub ack_num: u64,
    /// States below this have been discarded by the sender.
    pub throwaway_num: u64,
    pub diff: Vec<u8>,
    pub chaff: Vec<u8>,
}

impl Default for Instruction {
    fn default() -> Self {
        Self {
            protocol_version: MOSH_PROTOCOL_VERSION,
            old_num: 0,
            new_num: 0,
            ack_num: 0,
            throwaway_num: 0,
            diff: Vec::new(),
            chaff: Vec::new(),
        }
    }
}

impl Instruction {
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(32 + self.diff.len() + self.chaff.len());
        put_varint_field(&mut out, 1, self.protocol_version as u64);
        put_varint_field(&mut out, 2, self.old_num);
        put_varint_field(&mut out, 3, self.new_num);
        put_varint_field(&mut out, 4, self.ack_num);
        put_varint_field(&mut out, 5, self.throwaway_num);
        put_bytes_field(&mut out, 6, &self.diff);
        if !self.chaff.is_empty() {
            put_bytes_field(&mut out, 7, &self.chaff);
        }
        out
    }

    pub fn decode(buf: &[u8]) -> Result<Self, MoshError> {
        let mut inst = Instruction { protocol_version: 0, ..Default::default() };
        let mut r = Reader::new(buf);
        while let Some(field) = r.next_field() {
            match field? {
                (1, Value::Varint(v)) => inst.protocol_version = v as u32,
                (2, Value::Varint(v)) => inst.old_num = v,
                (3, Value::Varint(v)) => inst.new_num = v,
                (4, Value::Varint(v)) => inst.ack_num = v,
                (5, Value::Varint(v)) => inst.throwaway_num = v,
                (6, Value::Bytes(b)) => inst.diff = b.to_vec(),
                (7, Value::Bytes(b)) => inst.chaff = b.to_vec(),
                _ => {}
            }
        }
        Ok(inst)
    }
}

// ---------------------------------------------------------------------------
// ClientBuffers.UserMessage — what we send: keystrokes and resizes
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UserEvent {
    Keystroke(Vec<u8>),
    Resize { width: i32, height: i32 },
}

/// Encode a run of user events. Consecutive keystrokes are merged into one
/// instruction, which is what the reference client does and what keeps a burst
/// of typing down to a single small message.
pub fn encode_user_message(events: &[UserEvent]) -> Vec<u8> {
    let mut out = Vec::new();
    let mut keys: Vec<u8> = Vec::new();

    fn flush(out: &mut Vec<u8>, keys: &mut Vec<u8>) {
        if keys.is_empty() {
            return;
        }
        let mut keystroke = Vec::new();
        put_bytes_field(&mut keystroke, 4, keys);
        let mut instruction = Vec::new();
        put_bytes_field(&mut instruction, 2, &keystroke);
        put_bytes_field(out, 1, &instruction);
        keys.clear();
    }

    for e in events {
        match e {
            UserEvent::Keystroke(k) => keys.extend_from_slice(k),
            UserEvent::Resize { width, height } => {
                flush(&mut out, &mut keys);
                let mut resize = Vec::new();
                put_varint_field(&mut resize, 5, *width as u64);
                put_varint_field(&mut resize, 6, *height as u64);
                let mut instruction = Vec::new();
                put_bytes_field(&mut instruction, 3, &resize);
                put_bytes_field(&mut out, 1, &instruction);
            }
        }
    }
    flush(&mut out, &mut keys);
    out
}

// ---------------------------------------------------------------------------
// HostBuffers.HostMessage — what we receive
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HostEvent {
    /// Terminal output: an escape-sequence stream that repaints the screen.
    Bytes(Vec<u8>),
    Resize { width: i32, height: i32 },
    /// The server has echoed user input up to this state number.
    EchoAck(u64),
}

pub fn decode_host_message(buf: &[u8]) -> Result<Vec<HostEvent>, MoshError> {
    let mut events = Vec::new();
    let mut r = Reader::new(buf);
    while let Some(field) = r.next_field() {
        let (num, value) = field?;
        let (1, Value::Bytes(instruction)) = (num, value) else { continue };
        let mut ir = Reader::new(instruction);
        while let Some(inner) = ir.next_field() {
            match inner? {
                (2, Value::Bytes(hostbytes)) => {
                    let mut hr = Reader::new(hostbytes);
                    while let Some(f) = hr.next_field() {
                        if let (4, Value::Bytes(s)) = f? {
                            events.push(HostEvent::Bytes(s.to_vec()));
                        }
                    }
                }
                (3, Value::Bytes(resize)) => {
                    let (mut width, mut height) = (0i32, 0i32);
                    let mut rr = Reader::new(resize);
                    while let Some(f) = rr.next_field() {
                        match f? {
                            (5, Value::Varint(v)) => width = v as i32,
                            (6, Value::Varint(v)) => height = v as i32,
                            _ => {}
                        }
                    }
                    events.push(HostEvent::Resize { width, height });
                }
                (7, Value::Bytes(echo)) => {
                    let mut er = Reader::new(echo);
                    while let Some(f) = er.next_field() {
                        if let (8, Value::Varint(v)) = f? {
                            events.push(HostEvent::EchoAck(v));
                        }
                    }
                }
                _ => {}
            }
        }
    }
    Ok(events)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn varints_round_trip() {
        for v in [0u64, 1, 127, 128, 300, u32::MAX as u64, u64::MAX] {
            let mut buf = Vec::new();
            put_varint(&mut buf, v);
            assert_eq!(Reader::new(&buf).varint().unwrap(), v, "value {v}");
        }
    }

    #[test]
    fn instruction_round_trips() {
        let inst = Instruction {
            protocol_version: MOSH_PROTOCOL_VERSION,
            old_num: 3,
            new_num: 4,
            ack_num: 9,
            throwaway_num: 2,
            diff: b"the diff".to_vec(),
            chaff: b"xx".to_vec(),
        };
        assert_eq!(Instruction::decode(&inst.encode()).unwrap(), inst);
    }

    #[test]
    fn instruction_tolerates_fields_it_does_not_know() {
        let mut buf = Instruction::default().encode();
        put_bytes_field(&mut buf, 99, b"from a future version");
        put_varint_field(&mut buf, 100, 12345);
        let decoded = Instruction::decode(&buf).unwrap();
        assert_eq!(decoded.protocol_version, MOSH_PROTOCOL_VERSION);
    }

    #[test]
    fn truncated_input_is_an_error_not_a_panic() {
        let full = Instruction { diff: b"abcdef".to_vec(), ..Default::default() }.encode();
        for cut in 1..full.len() {
            // Must never panic; a short read is either an error or a partial parse.
            let _ = Instruction::decode(&full[..cut]);
        }
    }

    #[test]
    fn keystrokes_merge_into_one_instruction() {
        let one = encode_user_message(&[UserEvent::Keystroke(b"abc".to_vec())]);
        let split = encode_user_message(&[
            UserEvent::Keystroke(b"a".to_vec()),
            UserEvent::Keystroke(b"b".to_vec()),
            UserEvent::Keystroke(b"c".to_vec()),
        ]);
        assert_eq!(one, split, "consecutive keystrokes should coalesce");
    }

    #[test]
    fn a_resize_breaks_the_keystroke_run() {
        let msg = encode_user_message(&[
            UserEvent::Keystroke(b"a".to_vec()),
            UserEvent::Resize { width: 80, height: 24 },
            UserEvent::Keystroke(b"b".to_vec()),
        ]);
        // Three instructions, so three field-1 entries.
        let mut r = Reader::new(&msg);
        let mut count = 0;
        while let Some(f) = r.next_field() {
            if matches!(f.unwrap(), (1, Value::Bytes(_))) {
                count += 1;
            }
        }
        assert_eq!(count, 3);
    }

    /// The client and server halves are mirror images, so a host message we
    /// build the same way must decode back to the same events.
    #[test]
    fn host_messages_decode() {
        let mut msg = Vec::new();

        let mut hostbytes = Vec::new();
        put_bytes_field(&mut hostbytes, 4, b"\x1b[2Jhello");
        let mut inst = Vec::new();
        put_bytes_field(&mut inst, 2, &hostbytes);
        put_bytes_field(&mut msg, 1, &inst);

        let mut resize = Vec::new();
        put_varint_field(&mut resize, 5, 100);
        put_varint_field(&mut resize, 6, 30);
        let mut inst = Vec::new();
        put_bytes_field(&mut inst, 3, &resize);
        put_bytes_field(&mut msg, 1, &inst);

        let mut echo = Vec::new();
        put_varint_field(&mut echo, 8, 77);
        let mut inst = Vec::new();
        put_bytes_field(&mut inst, 7, &echo);
        put_bytes_field(&mut msg, 1, &inst);

        assert_eq!(
            decode_host_message(&msg).unwrap(),
            vec![
                HostEvent::Bytes(b"\x1b[2Jhello".to_vec()),
                HostEvent::Resize { width: 100, height: 30 },
                HostEvent::EchoAck(77),
            ],
        );
    }

    #[test]
    fn an_empty_host_message_yields_nothing() {
        assert!(decode_host_message(b"").unwrap().is_empty());
    }
}
