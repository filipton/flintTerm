package dev.flint.term.transfer

import dev.flint.term.R
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.FileObserver
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.flint.term.core.FileEntry
import dev.flint.term.session.TerminalSession
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * "Open in…": download a remote file into the app's cache, hand it to another app
 * through a content:// URI, and upload it back whenever that app saves it. The
 * watch lives until the session ends or the app is killed.
 */
object ExternalEdit {
    /** A save shows up as a write, or as a rename/create when the editor writes atomically. */
    private const val MASK = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE

    private class Watch(val sessionId: String, val observer: FileObserver)

    private val watchers = ConcurrentHashMap<String, Watch>()

    private fun dir(context: Context) = File(context.cacheDir, "edit").apply { mkdirs() }

    /** Local copy for a remote path; the folder keeps the file name so editors show it. */
    private fun localFor(context: Context, session: TerminalSession, remote: String): File {
        val key = Integer.toHexString((session.id + remote).hashCode())
        return File(File(dir(context), key).apply { mkdirs() }, remote.substringAfterLast('/'))
    }

    /**
     * Downloads [entry], hands it to another app ([edit] picks EDIT over VIEW) and starts
     * watching for saves. Reports through [onError]; the download shows up in the transfer list.
     */
    fun open(context: Context, transfers: TransferManager, session: TerminalSession, entry: FileEntry, edit: Boolean, onError: (String) -> Unit) {
        val local = localFor(context, session, entry.path)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", local)
        val intent = Intent(if (edit) Intent.ACTION_EDIT else Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeFor(entry.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Ask before spending a download on a file nothing here can open. The chooser itself
        // always resolves, so the question goes to the real intent — and the manifest
        // <queries> block is what makes the answer visible on Android 11+.
        if (context.packageManager.queryIntentActivities(intent, 0).isEmpty()) {
            onError("No app on this device can ${if (edit) "edit" else "open"} ${entry.name}")
            return
        }
        transfers.downloadToFile(session, entry.path, entry.name, entry.size.toLong(), local) { error ->
            if (error != null) {
                onError(error)
                return@downloadToFile
            }
            val chooser = Intent.createChooser(intent, if (edit) "Edit with" else "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Watch before handing over: a fast editor can save before startActivity returns.
            watch(context, transfers, session, local, entry.path)
            try {
                context.startActivity(chooser)
            } catch (e: ActivityNotFoundException) {
                stop(local.absolutePath)
                onError("No app can ${if (edit) "edit" else "open"} ${entry.name}")
            }
        }
    }

    /** Upload the local copy back after every save the other app makes. */
    private fun watch(context: Context, transfers: TransferManager, session: TerminalSession, local: File, remote: String) {
        val key = local.absolutePath
        stop(key)
        val dir = local.parentFile ?: return
        val name = local.name
        var lastSize = local.length()
        var lastModified = local.lastModified()
        // Watch the folder rather than the file: editors routinely save by writing a temp
        // file and renaming it over the original, which replaces the inode a file watch is
        // bound to, so a file watch would go silent after the very first save.
        @Suppress("DEPRECATION")
        val observer = object : FileObserver(dir.absolutePath, MASK) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && path != name) return
                if (session.destroyed) { stop(key); return }
                val size = local.length()
                val modified = local.lastModified()
                if (size == lastSize && modified == lastModified) return
                lastSize = size
                lastModified = modified
                transfers.uploadLocalFile(session, local, remote)
                android.os.Handler(context.mainLooper).post {
                    Toast.makeText(context, context.getString(R.string.externaledit_saving_back_to, name, session.label), Toast.LENGTH_SHORT).show()
                }
            }
        }
        observer.startWatching()
        watchers[key] = Watch(session.id, observer)
    }

    private fun stop(key: String) {
        watchers.remove(key)?.observer?.stopWatching()
    }

    /** Drop the watches for a session that is going away. */
    fun stopFor(session: TerminalSession) {
        watchers.entries.filter { it.value.sessionId == session.id }.forEach { stop(it.key) }
    }

    /**
     * Best-effort content type. Files without a usable extension are almost always
     * config or script files on a server, so they open as text rather than as a
     * binary blob no editor will accept.
     */
    fun mimeFor(name: String): String {
        // ".bashrc" is an extension-less dotfile, not a file with the extension "bashrc".
        val ext = name.substringBeforeLast('.', "").let { if (it.isEmpty()) "" else name.substringAfterLast('.') }.lowercase()
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return if (ext.isEmpty() || ext in TEXT_EXTENSIONS) "text/plain" else "application/octet-stream"
    }

    private val TEXT_EXTENSIONS = setOf(
        "conf", "cfg", "log", "sh", "bash", "zsh", "yml", "yaml", "toml", "ini", "md", "env",
        "service", "rules", "list", "properties", "lock", "tf", "tfvars", "nix", "j2", "tmpl",
    )
}
