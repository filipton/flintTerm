package dev.flint.term.session

import dev.flint.term.R
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hand a file this app wrote to whatever the person picks.
 *
 * One road out for all of them — a recording, a scrollback — because the Uri
 * has to come from the provider declared in the manifest and carry the read
 * grant with it; a second provider would be a second thing to get wrong.
 */
fun shareFile(context: Context, file: File, mime: String = "text/plain", title: String? = null) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, title ?: file.name)) }
        .onFailure { Toast.makeText(context, context.getString(R.string.sharefile_nothing_can_take_that_file), Toast.LENGTH_SHORT).show() }
}
