//! Fragmentation and zlib, between the instruction and the datagram.
//!
//! A serialized instruction is zlib-compressed and then cut into fragments that
//! fit the MTU. Each fragment carries a 10-byte header: an 8-byte instruction
//! id, then a 16-bit number whose top bit marks the last fragment. All the
//! fragments of one instruction share the id, so a receiver can tell a
//! late-arriving piece of an old instruction from the current one.

use std::io::{Read, Write};

use flate2::read::ZlibDecoder;
use flate2::write::ZlibEncoder;
use flate2::Compression;

use crate::MoshError;

pub const FRAG_HEADER_LEN: usize = 10;
const FINAL_BIT: u16 = 0x8000;
const FRAGMENT_NUM_MASK: u16 = 0x7fff;

/// Uncompressed ceiling for a reassembled instruction, so a hostile or broken
/// peer cannot make us allocate without bound.
const MAX_INSTRUCTION: usize = 4 * 1024 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Fragment {
    pub id: u64,
    pub fragment_num: u16,
    pub final_fragment: bool,
    pub contents: Vec<u8>,
}

impl Fragment {
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(FRAG_HEADER_LEN + self.contents.len());
        out.extend_from_slice(&self.id.to_be_bytes());
        let combined = if self.final_fragment { FINAL_BIT } else { 0 } | (self.fragment_num & FRAGMENT_NUM_MASK);
        out.extend_from_slice(&combined.to_be_bytes());
        out.extend_from_slice(&self.contents);
        out
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, MoshError> {
        if bytes.len() < FRAG_HEADER_LEN {
            return Err(MoshError::Protocol("fragment shorter than its header".into()));
        }
        let id = u64::from_be_bytes(bytes[..8].try_into().expect("checked length"));
        let combined = u16::from_be_bytes([bytes[8], bytes[9]]);
        Ok(Self {
            id,
            fragment_num: combined & FRAGMENT_NUM_MASK,
            final_fragment: combined & FINAL_BIT != 0,
            contents: bytes[FRAG_HEADER_LEN..].to_vec(),
        })
    }
}

/// Compresses an instruction and cuts it up. Each distinct instruction gets a
/// new id; retransmitting the same one reuses it, so the receiver can recognize
/// the duplicate.
#[derive(Default)]
pub struct Fragmenter {
    next_id: u64,
    last_encoded: Option<Vec<u8>>,
}

impl Fragmenter {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn make_fragments(&mut self, instruction: &[u8], mtu: usize) -> Result<Vec<Fragment>, MoshError> {
        let payload_mtu = mtu.saturating_sub(FRAG_HEADER_LEN).max(1);
        if self.last_encoded.as_deref() != Some(instruction) {
            self.next_id += 1;
            self.last_encoded = Some(instruction.to_vec());
        }
        let compressed = compress(instruction)?;
        let mut fragments = Vec::new();
        let mut num: u16 = 0;
        let mut rest = compressed.as_slice();
        loop {
            let take = rest.len().min(payload_mtu);
            let (chunk, remainder) = rest.split_at(take);
            let is_final = remainder.is_empty();
            fragments.push(Fragment {
                id: self.next_id,
                fragment_num: num,
                final_fragment: is_final,
                contents: chunk.to_vec(),
            });
            if is_final {
                break;
            }
            num = num.checked_add(1).ok_or_else(|| MoshError::Protocol("instruction needs too many fragments".into()))?;
            if num & FINAL_BIT != 0 {
                return Err(MoshError::Protocol("instruction needs too many fragments".into()));
            }
            rest = remainder;
        }
        Ok(fragments)
    }
}

/// Collects the fragments of one instruction. Anything belonging to a different
/// id replaces what is held, since only the newest instruction matters.
#[derive(Default)]
pub struct FragmentAssembly {
    id: Option<u64>,
    fragments: Vec<Option<Vec<u8>>>,
    total: Option<usize>,
    arrived: usize,
}

impl FragmentAssembly {
    pub fn new() -> Self {
        Self::default()
    }

    /// Add a fragment; true once the instruction is complete.
    pub fn add(&mut self, fragment: Fragment) -> bool {
        if self.id != Some(fragment.id) {
            self.id = Some(fragment.id);
            self.fragments.clear();
            self.total = None;
            self.arrived = 0;
        }
        let index = fragment.fragment_num as usize;
        if self.fragments.len() <= index {
            self.fragments.resize(index + 1, None);
        }
        if fragment.final_fragment {
            self.total = Some(index + 1);
            self.fragments.resize(index + 1, None);
        }
        if self.fragments[index].is_none() {
            self.arrived += 1;
        }
        self.fragments[index] = Some(fragment.contents);
        self.is_complete()
    }

    fn is_complete(&self) -> bool {
        matches!(self.total, Some(total) if self.arrived == total)
    }

    /// The reassembled, decompressed instruction. Clears the assembly.
    pub fn take(&mut self) -> Result<Vec<u8>, MoshError> {
        if !self.is_complete() {
            return Err(MoshError::Protocol("instruction is not complete".into()));
        }
        let mut compressed = Vec::new();
        for part in self.fragments.iter() {
            compressed.extend_from_slice(part.as_deref().ok_or_else(|| MoshError::Protocol("missing fragment".into()))?);
        }
        self.fragments.clear();
        self.total = None;
        self.arrived = 0;
        self.id = None;
        decompress(&compressed)
    }
}

fn compress(input: &[u8]) -> Result<Vec<u8>, MoshError> {
    let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
    encoder.write_all(input).map_err(|e| MoshError::Protocol(format!("compress: {e}")))?;
    encoder.finish().map_err(|e| MoshError::Protocol(format!("compress: {e}")))
}

fn decompress(input: &[u8]) -> Result<Vec<u8>, MoshError> {
    let mut out = Vec::new();
    ZlibDecoder::new(input)
        .take(MAX_INSTRUCTION as u64 + 1)
        .read_to_end(&mut out)
        .map_err(|e| MoshError::Protocol(format!("decompress: {e}")))?;
    if out.len() > MAX_INSTRUCTION {
        return Err(MoshError::Protocol("instruction is implausibly large".into()));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fragment_header_round_trips() {
        let f = Fragment { id: 0x0102030405060708, fragment_num: 3, final_fragment: true, contents: b"body".to_vec() };
        let bytes = f.encode();
        assert_eq!(bytes.len(), FRAG_HEADER_LEN + 4);
        assert_eq!(&bytes[8..10], &[0x80, 0x03], "final bit is the top bit of the number");
        assert_eq!(Fragment::decode(&bytes).unwrap(), f);
    }

    #[test]
    fn a_non_final_fragment_has_a_clear_top_bit() {
        let f = Fragment { id: 1, fragment_num: 5, final_fragment: false, contents: vec![] };
        assert_eq!(&f.encode()[8..10], &[0x00, 0x05]);
        assert_eq!(Fragment::decode(&f.encode()).unwrap(), f);
    }

    #[test]
    fn a_short_fragment_is_rejected() {
        assert!(Fragment::decode(&[0u8; FRAG_HEADER_LEN - 1]).is_err());
    }

    #[test]
    fn one_small_instruction_is_one_final_fragment() {
        let mut f = Fragmenter::new();
        let frags = f.make_fragments(b"small", 1400).unwrap();
        assert_eq!(frags.len(), 1);
        assert!(frags[0].final_fragment);
        assert_eq!(frags[0].fragment_num, 0);
    }

    /// Deterministic bytes that zlib cannot shrink, so fragment counts in these
    /// tests are about the MTU rather than about how well the data compresses.
    fn incompressible(n: usize) -> Vec<u8> {
        let mut state = 0x2545_f491_4f6c_dd1du64;
        (0..n)
            .map(|_| {
                state ^= state << 13;
                state ^= state >> 7;
                state ^= state << 17;
                (state >> 24) as u8
            })
            .collect()
    }

    #[test]
    fn a_big_instruction_splits_and_reassembles() {
        let payload = incompressible(40_000);
        let mut f = Fragmenter::new();
        let frags = f.make_fragments(&payload, 500).unwrap();
        assert!(frags.len() > 1, "expected several fragments, got {}", frags.len());
        assert!(frags.iter().rev().skip(1).all(|f| !f.final_fragment));
        assert!(frags.last().unwrap().final_fragment);

        let mut asm = FragmentAssembly::new();
        let last = frags.len() - 1;
        for (i, frag) in frags.into_iter().enumerate() {
            assert_eq!(asm.add(frag), i == last, "completed at the wrong fragment");
        }
        assert_eq!(asm.take().unwrap(), payload);
    }

    #[test]
    fn fragments_may_arrive_out_of_order() {
        let payload = incompressible(9000);
        let mut f = Fragmenter::new();
        let mut frags = f.make_fragments(&payload, 400).unwrap();
        frags.reverse();

        let mut asm = FragmentAssembly::new();
        let last = frags.len() - 1;
        for (i, frag) in frags.into_iter().enumerate() {
            assert_eq!(asm.add(frag), i == last);
        }
        assert_eq!(asm.take().unwrap(), payload);
    }

    #[test]
    fn a_duplicate_fragment_does_not_complete_early() {
        let payload = incompressible(9000);
        let mut f = Fragmenter::new();
        let frags = f.make_fragments(&payload, 400).unwrap();
        assert!(frags.len() >= 3);

        let mut asm = FragmentAssembly::new();
        assert!(!asm.add(frags[0].clone()));
        assert!(!asm.add(frags[0].clone()), "a repeat must not count twice");
        assert!(!asm.add(frags.last().unwrap().clone()), "still missing the middle");
    }

    #[test]
    fn a_new_instruction_id_discards_the_old_assembly() {
        let mut f = Fragmenter::new();
        let first = f.make_fragments(b"first instruction", 20).unwrap();
        let second = f.make_fragments(b"second instruction", 20).unwrap();
        assert_ne!(first[0].id, second[0].id, "distinct instructions need distinct ids");

        let mut asm = FragmentAssembly::new();
        asm.add(first[0].clone());
        let last = second.len() - 1;
        for (i, frag) in second.iter().enumerate() {
            assert_eq!(asm.add(frag.clone()), i == last);
        }
        assert_eq!(asm.take().unwrap(), b"second instruction");
    }

    #[test]
    fn retransmitting_the_same_instruction_keeps_its_id() {
        let mut f = Fragmenter::new();
        let a = f.make_fragments(b"same", 1400).unwrap();
        let b = f.make_fragments(b"same", 1400).unwrap();
        assert_eq!(a[0].id, b[0].id);
    }

    #[test]
    fn garbage_does_not_decompress() {
        let mut asm = FragmentAssembly::new();
        asm.add(Fragment { id: 1, fragment_num: 0, final_fragment: true, contents: b"not zlib".to_vec() });
        assert!(asm.take().is_err());
    }
}
