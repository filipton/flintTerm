package dev.flint.term.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flint.term.App
import dev.flint.term.data.fontSize
import dev.flint.term.terminal.TerminalView

/**
 * The terminal as a small window floating over whatever comes next.
 *
 * A build, a `tail -f`, a deploy — the things a phone terminal is used for are
 * mostly things you watch while doing something else, and a session that is
 * only visible in this app is a session you have to keep coming back to. The
 * window is a view onto the same live session, not a screenshot of it: nothing
 * is paused, and tapping it brings the app back where it was.
 */
object Pip {
    /** The session a floating window would show: whichever terminal was last on screen. */
    var sessionId: String? = null
        private set

    /** True while the app is coming back from a floating window. */
    var leftFloating: Boolean = false

    /** Whether leaving the app floats the terminal without being asked. */
    var autoEnter: Boolean = false
        private set

    /** Where the terminal sits in the window, so the shrink starts from it. */
    private var source: Rect? = null

    /** Android TV boxes and a few phones have no picture-in-picture at all. */
    fun supported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /**
     * Follow the terminal as it is laid out.
     *
     * The source rectangle is what the system animates out of and back into, so
     * it has to be current *before* the window appears — by the time anything
     * asks for one it is too late to go and measure.
     */
    fun trackTerminal(context: Context, sessionId: String, bounds: Rect) {
        this.sessionId = sessionId
        if (bounds == source || bounds.isEmpty) return
        source = bounds
        context.asActivity()?.let { apply(it) }
    }

    /** Whether a trip to another app leaves a floating window behind. */
    fun setAutoEnter(context: Context, enabled: Boolean) {
        autoEnter = enabled
        context.asActivity()?.let { apply(it) }
    }

    /** Float the terminal now; false when this device cannot. */
    fun float(context: Context): Boolean {
        val activity = context.asActivity() ?: return false
        if (!supported(activity)) return false
        return runCatching { activity.enterPictureInPictureMode(params()) }.getOrDefault(false)
    }

    private fun apply(activity: Activity) {
        if (!supported(activity)) return
        runCatching { activity.setPictureInPictureParams(params()) }
    }

    private fun params(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        source?.let {
            builder.setAspectRatio(ratioOf(it.width(), it.height()))
            builder.setSourceRectHint(it)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            // Text, not video: stretching glyphs while the window is dragged to
            // a new size looks worse than the cross-fade the system does when
            // seamless resizing is off.
            builder.setSeamlessResizeEnabled(false)
        }
        return builder.build()
    }

    /**
     * The terminal's shape, held inside what the system will take: it refuses
     * anything narrower or flatter than 2.39:1 with an exception.
     */
    private fun ratioOf(width: Int, height: Int): Rational = when {
        width > height * WIDEST -> Rational(239, 100)
        height > width * WIDEST -> Rational(100, 239)
        else -> Rational(width, height)
    }

    private const val WIDEST = 2.39f
}

private tailrec fun Context.asActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.asActivity()
    else -> null
}

/**
 * What the floating window shows: one session's output, and nothing else.
 *
 * No bar, no tab strip, no key row — at that size every row of chrome is a row
 * of terminal — and no input either, which costs nothing: a picture-in-picture
 * window receives no keystrokes and the keyboard cannot open over it.
 *
 * It shows the session at the shape it already has, scaled down to fit. The
 * alternative is reshaping the grid to the window, and a window this small
 * would mean a dozen columns: the program on the other end redraws for a
 * postage stamp, and what was on screen when you left is gone by the time you
 * look at it. Watching a build finish is the whole point, so the same rows and
 * columns stay, smaller.
 */
@Composable
fun FloatingTerminal(sessionId: String?) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val settings by app.store.settings.collectAsStateWithLifecycle()
    val sessions by app.sessions.sessions.collectAsStateWithLifecycle()
    val session = remember(sessionId, sessions) { sessionId?.let { app.sessions.get(it) } }
    if (session == null) {
        // The session was closed while the window floated; there is nothing to
        // draw and nothing to say at this size.
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val chrome = chromeFor(session.host?.theme, settings.theme)
    val view = remember(session.id) {
        TerminalView(context).apply {
            this.session = session
            isFocusable = false
            isFocusableInTouchMode = false
            detectLinks = false
            reshapesGrid = false
            maxFps = settings.maxFps
            // The size the session is already drawn at. What reaches the eye is
            // that times the scale the fit works out, so dropping it here would
            // only be taken back by a larger scale.
            fontSizeSp = session.host.fontSize(settings)
        }
    }
    LaunchedEffect(settings.fontFamily, settings.ligatures) { view.setFont(settings.fontFamily, settings.ligatures) }
    LaunchedEffect(chrome) {
        view.backgroundColorInt = chrome.terminal.value.toLong().toInt()
        view.foregroundColorInt = chrome.on.value.toLong().toInt()
    }
    // The session outlives the window: only this view of it is let go.
    DisposableEffect(view) { onDispose { view.session = null } }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().background(chrome.terminal))
}
