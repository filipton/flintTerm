//! Just enough DNS to ask a resolver inside the tunnel for an A / AAAA record.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

pub const TYPE_A: u16 = 1;
pub const TYPE_AAAA: u16 = 28;

/// Build a standard recursive query for `name`.
pub fn build_query(id: u16, name: &str, qtype: u16) -> Vec<u8> {
    let mut out = Vec::with_capacity(64);
    out.extend_from_slice(&id.to_be_bytes());
    out.extend_from_slice(&0x0100u16.to_be_bytes()); // recursion desired
    out.extend_from_slice(&1u16.to_be_bytes()); // one question
    out.extend_from_slice(&[0, 0, 0, 0, 0, 0]);
    for label in name.trim_end_matches('.').split('.') {
        let bytes = label.as_bytes();
        out.push(bytes.len().min(63) as u8);
        out.extend_from_slice(&bytes[..bytes.len().min(63)]);
    }
    out.push(0);
    out.extend_from_slice(&qtype.to_be_bytes());
    out.extend_from_slice(&1u16.to_be_bytes()); // IN
    out
}

/// Extract every address answer from a response to `id`. Returns `None`
/// when the packet is not a valid response for that id.
pub fn parse_answers(packet: &[u8], id: u16) -> Option<Vec<IpAddr>> {
    if packet.len() < 12 || u16::from_be_bytes([packet[0], packet[1]]) != id {
        return None;
    }
    let flags = u16::from_be_bytes([packet[2], packet[3]]);
    if flags & 0x8000 == 0 {
        return None; // not a response
    }
    let qd = u16::from_be_bytes([packet[4], packet[5]]) as usize;
    let an = u16::from_be_bytes([packet[6], packet[7]]) as usize;
    let mut pos = 12;
    for _ in 0..qd {
        pos = skip_name(packet, pos)?;
        pos += 4;
    }
    let mut out = Vec::new();
    for _ in 0..an {
        pos = skip_name(packet, pos)?;
        if pos + 10 > packet.len() {
            return None;
        }
        let rtype = u16::from_be_bytes([packet[pos], packet[pos + 1]]);
        let rdlen = u16::from_be_bytes([packet[pos + 8], packet[pos + 9]]) as usize;
        pos += 10;
        if pos + rdlen > packet.len() {
            return None;
        }
        let rdata = &packet[pos..pos + rdlen];
        match (rtype, rdlen) {
            (TYPE_A, 4) => out.push(IpAddr::V4(Ipv4Addr::new(rdata[0], rdata[1], rdata[2], rdata[3]))),
            (TYPE_AAAA, 16) => {
                let mut b = [0u8; 16];
                b.copy_from_slice(rdata);
                out.push(IpAddr::V6(Ipv6Addr::from(b)));
            }
            _ => {}
        }
        pos += rdlen;
    }
    Some(out)
}

fn skip_name(packet: &[u8], mut pos: usize) -> Option<usize> {
    loop {
        let len = *packet.get(pos)? as usize;
        if len == 0 {
            return Some(pos + 1);
        }
        if len & 0xC0 == 0xC0 {
            return Some(pos + 2); // compression pointer ends the name
        }
        pos += 1 + len;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_a_record() {
        let q = build_query(0x1234, "host.lan", TYPE_A);
        assert_eq!(&q[..2], &[0x12, 0x34]);
        // Fake a response: copy the query, set QR, add one answer with a compression pointer.
        let mut r = q.clone();
        r[2] |= 0x80;
        r[7] = 1;
        r.extend_from_slice(&[0xC0, 0x0C]); // name -> offset 12
        r.extend_from_slice(&TYPE_A.to_be_bytes());
        r.extend_from_slice(&1u16.to_be_bytes());
        r.extend_from_slice(&60u32.to_be_bytes());
        r.extend_from_slice(&4u16.to_be_bytes());
        r.extend_from_slice(&[10, 8, 0, 7]);
        assert_eq!(parse_answers(&r, 0x1234).unwrap(), vec!["10.8.0.7".parse::<IpAddr>().unwrap()]);
        assert!(parse_answers(&r, 0x9999).is_none());
    }
}
