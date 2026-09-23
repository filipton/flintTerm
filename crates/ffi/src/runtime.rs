use std::sync::LazyLock;

use tokio::runtime::Runtime;

/// One worker. Everything on it is network I/O and small bursts of parsing, and
/// a second worker mostly meant a task woken on one thread handing work to the
/// other — two cores unparked where one would do. What blocks does so off it:
/// prompts, signing and the agent go through `spawn_blocking`, and the callers
/// of [`block_on`] are foreign threads, not workers.
pub static RUNTIME: LazyLock<Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(1)
        .thread_name("flintterm-rt")
        .enable_all()
        .build()
        .expect("tokio runtime")
});

/// Run a future to completion from a foreign (non-runtime) thread.
pub fn block_on<F: std::future::Future>(f: F) -> F::Output {
    RUNTIME.block_on(f)
}
