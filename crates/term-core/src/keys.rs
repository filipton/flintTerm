//! xterm-compatible key and mouse encoding.
//!
//! This lives outside the emulator backend on purpose: the soft keyboard and
//! the extra-keys bar produce [`KeyEvent`]s, and only the mode flags come from
//! the backend.

use crate::TermModes;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Key {
    Char(char),
    Enter,
    Tab,
    Backspace,
    Escape,
    Up,
    Down,
    Left,
    Right,
    Home,
    End,
    PageUp,
    PageDown,
    Insert,
    Delete,
    F(u8),
    /// A modifier pressed on its own. Nothing but the kitty protocol's
    /// "report all keys" mode has a way to say it, so it is silent otherwise.
    Modifier(ModifierKey),
}

/// The modifier keys the kitty protocol gives numbers of their own.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ModifierKey {
    LeftShift,
    RightShift,
    LeftControl,
    RightControl,
    LeftAlt,
    RightAlt,
    LeftSuper,
    RightSuper,
}

impl ModifierKey {
    /// The key's number in the protocol's private-use block.
    fn code(self) -> u32 {
        match self {
            ModifierKey::LeftShift => 57441,
            ModifierKey::LeftControl => 57442,
            ModifierKey::LeftAlt => 57443,
            ModifierKey::LeftSuper => 57444,
            ModifierKey::RightShift => 57447,
            ModifierKey::RightControl => 57448,
            ModifierKey::RightAlt => 57449,
            ModifierKey::RightSuper => 57450,
        }
    }
}

/// Press, auto-repeat, or release.
///
/// Only a program that asked for event types sees the last two: a legacy
/// terminal has no way to spell them, so a repeat is just another press and a
/// release is nothing at all.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum KeyKind {
    #[default]
    Press,
    Repeat,
    Release,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Modifiers {
    pub shift: bool,
    pub alt: bool,
    pub ctrl: bool,
}

impl Modifiers {
    pub const NONE: Modifiers = Modifiers { shift: false, alt: false, ctrl: false };

    pub fn any(&self) -> bool {
        self.shift || self.alt || self.ctrl
    }

    /// xterm modifier parameter: 1 + shift(1) + alt(2) + ctrl(4).
    fn param(&self) -> u8 {
        1 + (self.shift as u8) + ((self.alt as u8) << 1) + ((self.ctrl as u8) << 2)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KeyEvent {
    pub key: Key,
    pub mods: Modifiers,
    pub kind: KeyKind,
}

impl KeyEvent {
    /// A plain press, which is all most callers ever build.
    pub fn press(key: Key, mods: Modifiers) -> KeyEvent {
        KeyEvent { key, mods, kind: KeyKind::Press }
    }
}

/// Kitty progressive-enhancement flags, as the program set them.
const DISAMBIGUATE: u8 = 1;
const EVENT_TYPES: u8 = 2;
const ALTERNATE_KEYS: u8 = 4;
const ALL_KEYS: u8 = 8;
const ASSOCIATED_TEXT: u8 = 16;

/// Encode a key event. Returns an empty vec when there is nothing to send.
pub fn encode_key(ev: KeyEvent, modes: &TermModes) -> Vec<u8> {
    // A release is invisible to everything but the kitty event-types flag, and
    // so is the difference between a repeat and a press.
    if ev.kind == KeyKind::Release && modes.kitty_flags & EVENT_TYPES == 0 {
        return Vec::new();
    }
    if modes.kitty_flags != 0 {
        if let Some(bytes) = encode_kitty(ev, modes.kitty_flags) {
            return bytes;
        }
    } else if modes.modify_other_keys >= 2 {
        if let Some(bytes) = encode_modify_other(ev) {
            return bytes;
        }
    }
    // The key fell through to its legacy form, which cannot say "released".
    if ev.kind == KeyKind::Release {
        return Vec::new();
    }
    encode_legacy(ev, modes)
}

/// The kitty form of the event, or `None` when the key keeps its legacy bytes.
fn encode_kitty(ev: KeyEvent, flags: u8) -> Option<Vec<u8>> {
    let mut mods = ev.mods;
    match ev.key {
        Key::Char(c) => {
            let (number, shifted) = char_codes(c, &mut mods);
            let all_keys = flags & ALL_KEYS != 0;
            // Disambiguation only claims the combinations legacy encoding
            // flattens; a plain letter stays a letter so `reset` still works
            // in a shell after a program left the mode on.
            if !all_keys && (flags & DISAMBIGUATE == 0 || !(mods.ctrl || mods.alt)) {
                return None;
            }
            // Ctrl and Alt turn the key into a control code, so there is no
            // text to report alongside it.
            let text = if all_keys && !mods.ctrl && !mods.alt { Some(c) } else { None };
            Some(kitty_seq(number, shifted, mods, ev.kind, flags, text, 'u'))
        }
        Key::Escape => (flags & DISAMBIGUATE != 0)
            .then(|| kitty_seq(27, None, mods, ev.kind, flags, None, 'u')),
        // These three keep their C0 bytes even under disambiguation, so that a
        // shell stays usable when a crashed program left the mode set.
        Key::Enter | Key::Tab | Key::Backspace => {
            if flags & ALL_KEYS == 0 {
                return None;
            }
            let number = match ev.key {
                Key::Enter => 13,
                Key::Tab => 9,
                _ => 127,
            };
            Some(kitty_seq(number, None, mods, ev.kind, flags, None, 'u'))
        }
        Key::Modifier(which) => (flags & ALL_KEYS != 0)
            .then(|| kitty_seq(which.code(), None, mods, ev.kind, flags, None, 'u')),
        // Functional keys are escape codes already and their legacy shape is
        // what the protocol asks for; only an event type needs saying here.
        _ => {
            if flags & EVENT_TYPES == 0 || ev.kind == KeyKind::Press {
                return None;
            }
            let (number, terminator) = functional(ev.key)?;
            Some(kitty_seq(number, None, mods, ev.kind, flags, None, terminator))
        }
    }
}

/// Build one `CSI` sequence in the protocol's shape:
/// `CSI number[:shifted] [; mods[:event]] [; text] terminator`.
fn kitty_seq(
    number: u32,
    shifted: Option<u32>,
    mods: Modifiers,
    kind: KeyKind,
    flags: u8,
    text: Option<char>,
    terminator: char,
) -> Vec<u8> {
    let event = match kind {
        KeyKind::Press => 1,
        KeyKind::Repeat => 2,
        KeyKind::Release => 3,
    };
    // Press is the default event type, so saying it would be noise.
    let event = (flags & EVENT_TYPES != 0 && event != 1).then_some(event);
    let text = text.filter(|_| flags & ASSOCIATED_TEXT != 0);
    let param = mods.param();
    let needs_mods = param != 1 || event.is_some() || text.is_some();

    let mut s = String::with_capacity(16);
    s.push_str("\x1b[");
    // An arrow with nothing to say is `CSI A`, not `CSI 1 A`.
    if terminator == 'u' || needs_mods {
        s.push_str(&number.to_string());
        if flags & ALTERNATE_KEYS != 0 {
            if let Some(sh) = shifted {
                s.push(':');
                s.push_str(&sh.to_string());
            }
        }
    }
    if needs_mods {
        s.push(';');
        s.push_str(&param.to_string());
        if let Some(e) = event {
            s.push(':');
            s.push_str(&e.to_string());
        }
    }
    if let Some(t) = text {
        s.push(';');
        s.push_str(&(t as u32).to_string());
    }
    s.push(terminator);
    s.into_bytes()
}

/// xterm's `modifyOtherKeys` level 2: everything legacy encoding would flatten
/// comes back as `CSI 27 ; mods ; codepoint ~`.
fn encode_modify_other(ev: KeyEvent) -> Option<Vec<u8>> {
    let mut mods = ev.mods;
    let cp = match ev.key {
        Key::Char(c) => {
            let (number, _) = char_codes(c, &mut mods);
            // Shift on its own already produced the character it stands for.
            if !(mods.ctrl || mods.alt) {
                return None;
            }
            number
        }
        Key::Enter => 13,
        Key::Tab => 9,
        Key::Backspace => 127,
        Key::Escape => 27,
        _ => return None,
    };
    if !mods.any() {
        return None;
    }
    Some(format!("\x1b[27;{};{}~", mods.param(), cp).into_bytes())
}

/// The key's number and the base-layout pair the protocol wants.
///
/// Android hands over the character the layout produced rather than the key
/// that was struck, so an uppercase letter is the one case where the unshifted
/// key and its shifted alternate can both be recovered — and the one case
/// where the app knows shift was down without being told.
fn char_codes(c: char, mods: &mut Modifiers) -> (u32, Option<u32>) {
    if c.is_ascii_uppercase() {
        mods.shift = true;
        (c.to_ascii_lowercase() as u32, Some(c as u32))
    } else {
        (c as u32, None)
    }
}

/// Number and terminator of a functional key in its legacy CSI shape.
fn functional(key: Key) -> Option<(u32, char)> {
    Some(match key {
        Key::Up => (1, 'A'),
        Key::Down => (1, 'B'),
        Key::Right => (1, 'C'),
        Key::Left => (1, 'D'),
        Key::Home => (1, 'H'),
        Key::End => (1, 'F'),
        Key::Insert => (2, '~'),
        Key::Delete => (3, '~'),
        Key::PageUp => (5, '~'),
        Key::PageDown => (6, '~'),
        Key::F(n) => {
            let n = n.clamp(1, 12);
            if n <= 4 {
                (1, "PQRS".as_bytes()[(n - 1) as usize] as char)
            } else {
                ([15, 17, 18, 19, 20, 21, 23, 24][(n - 5) as usize], '~')
            }
        }
        _ => return None,
    })
}

fn encode_legacy(ev: KeyEvent, modes: &TermModes) -> Vec<u8> {
    let mut out = Vec::with_capacity(8);
    let m = ev.mods;

    match ev.key {
        Key::Char(c) => {
            if m.alt {
                out.push(0x1b);
            }
            if m.ctrl {
                if let Some(b) = ctrl_byte(c) {
                    out.push(b);
                    return out;
                }
            }
            let mut buf = [0u8; 4];
            out.extend_from_slice(c.encode_utf8(&mut buf).as_bytes());
        }
        Key::Enter => {
            if m.alt {
                out.push(0x1b);
            }
            out.push(b'\r');
        }
        Key::Tab => {
            if m.shift {
                out.extend_from_slice(b"\x1b[Z");
            } else {
                if m.alt {
                    out.push(0x1b);
                }
                out.push(b'\t');
            }
        }
        Key::Backspace => {
            if m.alt {
                out.push(0x1b);
            }
            out.push(if m.ctrl { 0x08 } else { 0x7f });
        }
        Key::Escape => {
            if m.alt {
                out.push(0x1b);
            }
            out.push(0x1b);
        }
        Key::Up | Key::Down | Key::Right | Key::Left | Key::Home | Key::End => {
            let final_byte = match ev.key {
                Key::Up => b'A',
                Key::Down => b'B',
                Key::Right => b'C',
                Key::Left => b'D',
                Key::Home => b'H',
                _ => b'F',
            };
            if m.any() {
                out.extend_from_slice(format!("\x1b[1;{}{}", m.param(), final_byte as char).as_bytes());
            } else if modes.app_cursor {
                out.extend_from_slice(&[0x1b, b'O', final_byte]);
            } else {
                out.extend_from_slice(&[0x1b, b'[', final_byte]);
            }
        }
        Key::PageUp | Key::PageDown | Key::Insert | Key::Delete => {
            let n = match ev.key {
                Key::Insert => 2,
                Key::Delete => 3,
                Key::PageUp => 5,
                _ => 6,
            };
            if m.any() {
                out.extend_from_slice(format!("\x1b[{};{}~", n, m.param()).as_bytes());
            } else {
                out.extend_from_slice(format!("\x1b[{}~", n).as_bytes());
            }
        }
        Key::F(n) => {
            let n = n.clamp(1, 12);
            if n <= 4 {
                let final_byte = b"PQRS"[(n - 1) as usize] as char;
                if m.any() {
                    out.extend_from_slice(format!("\x1b[1;{}{}", m.param(), final_byte).as_bytes());
                } else {
                    out.extend_from_slice(format!("\x1bO{}", final_byte).as_bytes());
                }
            } else {
                let code = [15, 17, 18, 19, 20, 21, 23, 24][(n - 5) as usize];
                if m.any() {
                    out.extend_from_slice(format!("\x1b[{};{}~", code, m.param()).as_bytes());
                } else {
                    out.extend_from_slice(format!("\x1b[{}~", code).as_bytes());
                }
            }
        }
        // A modifier on its own is not a keystroke a legacy terminal knows.
        Key::Modifier(_) => {}
    }
    out
}

fn ctrl_byte(c: char) -> Option<u8> {
    let c = c.to_ascii_lowercase();
    Some(match c {
        'a'..='z' => (c as u8) - b'a' + 1,
        ' ' | '@' | '2' => 0,
        '[' | '3' => 0x1b,
        '\\' | '4' => 0x1c,
        ']' | '5' => 0x1d,
        '^' | '6' => 0x1e,
        '_' | '7' | '-' => 0x1f,
        '?' | '8' => 0x7f,
        _ => return None,
    })
}

/// Wrap pasted text in bracketed-paste markers when the application asked for them.
pub fn encode_paste(text: &str, modes: &TermModes) -> Vec<u8> {
    // Normalize newlines to CR like a real terminal does on paste.
    let normalized = text.replace("\r\n", "\r").replace('\n', "\r");
    if modes.bracketed_paste {
        let mut v = Vec::with_capacity(normalized.len() + 12);
        v.extend_from_slice(b"\x1b[200~");
        v.extend_from_slice(normalized.as_bytes());
        v.extend_from_slice(b"\x1b[201~");
        v
    } else {
        normalized.into_bytes()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MouseButton {
    Left,
    Middle,
    Right,
    WheelUp,
    WheelDown,
    /// Motion with no button (only reported in motion mode).
    None,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MouseEvent {
    pub button: MouseButton,
    /// 0-based cell coordinates.
    pub col: u16,
    pub row: u16,
    pub pressed: bool,
    pub motion: bool,
    pub mods: Modifiers,
}

/// Encode a mouse event for the application. Returns empty if the terminal is
/// not in a mouse reporting mode that covers this event.
pub fn encode_mouse(ev: MouseEvent, modes: &TermModes) -> Vec<u8> {
    if !modes.mouse_reporting {
        return Vec::new();
    }
    if ev.motion && !modes.mouse_motion && ev.button == MouseButton::None {
        return Vec::new();
    }
    let mut code: u32 = match ev.button {
        MouseButton::Left => 0,
        MouseButton::Middle => 1,
        MouseButton::Right => 2,
        MouseButton::None => 3,
        MouseButton::WheelUp => 64,
        MouseButton::WheelDown => 65,
    };
    if ev.motion {
        code += 32;
    }
    if ev.mods.shift {
        code += 4;
    }
    if ev.mods.alt {
        code += 8;
    }
    if ev.mods.ctrl {
        code += 16;
    }
    let (x, y) = (ev.col as u32 + 1, ev.row as u32 + 1);
    if modes.sgr_mouse {
        let suffix = if ev.pressed || ev.motion { 'M' } else { 'm' };
        format!("\x1b[<{};{};{}{}", code, x, y, suffix).into_bytes()
    } else {
        if !ev.pressed && !ev.motion {
            code = 3;
        }
        let mut v = vec![0x1b, b'[', b'M'];
        let push = |v: &mut Vec<u8>, n: u32| {
            let n = n + 32;
            if modes.utf8_mouse && n >= 128 {
                let c = char::from_u32(n).unwrap_or(' ');
                let mut b = [0u8; 4];
                v.extend_from_slice(c.encode_utf8(&mut b).as_bytes());
            } else {
                v.push(n.min(255) as u8);
            }
        };
        push(&mut v, code);
        push(&mut v, x);
        push(&mut v, y);
        v
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ev(key: Key, ctrl: bool, alt: bool, shift: bool) -> KeyEvent {
        KeyEvent::press(key, Modifiers { ctrl, alt, shift })
    }

    /// Modes with the given kitty flags in force.
    fn kitty(flags: u8) -> TermModes {
        TermModes { kitty_flags: flags, ..Default::default() }
    }

    fn s(bytes: Vec<u8>) -> String {
        String::from_utf8(bytes).unwrap()
    }

    #[test]
    fn ctrl_letters() {
        let m = TermModes::default();
        assert_eq!(encode_key(ev(Key::Char('c'), true, false, false), &m), vec![3]);
        assert_eq!(encode_key(ev(Key::Char('C'), true, false, false), &m), vec![3]);
        assert_eq!(encode_key(ev(Key::Char('['), true, false, false), &m), vec![0x1b]);
        assert_eq!(encode_key(ev(Key::Char('x'), false, true, false), &m), vec![0x1b, b'x']);
    }

    #[test]
    fn arrows_respect_app_cursor() {
        let mut m = TermModes::default();
        assert_eq!(encode_key(ev(Key::Up, false, false, false), &m), b"\x1b[A");
        m.app_cursor = true;
        assert_eq!(encode_key(ev(Key::Up, false, false, false), &m), b"\x1bOA");
        assert_eq!(encode_key(ev(Key::Up, true, false, false), &m), b"\x1b[1;5A");
        assert_eq!(encode_key(ev(Key::Left, false, false, true), &m), b"\x1b[1;2D");
    }

    #[test]
    fn function_and_editing_keys() {
        let m = TermModes::default();
        assert_eq!(encode_key(ev(Key::F(1), false, false, false), &m), b"\x1bOP");
        assert_eq!(encode_key(ev(Key::F(5), false, false, false), &m), b"\x1b[15~");
        assert_eq!(encode_key(ev(Key::F(12), false, false, true), &m), b"\x1b[24;2~");
        assert_eq!(encode_key(ev(Key::Delete, false, false, false), &m), b"\x1b[3~");
        assert_eq!(encode_key(ev(Key::Tab, false, false, true), &m), b"\x1b[Z");
        assert_eq!(encode_key(ev(Key::Backspace, false, false, false), &m), vec![0x7f]);
    }

    #[test]
    fn disambiguate_separates_escape_from_ctrl_bracket() {
        // The pair this whole protocol exists for: legacy sends the same byte
        // for both, and no editor can tell them apart.
        let legacy = TermModes::default();
        assert_eq!(encode_key(ev(Key::Escape, false, false, false), &legacy), vec![0x1b]);
        assert_eq!(encode_key(ev(Key::Char('['), true, false, false), &legacy), vec![0x1b]);

        let m = kitty(1);
        assert_eq!(s(encode_key(ev(Key::Escape, false, false, false), &m)), "\x1b[27u");
        assert_eq!(s(encode_key(ev(Key::Char('['), true, false, false), &m)), "\x1b[91;5u");
    }

    #[test]
    fn disambiguate_leaves_plain_text_and_the_c0_trio_alone() {
        let m = kitty(1);
        assert_eq!(s(encode_key(ev(Key::Char('a'), false, false, false), &m)), "a");
        assert_eq!(s(encode_key(ev(Key::Char('A'), false, false, true), &m)), "A");
        assert_eq!(encode_key(ev(Key::Enter, false, false, false), &m), b"\r");
        assert_eq!(encode_key(ev(Key::Tab, false, false, false), &m), b"\t");
        assert_eq!(encode_key(ev(Key::Backspace, false, false, false), &m), vec![0x7f]);
        // Alt and ctrl are what disambiguation claims, shift alone is not.
        assert_eq!(s(encode_key(ev(Key::Char('x'), false, true, false), &m)), "\x1b[120;3u");
        assert_eq!(s(encode_key(ev(Key::Char('C'), true, false, false), &m)), "\x1b[99;6u");
    }

    #[test]
    fn event_types_are_reported_only_when_asked_for() {
        let m = kitty(1);
        let release = KeyEvent { kind: KeyKind::Release, ..ev(Key::Escape, false, false, false) };
        assert!(encode_key(release, &m).is_empty());

        let m = kitty(1 | 2);
        assert_eq!(s(encode_key(release, &m)), "\x1b[27;1:3u");
        let repeat = KeyEvent { kind: KeyKind::Repeat, ..ev(Key::Char('c'), true, false, false) };
        assert_eq!(s(encode_key(repeat, &m)), "\x1b[99;5:2u");
        // A key that stays legacy has no release to report.
        let up = KeyEvent { kind: KeyKind::Release, ..ev(Key::Char('a'), false, false, false) };
        assert!(encode_key(up, &m).is_empty());
        // Arrows keep their letter form and grow the event sub-parameter.
        let arrow = KeyEvent { kind: KeyKind::Release, ..ev(Key::Up, false, false, false) };
        assert_eq!(s(encode_key(arrow, &m)), "\x1b[1;1:3A");
        let f5 = KeyEvent { kind: KeyKind::Release, ..ev(Key::F(5), true, false, false) };
        assert_eq!(s(encode_key(f5, &m)), "\x1b[15;5:3~");
    }

    #[test]
    fn alternate_keys_report_the_shifted_codepoint() {
        let m = kitty(1 | 4);
        assert_eq!(s(encode_key(ev(Key::Char('C'), true, false, false), &m)), "\x1b[99:67;6u");
        // Without shift there is no alternate to name.
        assert_eq!(s(encode_key(ev(Key::Char('c'), true, false, false), &m)), "\x1b[99;5u");
        // The flag is inert while the key is not reported as an escape code.
        let m = kitty(4);
        assert_eq!(s(encode_key(ev(Key::Char('C'), false, false, true), &m)), "C");
    }

    #[test]
    fn all_keys_as_escape_codes() {
        let m = kitty(1 | 8);
        assert_eq!(s(encode_key(ev(Key::Char('a'), false, false, false), &m)), "\x1b[97u");
        assert_eq!(s(encode_key(ev(Key::Enter, false, false, true), &m)), "\x1b[13;2u");
        assert_eq!(s(encode_key(ev(Key::Tab, false, false, false), &m)), "\x1b[9u");
        assert_eq!(s(encode_key(ev(Key::Backspace, false, false, false), &m)), "\x1b[127u");
        assert_eq!(
            s(encode_key(ev(Key::Modifier(ModifierKey::LeftControl), true, false, false), &m)),
            "\x1b[57442;5u",
        );
        assert_eq!(
            s(encode_key(ev(Key::Modifier(ModifierKey::RightShift), false, false, true), &m)),
            "\x1b[57447;2u",
        );
        // A lone modifier has nothing to say without that flag.
        assert!(encode_key(ev(Key::Modifier(ModifierKey::LeftAlt), false, false, false), &kitty(1)).is_empty());
    }

    #[test]
    fn associated_text_rides_along() {
        let m = kitty(1 | 8 | 16);
        assert_eq!(s(encode_key(ev(Key::Char('a'), false, false, false), &m)), "\x1b[97;1;97u");
        assert_eq!(s(encode_key(ev(Key::Char('A'), false, false, true), &m)), "\x1b[97;2;65u");
        // Ctrl makes a control code, and a control code is not text.
        assert_eq!(s(encode_key(ev(Key::Char('a'), true, false, false), &m)), "\x1b[97;5u");
    }

    #[test]
    fn modify_other_keys_level_two() {
        let m = TermModes { modify_other_keys: 2, ..Default::default() };
        assert_eq!(s(encode_key(ev(Key::Char('['), true, false, false), &m)), "\x1b[27;5;91~");
        assert_eq!(s(encode_key(ev(Key::Enter, false, false, true), &m)), "\x1b[27;2;13~");
        assert_eq!(s(encode_key(ev(Key::Char('C'), true, false, false), &m)), "\x1b[27;6;99~");
        // Untouched keys keep every legacy byte they had.
        assert_eq!(s(encode_key(ev(Key::Char('a'), false, false, false), &m)), "a");
        assert_eq!(encode_key(ev(Key::Escape, false, false, false), &m), vec![0x1b]);
        assert_eq!(s(encode_key(ev(Key::Up, true, false, false), &m)), "\x1b[1;5A");
        // Level 1 is xterm's cautious half, which we leave to legacy encoding.
        let m = TermModes { modify_other_keys: 1, ..Default::default() };
        assert_eq!(encode_key(ev(Key::Char('['), true, false, false), &m), vec![0x1b]);
    }

    #[test]
    fn kitty_wins_over_modify_other_keys() {
        let m = TermModes { kitty_flags: 1, modify_other_keys: 2, ..Default::default() };
        assert_eq!(s(encode_key(ev(Key::Char('['), true, false, false), &m)), "\x1b[91;5u");
    }

    #[test]
    fn paste_is_bracketed_when_requested() {
        let mut m = TermModes::default();
        assert_eq!(encode_paste("a\nb", &m), b"a\rb");
        m.bracketed_paste = true;
        assert_eq!(encode_paste("a", &m), b"\x1b[200~a\x1b[201~");
    }

    #[test]
    fn sgr_mouse() {
        let m = TermModes { mouse_reporting: true, sgr_mouse: true, ..Default::default() };
        let e = MouseEvent { button: MouseButton::Left, col: 4, row: 2, pressed: true, motion: false, mods: Modifiers::NONE };
        assert_eq!(encode_mouse(e, &m), b"\x1b[<0;5;3M");
        let e = MouseEvent { button: MouseButton::WheelDown, col: 0, row: 0, pressed: true, motion: false, mods: Modifiers::NONE };
        assert_eq!(encode_mouse(e, &m), b"\x1b[<65;1;1M");
        assert!(encode_mouse(e, &TermModes::default()).is_empty());
    }
}
