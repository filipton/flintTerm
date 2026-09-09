package dev.flint.term.transfer

import android.net.Uri
import dev.flint.term.session.TerminalSession

/**
 * A file from the phone, put where the next command can name it.
 *
 * Termius calls this pasting a file into the terminal, and the name is honest
 * about what it is for: you are about to run something on that file, and the
 * two steps in between — upload it somewhere, then type out where it went —
 * are the part nobody wants to do by hand. So the file goes up and its path is
 * typed at the cursor, quoted, once the bytes are actually there.
 */
object InsertFile {
    /**
     * Send [uris] to [remoteDir] on [session]'s host, typing each path as it
     * arrives.
     *
     * The paths are typed one at a time rather than all at the end: a large
     * file should not hold up a small one that is already there, and the order
     * they land in is the order they were picked in often enough not to matter.
     */
    fun into(
        transfers: TransferManager,
        session: TerminalSession,
        uris: List<Uri>,
        remoteDir: String,
        onPath: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val dir = remoteDir.trim().ifEmpty { "/tmp" }.trimEnd('/').ifEmpty { "" }
        for (uri in uris) {
            transfers.upload(session, uri, dir) { path, error ->
                if (error == null) onPath(quote(path)) else onFailure(error)
            }
        }
    }

    /**
     * [path] as a shell would have to read it back.
     *
     * Single quotes, because a file picked on a phone is as likely as not to
     * have a space or a bracket in its name, and a path that has to be repaired
     * by hand defeats the point of typing it for you.
     */
    fun quote(path: String): String =
        if (path.all { it.isLetterOrDigit() || it in "./_-+@%:," }) path
        else "'" + path.replace("'", "'\\''") + "'"
}
