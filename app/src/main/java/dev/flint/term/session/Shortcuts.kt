package dev.flint.term.session

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.flint.term.data.Host
import dev.flint.term.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Home-screen and launcher shortcuts that connect straight to a host. */
object Shortcuts {
    const val ACTION_CONNECT = "dev.flint.term.CONNECT"
    const val EXTRA_HOST_ID = "hostId"
    const val ACTION_OPEN_SESSION = "dev.flint.term.OPEN_SESSION"
    const val EXTRA_SESSION_ID = "sessionId"

    private val palette = intArrayOf(0xFF7AA2F7.toInt(), 0xFF5DE4C7.toInt(), 0xFFBB9AF7.toInt(), 0xFFE0AF68.toInt(), 0xFFF7768E.toInt(), 0xFF7DCFFF.toInt(), 0xFFFF9E64.toInt(), 0xFF9ECE6A.toInt(), 0xFFF2A3D4.toInt())

    fun connectIntent(context: Context, host: Host): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION_CONNECT).putExtra(EXTRA_HOST_ID, host.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun info(context: Context, host: Host): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, "host-" + host.id)
            .setShortLabel(host.displayName.take(24))
            .setLongLabel("Connect to ${host.displayName}")
            .setIcon(IconCompat.createWithAdaptiveBitmap(icon(host)))
            .setIntent(connectIntent(context, host))
            .build()

    /** Ask the launcher to pin a shortcut (shows the system confirmation). */
    fun pin(context: Context, host: Host): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        return ShortcutManagerCompat.requestPinShortcut(context, info(context, host), null)
    }

    /**
     * One at a time, off the main thread: each shortcut is a bitmap drawn and
     * handed to the launcher over binder, which is not work for a tap to wait on.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    /** What the launcher was last given, so the same list is not drawn again. */
    @Volatile private var shown: List<ShortcutFace>? = null

    /** Everything a dynamic shortcut shows; when none of it moved, neither does the launcher. */
    data class ShortcutFace(val id: String, val name: String, val color: Int)

    /** The hosts the long-press menu offers, most recent first, as they would be drawn. */
    fun recentFaces(hosts: List<Host>): List<ShortcutFace> =
        hosts.filter { it.lastConnected > 0 }.sortedByDescending { it.lastConnected }.take(4)
            .map { ShortcutFace(it.id, it.displayName, it.color) }

    /**
     * Long-press-the-app-icon shortcuts: the most recently used hosts.
     *
     * Called on every connection, and nearly every time the answer is the one
     * the launcher already has — the host at the top was connected to again.
     * Only a change in which hosts, their order or how they look redraws them.
     */
    fun updateDynamic(context: Context, hosts: List<Host>) {
        val faces = recentFaces(hosts)
        if (faces == shown) return
        shown = faces
        val byId = hosts.associateBy { it.id }
        val recent = faces.mapNotNull { byId[it.id] }
        val app = context.applicationContext
        scope.launch {
            runCatching { ShortcutManagerCompat.setDynamicShortcuts(app, recent.map { info(app, it) }) }
                .onFailure { shown = null }
        }
    }

    /** A rounded tile in the host's accent color with its initial. */
    private fun icon(host: Host): Bitmap {
        val size = 216
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val color = if (host.color != 0) host.color else palette[Math.floorMod(host.id.hashCode(), palette.size)]
        c.drawColor(color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(60, 0, 0, 0)
        }
        c.drawRoundRect(RectF(size * 0.28f, size * 0.28f, size * 0.72f, size * 0.72f), size * 0.1f, size * 0.1f, paint)
        paint.color = Color.WHITE
        paint.textSize = size * 0.34f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        c.drawText(host.displayName.take(1).uppercase(), size / 2f, y, paint)
        return bmp
    }
}
