package dev.flint.term.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.flint.term.session.TerminalSession

/**
 * Give a session a name of its own.
 *
 * Four tabs called `hostssh` say nothing about which is the build and which is
 * the one with the database open, and the host list is the wrong place to fix
 * that — the name belongs to this session, not to the machine. It lasts as long
 * as the session does, and comes back with it after a restore.
 */
@Composable
fun RenameSessionDialog(session: TerminalSession, onDismiss: () -> Unit) {
    var name by remember(session.id) { mutableStateOf(session.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            Field(
                value = name,
                onValueChange = { name = it },
                label = "Name",
                placeholder = session.defaultLabel,
                hint = "Leave it empty to go back to ${session.defaultLabel}",
            )
        },
        confirmButton = {
            TextButton(onClick = { session.rename(name); onDismiss() }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
