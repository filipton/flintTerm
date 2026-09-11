package dev.flint.term.ui

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.flint.term.App
import dev.flint.term.data.imports.SchemeImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Import a scheme", from the first tap to a new card in the custom section.
 *
 * Two ways in: the system file picker, opened on every type because scheme
 * files have no agreed extension, or a paste box for text copied from a repo.
 * Either way the text goes through [SchemeImport], and what it finds is shown
 * as a preview card with an editable name before anything is saved — the file
 * rarely knows what it should be called, and a wrong guess is easier to fix
 * here than from the card's menu later.
 *
 * Kept composed while [open] is false so that a picker result arriving after
 * the sheet closed still has somewhere to land.
 */
@Composable
fun SchemeImportFlow(open: Boolean, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step>(Step.Idle) }

    fun finish() { step = Step.Idle; onClose() }
    fun read(text: String, fallbackName: String?) {
        step = SchemeImport.parse(text, fallbackName).fold(
            onSuccess = { Step.Preview(it, it.scheme.name) },
            onFailure = { Step.Failed(it.message ?: "Could not read that file.") },
        )
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) { finish(); return@rememberLauncherForActivityResult }
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
            }
            loaded.fold(
                onSuccess = { read(it, displayName(context, uri)) },
                onFailure = { step = Step.Failed("Could not open that file.") },
            )
        }
    }

    if (open && step == Step.Idle) {
        ActionSheet(
            onDismiss = onClose,
            title = stringResource(R.string.schemeimportflow_import_a_scheme),
            subtitle = stringResource(R.string.schemeimportflow_ghostty_alacritty_windows_terminal_iterm2_or_xre),
            actions = listOf(
                SheetAction(stringResource(R.string.schemeimportflow_pick_a_file), Icons.Rounded.FolderOpen) {
                    step = Step.Picking
                    pick.launch(arrayOf("*/*"))
                },
                SheetAction(stringResource(R.string.schemeimportflow_paste_text), Icons.Rounded.ContentPaste) { step = Step.Pasting("") },
            ),
        )
    }

    when (val s = step) {
        Step.Idle, Step.Picking -> Unit
        is Step.Pasting -> AlertDialog(
            onDismissRequest = ::finish,
            title = { Text(stringResource(R.string.schemeimportflow_paste_a_scheme)) },
            text = {
                Field(
                    s.text, { step = Step.Pasting(it) }, stringResource(R.string.schemeimportflow_scheme_file_contents),
                    mono = true, singleLine = false, minLines = 6, maxLines = 9,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                )
            },
            confirmButton = { TextButton(enabled = s.text.isNotBlank(), onClick = { read(s.text, null) }) { Text(stringResource(R.string.schemeimportflow_read)) } },
            dismissButton = { TextButton(onClick = ::finish) { Text(stringResource(R.string.schemeimportflow_cancel)) } },
        )
        is Step.Preview -> AlertDialog(
            onDismissRequest = ::finish,
            title = { Text(stringResource(R.string.schemeimportflow_add_scheme)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.schemeimportflow_read_as_a_scheme, s.parsed.format.label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Field(s.name, { step = s.copy(name = it) }, "Name")
                    Spacer(Modifier.height(12.dp))
                    ThemeCard(s.parsed.scheme.copy(name = s.name), s.name.ifBlank { "Unnamed" }, null, selected = false) {}
                }
            },
            confirmButton = {
                TextButton(
                    enabled = s.name.isNotBlank(),
                    onClick = {
                        val scheme = s.parsed.scheme.copy(name = s.name.trim())
                        app.store.updateSettings { it.copy(customSchemes = it.customSchemes + scheme) }
                        finish()
                    },
                ) { Text(stringResource(R.string.schemeimportflow_add)) }
            },
            dismissButton = { TextButton(onClick = ::finish) { Text(stringResource(R.string.schemeimportflow_cancel)) } },
        )
        is Step.Failed -> AlertDialog(
            onDismissRequest = ::finish,
            title = { Text(stringResource(R.string.schemeimportflow_not_a_scheme)) },
            text = { Text(s.reason) },
            confirmButton = { TextButton(onClick = ::finish) { Text("OK") } },
        )
    }
}

private sealed class Step {
    data object Idle : Step()
    data object Picking : Step()
    data class Pasting(val text: String) : Step()
    data class Preview(val parsed: SchemeImport.Parsed, val name: String) : Step()
    data class Failed(val reason: String) : Step()
}

/** The file's name without its extension, for a scheme that does not name itself. */
private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
