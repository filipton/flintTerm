package dev.flint.term.files

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import dev.flint.term.App
import dev.flint.term.R
import dev.flint.term.core.FileEntry
import dev.flint.term.core.SftpClient
import dev.flint.term.core.TransferListener
import dev.flint.term.data.Host
import dev.flint.term.session.TerminalSession
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Puts every SSH host into Android's file picker and the Files app, so any app
 * can open and save files on a server without going through this one.
 *
 * A document id is `<host id>:<absolute path>`; roots are the hosts themselves.
 * Connections are made on demand and dropped once nothing has used them for a
 * while — the same "only while it is needed" rule the tunnels follow.
 */
class SftpDocumentsProvider : DocumentsProvider() {
    private val app: App get() = context!!.applicationContext as App

    private class Connection(val session: TerminalSession, val sftp: SftpClient) {
        @Volatile var lastUsed: Long = System.currentTimeMillis()
        val alive: Boolean get() = session.isConnected
        fun close() {
            runCatching { sftp.shutdown() }
            runCatching { session.destroy() }
        }
    }

    private val connections = HashMap<String, Connection>()
    private val reaper = Handler(HandlerThread("sftp-provider").apply { start() }.looper)
    /** Set while [reap] is scheduled, so it is armed once rather than per connection. */
    private var reaping = false

    private val reap = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val more = synchronized(connections) {
                val dead = connections.filterValues { now - it.lastUsed > IDLE_MS || !it.alive }
                dead.forEach { (id, c) -> c.close(); connections.remove(id) }
                connections.isNotEmpty()
            }
            // Nothing left to reap: stop waking up twice a minute until the next
            // connection arms it again. A ContentProvider is created with the
            // process, so this used to tick for the life of the app even when
            // nothing had ever opened a file.
            if (more) reaper.postDelayed(this, REAP_EVERY_MS) else reaping = false
        }
    }

    /** Start the reaper if it is not already running. Call while holding [connections]. */
    private fun armReaper() {
        if (reaping) return
        reaping = true
        reaper.postDelayed(reap, REAP_EVERY_MS)
    }

    override fun onCreate(): Boolean = true

    override fun shutdown() {
        reaper.removeCallbacksAndMessages(null)
        synchronized(connections) {
            connections.values.forEach { it.close() }
            connections.clear()
        }
    }

    // ---- roots ---------------------------------------------------------------

    /**
     * Only the hosts that asked to be here.
     *
     * A row is a storage location as far as the Files app is concerned, so every
     * host appearing there unasked would bury the phone's own storage under a
     * list of servers. Off unless a host's "Show in Files app" is turned on.
     */
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        for (host in hosts()) {
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, host.id)
                add(Root.COLUMN_DOCUMENT_ID, docId(host.id, "/"))
                add(Root.COLUMN_TITLE, host.displayName)
                add(Root.COLUMN_SUMMARY, host.target)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(
                    Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY,
                )
            }
        }
        return cursor
    }

    /** Hosts offered to the Files app: SSH, reachable, and switched on by the user. */
    private fun hosts(): List<Host> =
        // Resolved, so a host that borrows its login from an account or a group
        // is named here the way it is named everywhere else.
        app.store.hosts.value.filter { it.showInFiles && !it.isTelnet && it.hostname.isNotBlank() }
            .map { app.store.effective(it) }

    // ---- documents -----------------------------------------------------------

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val (hostId, path) = split(documentId)
        if (path == "/" || path.isEmpty()) {
            // The root row: describing it must not need a connection, or the
            // Files app would dial every server just to draw its list.
            val host = host(hostId)
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, docId(hostId, "/"))
                add(Document.COLUMN_DISPLAY_NAME, host.displayName)
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
                add(Document.COLUMN_SIZE, null)
            }
            return cursor
        }
        val entry = withSftp(hostId) { it.stat(path) }
        cursor.addEntry(hostId, entry)
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val (hostId, path) = split(parentDocumentId)
        val dir = if (path.isEmpty() || path == "/") withSftp(hostId) { it.home() } else path
        val showHidden = app.store.settings.value.showHiddenFiles
        withSftp(hostId) { it.list(dir) }
            .filter { showHidden || !it.name.startsWith(".") }
            .sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
            .forEach { cursor.addEntry(hostId, it) }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (parentHost, parentPath) = split(parentDocumentId)
        val (childHost, childPath) = split(documentId)
        if (parentHost != childHost) return false
        if (parentPath == "/" || parentPath.isEmpty()) return true
        return childPath.startsWith(parentPath.trimEnd('/') + "/")
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val (hostId, path) = split(documentId)
        val writing = mode.contains('w')
        val cache = File(context!!.cacheDir, "docs").apply { mkdirs() }
        val local = File.createTempFile("doc", null, cache)
        try {
            if (!writing || mode.contains('a') || mode.contains('r')) {
                withSftp(hostId) { it.download(path, local.absolutePath, Silent) }
            }
        } catch (e: Exception) {
            local.delete()
            throw FileNotFoundException("cannot read $path: ${e.message}")
        }
        if (!writing) {
            // Read-only: hand over the copy and let the framework close it.
            return ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY).also { local.delete() }
        }
        // Writing: the app edits a local copy, and it goes back to the server
        // when it closes the descriptor. Anything else would need random-access
        // writes over SFTP for every seek the editor makes.
        return ParcelFileDescriptor.open(
            local,
            ParcelFileDescriptor.MODE_READ_WRITE,
            reaper,
        ) { error ->
            try {
                if (error == null) withSftp(hostId) { it.upload(local.absolutePath, path, Silent) }
                else Log.w(TAG, "not uploading $path: $error")
            } catch (e: Exception) {
                Log.w(TAG, "upload of $path failed: ${e.message}")
            } finally {
                local.delete()
            }
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val (hostId, parent) = split(parentDocumentId)
        val base = if (parent.isEmpty() || parent == "/") withSftp(hostId) { it.home() } else parent
        val path = join(base, displayName)
        withSftp(hostId) { sftp ->
            if (mimeType == Document.MIME_TYPE_DIR) sftp.mkdir(path) else sftp.writeText(path, "")
        }
        return docId(hostId, path)
    }

    override fun deleteDocument(documentId: String) {
        val (hostId, path) = split(documentId)
        withSftp(hostId) { sftp -> sftp.remove(path, sftp.stat(path).isDir) }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val (hostId, path) = split(documentId)
        val target = join(path.substringBeforeLast('/', ""), displayName)
        withSftp(hostId) { it.rename(path, target) }
        return docId(hostId, target)
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point?, signal: CancellationSignal?): android.content.res.AssetFileDescriptor? = null

    // ---- plumbing ------------------------------------------------------------

    private fun MatrixCursor.addEntry(hostId: String, e: FileEntry) {
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId(hostId, e.path))
            add(Document.COLUMN_DISPLAY_NAME, e.name)
            add(Document.COLUMN_MIME_TYPE, if (e.isDir) Document.MIME_TYPE_DIR else mimeOf(e.name))
            add(Document.COLUMN_SIZE, e.size)
            add(Document.COLUMN_LAST_MODIFIED, e.mtime?.let { it.toLong() * 1000 })
            // The owner-write bit; when the server reports no mode at all, assume
            // it is writable and let the server say no if it is not.
            val writable = e.permissions?.let { (it and 0b010_000_000u) != 0u } ?: true
            var flags = 0
            if (writable) {
                flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
                flags = flags or if (e.isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
            }
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun host(id: String): Host =
        app.store.hosts.value.firstOrNull { it.id == id }?.let { app.store.effective(it) }
            ?: throw FileNotFoundException("no such host")

    /**
     * Run something against the host's SFTP connection, opening one if needed.
     *
     * Called on binder threads, which is exactly where the framework expects a
     * provider to block; the connection itself is shared and reused, because
     * dialling a server per directory listing would be unbearable.
     */
    private fun <T> withSftp(hostId: String, body: (SftpClient) -> T): T {
        val host = host(hostId)
        val connection = synchronized(connections) {
            connections[hostId]?.takeIf { it.alive } ?: run {
                connections.remove(hostId)?.close()
                val (session, sftp) = try {
                    runBlocking { app.sessions.openSftpSession(host) }
                } catch (e: Exception) {
                    throw FileNotFoundException(e.message ?: "cannot connect to ${host.displayName}")
                }
                Connection(session, sftp).also { connections[hostId] = it; armReaper() }
            }
        }
        connection.lastUsed = System.currentTimeMillis()
        return try {
            body(connection.sftp).also { connection.lastUsed = System.currentTimeMillis() }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw FileNotFoundException(e.message ?: "SFTP failed")
        }
    }

    private object Silent : TransferListener {
        override fun onProgress(done: ULong, total: ULong?): Boolean = true
    }

    companion object {
        const val TAG = "SftpDocuments"
        /**
         * Tell the Files app the location has appeared or gone. Called when the
         * setting is flipped, since roots are cached until something says so.
         */
        fun refreshRoots(context: Context) {
            val uri = android.provider.DocumentsContract.buildRootsUri("${context.packageName}.documents")
            runCatching { context.contentResolver.notifyChange(uri, null) }
        }

        const val IDLE_MS = 90_000L
        const val REAP_EVERY_MS = 30_000L

        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_ICON, Root.COLUMN_FLAGS,
        )
        val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        fun docId(hostId: String, path: String) = "$hostId:$path"

        /** Host id and path; the path may itself contain colons, the id may not. */
        fun split(documentId: String): Pair<String, String> {
            val i = documentId.indexOf(':')
            if (i < 0) throw FileNotFoundException("bad document id")
            return documentId.substring(0, i) to documentId.substring(i + 1)
        }

        fun join(dir: String, name: String) = if (dir.isEmpty() || dir == "/") "/$name" else "${dir.trimEnd('/')}/$name"

        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
