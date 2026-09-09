package dev.flint.term.transfer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import dev.flint.term.App
import dev.flint.term.core.FileEntry
import dev.flint.term.core.SftpClient
import dev.flint.term.core.TransferListener
import dev.flint.term.data.Host
import dev.flint.term.session.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class TransferKind {
    DOWNLOAD,
    UPLOAD,
    /** Host to host, streamed through the phone. */
    COPY,
}

enum class TransferStatus {
    QUEUED,
    /** Held back because the connection is metered and data saving is on. */
    WAITING_FOR_WIFI,
    RUNNING,
    DONE,
    FAILED,
    CANCELLED,
}

data class Transfer(
    val id: String,
    val kind: TransferKind,
    /** Display name: file or folder name. */
    val name: String,
    val sessionLabel: String,
    /** The far end of a [TransferKind.COPY]; null for anything that only touches one host. */
    val destLabel: String? = null,
    val status: TransferStatus = TransferStatus.QUEUED,
    val done: Long = 0,
    val total: Long? = null,
    val filesDone: Int = 0,
    val filesTotal: Int = 1,
    /** Bytes per second over the last few seconds. */
    val speed: Long = 0,
    /** Seconds remaining, if the total is known and speed is non-zero. */
    val etaSeconds: Long? = null,
    val currentFile: String? = null,
    val error: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
) {
    val isActive get() = status == TransferStatus.QUEUED || status == TransferStatus.RUNNING || status == TransferStatus.WAITING_FOR_WIFI
    val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (done.toFloat() / it).coerceIn(0f, 1f) }

    /** Which machines are involved, so a copy states its direction wherever it is shown. */
    val route: String get() = destLabel?.let { "$sessionLabel → $it" } ?: sessionLabel
}

/**
 * Runs SFTP transfers independently of any screen. Each transfer opens its
 * own SFTP channel on the session so the browser stays responsive, reports
 * progress into [transfers], and keeps going while the app is in the
 * background (see [TransferService], which turns the state into notifications).
 */
class TransferManager(private val context: Context) {
    /**
     * Set by [dev.flint.term.App]. When it says we are saving data, a transfer
     * waits for an unmetered connection instead of spending mobile data on what
     * is usually a large file. Interactive work is never held up — only this.
     */
    var holdForWifi: (() -> Boolean)? = null
    var awaitUnmetered: (suspend () -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers

    private class Running(val job: Job, val cancelled: AtomicBoolean = AtomicBoolean(false)) {
        @Volatile var sftp: SftpClient? = null
        /** The receiving side of a host-to-host copy; it has to be stopped too. */
        @Volatile var destSftp: SftpClient? = null
    }

    private val running = ConcurrentHashMap<String, Running>()

    val activeCount: Int get() = _transfers.value.count { it.isActive }

    // ---- public API --------------------------------------------------------

    /** Download one remote file into a document the user picked (content://). */
    fun downloadFile(session: TerminalSession, entry: FileEntry, target: Uri): String =
        start(TransferKind.DOWNLOAD, entry.name, session, filesTotal = 1, total = entry.size.toLong()) { t, sftp, upd ->
            downloadOne(sftp, entry.path, target, t, upd, 0L, entry.size.toLong())
        }

    /** Recursively download a remote folder into a directory tree the user picked. */
    fun downloadDirectory(session: TerminalSession, entry: FileEntry, tree: Uri): String =
        start(TransferKind.DOWNLOAD, entry.name, session, filesTotal = 0, total = null) { t, sftp, upd ->
            // 1. Walk the remote tree to know what we are in for.
            val files = mutableListOf<Pair<FileEntry, String>>() // entry, relative dir
            val dirs = mutableListOf<String>()
            walk(sftp, entry.path, "", files, dirs, t)
            val totalBytes = files.sumOf { it.first.size.toLong() }
            upd { it.copy(total = totalBytes, filesTotal = files.size) }

            // 2. Mirror the directory structure under the picked tree.
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val rootDir = createDir(root, entry.name) ?: error("cannot create folder ${entry.name}")
            val dirUris = HashMap<String, Uri>()
            dirUris[""] = rootDir
            for (rel in dirs.sorted()) {
                val parent = dirUris[parentOf(rel)] ?: rootDir
                dirUris[rel] = createDir(parent, rel.substringAfterLast('/')) ?: error("cannot create folder $rel")
            }

            // 3. Files, sequentially (one channel, keeps ordering and memory predictable).
            var doneBytes = 0L
            var count = 0
            for ((file, relDir) in files) {
                if (t.cancelled.get()) throw CancelledException()
                val parent = dirUris[relDir] ?: rootDir
                val doc = DocumentsContract.createDocument(context.contentResolver, parent, mimeFor(file.name), file.name)
                    ?: error("cannot create ${file.name}")
                upd { it.copy(currentFile = file.name) }
                downloadOne(sftp, file.path, doc, t, upd, doneBytes, totalBytes)
                doneBytes += file.size.toLong()
                count++
                upd { it.copy(done = doneBytes, filesDone = count) }
            }
        }

    /** Upload a document the user picked into the remote directory. */
    /**
     * The name to show (and upload under) for a picked or shared document, or null when
     * the Uri tells us nothing — a share can hand us one we may not even read.
     */
    fun nameFor(uri: Uri): String? =
        queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /**
     * Send [source] into [remoteDir].
     *
     * [onDone] is told where the file landed and what went wrong, if anything —
     * which is what "put this file in the terminal" needs: the path is only
     * worth typing once the bytes are actually there.
     */
    fun upload(
        session: TerminalSession,
        source: Uri,
        remoteDir: String,
        onDone: ((remotePath: String, error: String?) -> Unit)? = null,
    ): String {
        // Let an unreadable Uri fail visibly as a transfer instead of crashing the app.
        val name = nameFor(source) ?: "upload-${System.currentTimeMillis()}"
        val size = querySize(source)
        val remote = join(remoteDir, name)
        var failure: String? = "cancelled"
        return start(
            TransferKind.UPLOAD, name, session, filesTotal = 1, total = size,
            onFinished = onDone?.let { done -> { done(remote, failure) } },
        ) { t, sftp, upd ->
            val pfd = context.contentResolver.openFileDescriptor(source, "r") ?: error("cannot open $name")
            val fd = pfd.detachFd()
            try {
                sftp.uploadFd(fd, remote, progressListener(t, upd, 0L, size))
                failure = null
            } catch (e: Exception) {
                // Remove the partial remote file; cancel() only flags in-flight transfers, so this still works.
                runCatching { sftp.remove(remote, false) }
                failure = e.message ?: e.javaClass.simpleName
                throw e
            }
        }
    }

    /**
     * Copy a remote file or folder from [session]'s host straight to [destHost],
     * landing under [destDir] there.
     *
     * The phone is only the wire: each file is read from one server and written
     * to the other through a pipe, so nothing is staged on storage and no more
     * than a pipe buffer is ever held. A folder is walked exactly like a
     * download is, and one file that will not copy costs only itself — the rest
     * of the folder still goes over, and the names that failed end up on the
     * transfer.
     */
    fun copyToHost(session: TerminalSession, entry: FileEntry, destHost: Host, destDir: String): String =
        start(
            TransferKind.COPY,
            entry.name,
            session,
            filesTotal = if (entry.isDir) 0 else 1,
            total = if (entry.isDir) null else entry.size.toLong(),
            destLabel = destHost.displayName,
        ) { t, sftp, upd ->
            // A headless session of its own: the destination need not be open in
            // the app, and this one goes away with the transfer.
            val (destSession, destSftp) = (context.applicationContext as App).sessions.openSftpSession(destHost)
            t.destSftp = destSftp
            try {
                if (t.cancelled.get()) throw CancelledException()
                if (entry.isDir) copyTree(sftp, destSftp, entry, destDir, t, upd)
                else copyOne(sftp, destSftp, entry.path, join(destDir, entry.name), t, upd, 0L, entry.size.toLong())
            } finally {
                runCatching { destSftp.shutdown() }
                runCatching { destSession.destroy() }
            }
        }

    /** Download a remote file into a local [File] (used by "Open in…"); [onDone] gets null on success. */
    fun downloadToFile(session: TerminalSession, remotePath: String, name: String, size: Long?, target: File, onDone: (String?) -> Unit): String {
        var failure: String? = "cancelled"
        return start(TransferKind.DOWNLOAD, name, session, filesTotal = 1, total = size, onFinished = { onDone(failure) }) { r, sftp, upd ->
            target.parentFile?.mkdirs()
            try {
                sftp.download(remotePath, target.absolutePath, progressListener(r, upd, 0L, size))
                failure = null
            } catch (e: Exception) {
                target.delete()
                failure = e.message ?: e.javaClass.simpleName
                throw e
            }
        }
    }

    /** Upload a local [File] back to [remotePath] (the editor round-trip). */
    fun uploadLocalFile(session: TerminalSession, source: File, remotePath: String): String {
        val size = source.length().takeIf { it > 0 }
        return start(TransferKind.UPLOAD, source.name, session, filesTotal = 1, total = size) { t, sftp, upd ->
            sftp.upload(source.absolutePath, remotePath, progressListener(t, upd, 0L, size))
        }
    }

    fun cancel(id: String) {
        running[id]?.let { r ->
            r.cancelled.set(true)
            runCatching { r.sftp?.cancel() }
            runCatching { r.destSftp?.cancel() }
            r.job.cancel()
        }
        update(id) { if (it.isActive) it.copy(status = TransferStatus.CANCELLED, finishedAt = System.currentTimeMillis()) else it }
    }

    fun clearFinished() {
        _transfers.update { list -> list.filter { it.isActive } }
    }

    // ---- machinery ---------------------------------------------------------

    private class CancelledException : Exception("cancelled")

    private fun start(
        kind: TransferKind,
        name: String,
        session: TerminalSession,
        filesTotal: Int,
        total: Long?,
        destLabel: String? = null,
        onFinished: (() -> Unit)? = null,
        body: suspend (Running, SftpClient, ((Transfer) -> Transfer) -> Unit) -> Unit,
    ): String {
        val id = UUID.randomUUID().toString()
        _transfers.update { it + Transfer(id, kind, name, session.label, destLabel, total = total, filesTotal = filesTotal) }
        TransferService.refresh(context)
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val r = running[id] ?: return@launch
            val upd: ((Transfer) -> Transfer) -> Unit = { f -> update(id, f) }
            var sftp: SftpClient? = null
            try {
                // Big files are exactly what data saving is for; wait for Wi-Fi.
                if (holdForWifi?.invoke() == true) {
                    upd { it.copy(status = TransferStatus.WAITING_FOR_WIFI) }
                    TransferService.refresh(context)
                    awaitUnmetered?.invoke()
                    if (r.cancelled.get()) throw CancelledException()
                }
                upd { it.copy(status = TransferStatus.RUNNING) }
                sftp = session.core.openSftp()
                r.sftp = sftp
                body(r, sftp, upd)
                upd { it.copy(status = TransferStatus.DONE, done = it.total ?: it.done, finishedAt = System.currentTimeMillis(), speed = 0, etaSeconds = null, currentFile = null) }
            } catch (e: Exception) {
                val cancelled = r.cancelled.get() || e is CancelledException || e is kotlinx.coroutines.CancellationException ||
                    (e.message?.contains("cancelled") == true)
                Log.w("TransferManager", "transfer $name ${if (cancelled) "cancelled" else "failed"}: ${e.message}")
                upd {
                    it.copy(
                        status = if (cancelled) TransferStatus.CANCELLED else TransferStatus.FAILED,
                        error = if (cancelled) null else (e.message ?: e.javaClass.simpleName),
                        finishedAt = System.currentTimeMillis(),
                        speed = 0,
                        etaSeconds = null,
                    )
                }
            } finally {
                runCatching { sftp?.shutdown() }
                running.remove(id)
                onFinished?.let { done -> kotlinx.coroutines.withContext(Dispatchers.Main) { done() } }
            }
        }
        running[id] = Running(job)
        job.start()
        return id
    }

    private suspend fun downloadOne(
        sftp: SftpClient,
        remotePath: String,
        target: Uri,
        r: Running,
        upd: ((Transfer) -> Transfer) -> Unit,
        baseDone: Long,
        total: Long?,
    ) {
        val pfd = context.contentResolver.openFileDescriptor(target, "wt") ?: error("cannot open destination")
        val fd = pfd.detachFd()
        try {
            sftp.downloadFd(remotePath, fd, progressListener(r, upd, baseDone, total))
        } catch (e: Exception) {
            // Don't leave a truncated file behind when cancelled or failed.
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
            throw e
        }
    }

    private suspend fun copyTree(
        src: SftpClient,
        dest: SftpClient,
        entry: FileEntry,
        destDir: String,
        r: Running,
        upd: ((Transfer) -> Transfer) -> Unit,
    ) {
        val files = mutableListOf<Pair<FileEntry, String>>() // entry, relative dir
        val dirs = mutableListOf<String>()
        walk(src, entry.path, "", files, dirs, r)
        val totalBytes = files.sumOf { it.first.size.toLong() }
        upd { it.copy(total = totalBytes, filesTotal = files.size) }

        // Shallowest first, so every directory has its parent by the time it is
        // made. An existing one is no reason to stop; only the files decide.
        val root = join(destDir, entry.name)
        runCatching { dest.mkdir(root) }
        for (rel in dirs.sorted()) runCatching { dest.mkdir(join(root, rel)) }

        var doneBytes = 0L
        var count = 0
        val failed = mutableListOf<String>()
        for ((file, relDir) in files) {
            if (r.cancelled.get()) throw CancelledException()
            upd { it.copy(currentFile = file.name) }
            val target = join(if (relDir.isEmpty()) root else join(root, relDir), file.name)
            // One file the far end will not take costs only itself; the folder goes on.
            runCatching { copyOne(src, dest, file.path, target, r, upd, doneBytes, totalBytes) }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (r.cancelled.get()) throw CancelledException()
                failed += "${file.name}: ${e.message ?: e.javaClass.simpleName}"
            }
            doneBytes += file.size.toLong()
            count++
            upd { it.copy(done = doneBytes, filesDone = count) }
        }
        if (failed.isNotEmpty()) error(failed.take(3).joinToString("; ") + if (failed.size > 3) " (+${failed.size - 3} more)" else "")
    }

    /**
     * One file from [src] to [dest] without touching the phone's storage.
     *
     * A pipe joins the two halves of the core's own transfer loops: the source
     * writes into one end while the destination reads the other, and the pipe's
     * buffer is the only place bytes wait. Whichever end lets go first, the
     * other sees a clean end of file — so a failure on either side means the
     * remote copy is short and has to be taken away again.
     */
    private suspend fun copyOne(
        src: SftpClient,
        dest: SftpClient,
        remotePath: String,
        target: String,
        r: Running,
        upd: ((Transfer) -> Transfer) -> Unit,
        baseDone: Long,
        total: Long?,
    ) = coroutineScope {
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0].detachFd()
        val writeFd = pipe[1].detachFd()
        // Progress is counted where the bytes come from; the destination side
        // only needs a way to notice a cancel.
        val push = async(Dispatchers.IO) { runCatching { src.downloadFd(remotePath, writeFd, progressListener(r, upd, baseDone, total)) } }
        val pull = async(Dispatchers.IO) { runCatching { dest.uploadFd(readFd, target, cancelListener(r)) } }
        // Both halves are waited on before judging: removing the target while
        // the destination was still writing it would put the file back.
        val pushed = push.await()
        val pulled = pull.await()
        val failure = pushed.exceptionOrNull() ?: pulled.exceptionOrNull()
        if (failure != null) {
            runCatching { dest.remove(target, false) }
            throw failure
        }
    }

    /** Watches only for a cancel, for the end of a copy that does not count bytes. */
    private fun cancelListener(r: Running): TransferListener = object : TransferListener {
        override fun onProgress(done: ULong, fileTotal: ULong?): Boolean = !r.cancelled.get()
    }

    /** Progress callback with speed/ETA, throttled to ~5 updates per second. */
    private fun progressListener(r: Running, upd: ((Transfer) -> Transfer) -> Unit, baseDone: Long, total: Long?): TransferListener {
        val samples = ArrayDeque<Pair<Long, Long>>() // (time ms, bytes)
        var lastPush = 0L
        return object : TransferListener {
            override fun onProgress(done: ULong, fileTotal: ULong?): Boolean {
                if (r.cancelled.get()) return false
                val now = System.currentTimeMillis()
                val absolute = baseDone + done.toLong()
                samples.addLast(now to absolute)
                while (samples.size > 2 && now - samples.first().first > 3000) samples.removeFirst()
                if (now - lastPush < 200 && fileTotal?.toLong() != done.toLong()) return true
                lastPush = now
                val (t0, b0) = samples.first()
                val dt = now - t0
                val speed = if (dt > 300) (absolute - b0) * 1000 / dt else 0L
                val grand = total ?: fileTotal?.toLong()?.let { baseDone + it }
                val eta = if (speed > 0 && grand != null && grand >= absolute) (grand - absolute) / speed else null
                upd { it.copy(done = absolute, total = grand ?: it.total, speed = speed, etaSeconds = eta) }
                return true
            }
        }
    }

    private fun walk(
        sftp: SftpClient,
        path: String,
        rel: String,
        files: MutableList<Pair<FileEntry, String>>,
        dirs: MutableList<String>,
        r: Running,
    ) {
        if (r.cancelled.get()) throw CancelledException()
        for (e in sftp.list(path)) {
            if (e.isDir && !e.isSymlink) {
                val childRel = if (rel.isEmpty()) e.name else "$rel/${e.name}"
                dirs += childRel
                walk(sftp, e.path, childRel, files, dirs, r)
            } else if (!e.isDir) {
                files += e to rel
            }
        }
    }

    private fun createDir(parent: Uri, name: String): Uri? =
        runCatching { DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name) }.getOrNull()

    private fun update(id: String, f: (Transfer) -> Transfer) {
        _transfers.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
        }
    }.getOrNull()

    companion object {
        fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"
        private fun parentOf(rel: String) = rel.substringBeforeLast('/', "")
        fun mimeFor(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
