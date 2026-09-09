use std::sync::LazyLock;

use tokio::runtime::Runtime;

pub static RUNTIME: LazyLock<Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("flintterm-rt")
        .enable_all()
        .build()
        .expect("tokio runtime")
});

/// Run a future to completion from a foreign (non-runtime) thread.
pub fn block_on<F: std::future::Future>(f: F) -> F::Output {
    RUNTIME.block_on(f)
}
