//! Costs of the individual libghostty calls the snapshot loop can be built on.
//!
//!     cargo run -p term-ghostty --release --example ffibench
//!
//! The snapshot reads a style once per distinct style per row, and there are
//! two ways to do it: position the render state's cell cursor and read
//! (`select` + `row_cells_get`), or build a grid reference and read through
//! that (`grid_ref` + `grid_ref_style`). This says which is cheaper, and
//! whether a grid reference can be walked across a row by hand rather than
//! rebuilt per column.
//!
//! The answer is that the render-state route wins, and not narrowly: the two
//! reads cost about the same, but the grid reference has to be rebuilt for
//! every column at several times that price. Moving `x` by hand instead is not
//! an option — the last lines below show it giving the wrong style for a fifth
//! of the row.

use std::ffi::c_void;
use std::time::Instant;

use ghostty_vt_sys as sys;

const COLS: u16 = 120;
const ROWS: u16 = 40;
const N: u32 = 200_000;

fn per_call(label: &str, iterations: u32, elapsed: std::time::Duration) {
    println!("{label:<44} {:>7.1} ns", elapsed.as_secs_f64() * 1e9 / iterations as f64);
}

fn main() {
    unsafe {
        let mut term: sys::GhosttyTerminal = std::ptr::null_mut();
        assert_eq!(sys::ghostty_terminal_new(std::ptr::null(), &mut term, COLS, ROWS), 0);
        // Fill the screen with styled text so every lookup finds something.
        for i in 0..ROWS {
            let line = format!("\x1b[3{};1mrow {i} styled content here\x1b[0m\r\n", i % 7 + 1);
            sys::ghostty_terminal_vt_write(term, line.as_ptr(), line.len());
        }

        let mut render: sys::GhosttyRenderState = std::ptr::null_mut();
        assert_eq!(sys::ghostty_render_state_new(std::ptr::null(), &mut render), 0);
        assert_eq!(sys::ghostty_render_state_update(render, term), 0);

        let mut rows_iter: sys::GhosttyRenderStateRowIterator = std::ptr::null_mut();
        assert_eq!(sys::ghostty_render_state_row_iterator_new(std::ptr::null(), &mut rows_iter), 0);
        let mut cells_iter: sys::GhosttyRenderStateRowCells = std::ptr::null_mut();
        assert_eq!(sys::ghostty_render_state_row_cells_new(std::ptr::null(), &mut cells_iter), 0);

        let mut it = rows_iter;
        assert_eq!(
            sys::ghostty_render_state_get(
                render,
                sys::GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                &mut it as *mut _ as *mut c_void
            ),
            0
        );
        assert!(sys::ghostty_render_state_row_iterator_next(rows_iter));
        let mut ci = cells_iter;
        assert_eq!(
            sys::ghostty_render_state_row_get(
                rows_iter,
                sys::GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                &mut ci as *mut _ as *mut c_void
            ),
            0
        );

        // --- the render-state route -----------------------------------------
        let mut style = sys::sized::<sys::GhosttyStyle>();
        let start = Instant::now();
        for i in 0..N {
            sys::ghostty_render_state_row_cells_select(cells_iter, (i % COLS as u32) as u16);
        }
        per_call("row_cells_select", N, start.elapsed());

        let start = Instant::now();
        for _ in 0..N {
            sys::ghostty_render_state_row_cells_get(
                cells_iter,
                sys::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,
                &mut style as *mut _ as *mut c_void,
            );
        }
        per_call("row_cells_get(STYLE)", N, start.elapsed());

        // --- the grid-reference route ---------------------------------------
        let mut r = sys::sized::<sys::GhosttyGridRef>();
        let start = Instant::now();
        for i in 0..N {
            let point =
                sys::GhosttyPoint::new(sys::GHOSTTY_POINT_TAG_VIEWPORT, 0, i % ROWS as u32);
            sys::ghostty_terminal_grid_ref(term, point, &mut r);
        }
        per_call("terminal_grid_ref (once per row)", N, start.elapsed());

        let base = r;
        let start = Instant::now();
        for i in 0..N {
            let mut probe = base;
            probe.x = (i % COLS as u32) as u16;
            sys::ghostty_grid_ref_style(&probe, &mut style);
        }
        per_call("grid_ref_style (x moved by hand)", N, start.elapsed());

        // Both routes must agree with the render state, and a reference whose
        // x was moved by hand must agree too if it is to be worth the trouble.
        let same = |a: &sys::GhosttyStyle, b: &sys::GhosttyStyle| {
            a.bold == b.bold
                && a.italic == b.italic
                && a.fg_color.tag == b.fg_color.tag
                && a.fg_color.value._padding == b.fg_color.value._padding
        };
        let mut fresh_ref_differs = 0;
        let mut moved_ref_differs = 0;
        for col in 0..COLS {
            let mut want = sys::sized::<sys::GhosttyStyle>();
            sys::ghostty_render_state_row_cells_select(cells_iter, col);
            sys::ghostty_render_state_row_cells_get(
                cells_iter,
                sys::GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,
                &mut want as *mut _ as *mut c_void,
            );

            let point = sys::GhosttyPoint::new(sys::GHOSTTY_POINT_TAG_VIEWPORT, col, 0);
            let mut fresh = sys::sized::<sys::GhosttyGridRef>();
            sys::ghostty_terminal_grid_ref(term, point, &mut fresh);
            let mut got = sys::sized::<sys::GhosttyStyle>();
            sys::ghostty_grid_ref_style(&fresh, &mut got);
            if !same(&want, &got) {
                fresh_ref_differs += 1;
            }

            let mut moved = base;
            moved.x = col;
            let mut by_hand = sys::sized::<sys::GhosttyStyle>();
            sys::ghostty_grid_ref_style(&moved, &mut by_hand);
            if !same(&want, &by_hand) {
                moved_ref_differs += 1;
            }
        }
        println!();
        println!("of {COLS} columns, the style differs from the render state's in:");
        println!("  a reference built per column       {fresh_ref_differs}");
        println!("  a reference with x moved by hand   {moved_ref_differs}");

        sys::ghostty_render_state_row_cells_free(cells_iter);
        sys::ghostty_render_state_row_iterator_free(rows_iter);
        sys::ghostty_render_state_free(render);
        sys::ghostty_terminal_free(term);
    }
}
