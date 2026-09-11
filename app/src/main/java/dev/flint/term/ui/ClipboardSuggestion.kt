package dev.flint.term.ui

import androidx.compose.ui.res.stringResource
import dev.flint.term.R
import android.content.ClipDescription
import android.content.ClipboardManager
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.flint.term.terminal.TerminalView
import kotlinx.coroutines.delay

/** How long an offer stays on screen before it stops being worth a row. */
private const val OFFER_MS = 30_000L

/**
 * The newest clip already pasted or waved away.
 *
 * Process-wide rather than remembered by the composable: leaving a session and
 * coming back rebuilds the screen, and an offer that came back with it would
 * make the × mean "until you look away" rather than "no".
 */
private object Settled {
    var stamp = 0L
}

/**
 * How old a clip can be and still be worth offering.
 *
 * What was copied is not remembered across a restart, so without this the app
 * would open with an offer to paste whatever happened to be on the clipboard,
 * however long ago it got there. An offer is only useful while it is still the
 * thing somebody just went and copied.
 */
private const val FRESH_MS = 10 * 60_000L

/**
 * A one-tap paste for something that was copied elsewhere.
 *
 * Copying in another app and coming back to type it out by hand is the one thing
 * a phone terminal makes tedious, and the key bar's paste cap is only obvious
 * to somebody already looking for it. This is the offer every phone keyboard
 * makes instead: a strip that appears when the clipboard has something newer
 * than the last thing pasted or waved away, and goes away again on its own.
 *
 * It reads the clipboard's description and never its contents, so nothing is
 * looked at until it is tapped — no "pasted from your clipboard" toast for a
 * paste that never happened.
 */
@Composable
fun ClipboardSuggestion(view: TerminalView, chrome: Color, onChrome: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    val owner = LocalLifecycleOwner.current
    // The clip being offered, by the stamp the framework put on it, and the
    // newest stamp already dealt with. Stamps rather than a flag, because what
    // was copied while the app was away arrives with no notification at all.
    var offered by remember { mutableStateOf(0L) }
    // A picture cannot be typed: it goes up to the host as a file, so the offer
    // says so rather than promising a paste.
    var picture by remember { mutableStateOf(false) }

    // Anything but text is a file: a screenshot, a PDF, a download. It cannot be
    // typed, so the offer says "send" and the paste takes the road files take.
    fun look() {
        val d = runCatching { clipboard.primaryClipDescription }.getOrNull()
        val stamp = d?.timestamp ?: 0L
        val text = d?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true ||
            d?.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) == true
        val something = (d?.mimeTypeCount ?: 0) > 0
        val fresh = stamp > System.currentTimeMillis() - FRESH_MS
        picture = something && !text
        offered = if (something && fresh && stamp > Settled.stamp) stamp else 0L
    }

    val host = LocalView.current
    DisposableEffect(clipboard, owner, host) {
        val onClip = ClipboardManager.OnPrimaryClipChangedListener { look() }
        // Three ways to hear about a copy, because one is never enough. The
        // listener only runs while this window has focus; a copy made in another
        // app happens while this one is stopped, which resume covers; and a
        // screenshot's own toolbar takes the focus without ever pausing the app,
        // which leaves the window regaining it as the only thing that fires.
        val onLifecycle = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) look() }
        val onFocus = ViewTreeObserver.OnWindowFocusChangeListener { has -> if (has) look() }
        clipboard.addPrimaryClipChangedListener(onClip)
        owner.lifecycle.addObserver(onLifecycle)
        host.viewTreeObserver.addOnWindowFocusChangeListener(onFocus)
        look()
        onDispose {
            clipboard.removePrimaryClipChangedListener(onClip)
            owner.lifecycle.removeObserver(onLifecycle)
            runCatching { host.viewTreeObserver.removeOnWindowFocusChangeListener(onFocus) }
        }
    }

    LaunchedEffect(offered) {
        if (offered != 0L) {
            delay(OFFER_MS)
            Settled.stamp = offered
            offered = 0L
        }
    }

    if (offered == 0L) return
    Row(
        modifier.background(chrome).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(onChrome.copy(alpha = 0.10f))
                .clickable {
                    Settled.stamp = offered
                    offered = 0L
                    view.paste()
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (picture) Icons.Rounded.Image else Icons.Rounded.ContentPaste,
                null, Modifier.size(15.dp), tint = onChrome.copy(alpha = 0.75f),
            )
            Text(
                if (picture) stringResource(R.string.clipboardsuggestion_send_the_file_you_copied) else stringResource(R.string.clipboardsuggestion_paste_what_you_copied),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = onChrome.copy(alpha = 0.85f),
            )
        }
        Dismiss(onChrome) { Settled.stamp = offered; offered = 0L }
    }
}

@Composable
private fun Dismiss(onChrome: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(onChrome.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Close, "Dismiss", Modifier.size(15.dp), tint = onChrome.copy(alpha = 0.7f))
    }
}
