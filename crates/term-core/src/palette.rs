//! Color palette: 16 ANSI colors, 256-color cube/grayscale, and the special
//! foreground/background/cursor colors. Colors are `0x00RRGGBB`.

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Palette {
    pub colors: [u32; 256],
    pub foreground: u32,
    pub background: u32,
    pub cursor: u32,
    pub selection_bg: u32,
}

impl Palette {
    /// Build a palette from the 16 ANSI colors plus specials; the 240 cube and
    /// grayscale entries are the xterm defaults.
    pub fn from_ansi(ansi: [u32; 16], foreground: u32, background: u32, cursor: u32, selection_bg: u32) -> Self {
        let mut colors = [0u32; 256];
        colors[..16].copy_from_slice(&ansi);
        // 6x6x6 color cube.
        let mut i = 16;
        for r in 0..6u32 {
            for g in 0..6u32 {
                for b in 0..6u32 {
                    let f = |v: u32| if v == 0 { 0 } else { 55 + v * 40 };
                    colors[i] = (f(r) << 16) | (f(g) << 8) | f(b);
                    i += 1;
                }
            }
        }
        // Grayscale ramp.
        for s in 0..24u32 {
            let v = 8 + s * 10;
            colors[i] = (v << 16) | (v << 8) | v;
            i += 1;
        }
        Self { colors, foreground, background, cursor, selection_bg }
    }

    pub fn dim(color: u32) -> u32 {
        let r = ((color >> 16) & 0xff) * 2 / 3;
        let g = ((color >> 8) & 0xff) * 2 / 3;
        let b = (color & 0xff) * 2 / 3;
        (r << 16) | (g << 8) | b
    }
}

impl Default for Palette {
    fn default() -> Self {
        // A calm dark theme (close to "One Dark").
        Self::from_ansi(
            [
                0x282c34, 0xe06c75, 0x98c379, 0xe5c07b, 0x61afef, 0xc678dd, 0x56b6c2, 0xabb2bf,
                0x5c6370, 0xe06c75, 0x98c379, 0xe5c07b, 0x61afef, 0xc678dd, 0x56b6c2, 0xffffff,
            ],
            0xabb2bf,
            0x1e2127,
            0x528bff,
            0x3e4451,
        )
    }
}
