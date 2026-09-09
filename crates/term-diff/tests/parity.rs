//! The two backends must agree on everything not explicitly accounted for.
//!
//! This is the regression net for the libghostty backend: whatever it is doing
//! differently from the one that shipped shows up here, described in terms of
//! the grid rather than as a byte offset. Run
//! `cargo run -p term-diff -- --known` to see the differences that are allowed,
//! each with the reason it stands.

#[test]
fn the_backends_agree() {
    let divergences = term_diff::run_all();
    assert!(
        divergences.is_empty(),
        "the emulator backends diverged:\n{}",
        term_diff::report(&divergences)
    );
}

/// Every allowed difference must still be a difference.
///
/// A script marked known hides everything inside it, so if the backends quietly
/// converge the marking should come off rather than sit there covering nothing.
#[test]
fn the_known_differences_are_still_real() {
    for script in term_diff::scripts::all().iter().filter(|s| s.known.is_some()) {
        assert!(
            !term_diff::run(script).is_empty(),
            "{:?} is marked as a known difference but the backends now agree on it — \
             drop the marking",
            script.name
        );
    }
}
