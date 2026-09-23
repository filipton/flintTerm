package dev.flint.term.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import dev.flint.term.App
import dev.flint.term.R
import dev.flint.term.core.KeyCode
import dev.flint.term.core.KeyEventKind
import dev.flint.term.core.KeyPress
import dev.flint.term.core.PointerButton
import dev.flint.term.core.SelectKind
import dev.flint.term.core.snapshotCellBytes
import dev.flint.term.core.snapshotImagesOffset
import dev.flint.term.core.snapshotKittyFlagsOffset
import dev.flint.term.core.snapshotLinksOffset
import dev.flint.term.core.snapshotHeaderBytes
import dev.flint.term.core.snapshotInputOffset
import dev.flint.term.core.snapshotPredictionsCountOffset
import dev.flint.term.core.snapshotPredictionsOffset
import dev.flint.term.core.snapshotRowEntryBytes
import dev.flint.term.core.snapshotRowsOffset
import dev.flint.term.data.CursorStyle
import dev.flint.term.data.HighlightRule
import dev.flint.term.data.Settings
import dev.flint.term.session.TerminalSession
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders a [TerminalSession] and turns touches / keys into terminal input.
 *
 * Rendering pulls a packed snapshot from the Rust core once per frame (only
 * when the core reported damage) and paints it with plain Canvas calls:
 * background runs first, then text runs, then cursor and selection handles.
 * No per-cell object allocation happens on the draw path.
 *
 * From API 29 each row is recorded once into a [android.graphics.RenderNode]
 * kept under the hash of its content, from draw runs the core has already
 * laid out, and a frame replays the recordings; see [RowNodes].
 */
class TerminalView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    // ---- public knobs -----------------------------------------------------

    /** Core handle, or null when the session is gone; every core call goes through this. */
    private val core get() = session?.takeIf { !it.destroyed }?.core

    /** Where keys, text and pastes go: this session, and any mirror of it. */
    private val sink = KeySink()

    /**
     * Kept as fields rather than written inline at every call, so that letting
     * go of a session only ever takes back *this* view's callback.
     *
     * Two views really do share a session — the floating window and the screen
     * behind it — and both have to keep hearing about frames, so the session
     * holds a list and these are this view's entry in it.
     */
    private val onDamage: () -> Unit = { requestFrame() }

    /**
     * The most frames a second this view will draw; 0, the default, for as many
     * as the display will take. See [dev.flint.term.data.Settings.maxFps].
     */
    var maxFps: Int = 30

    /** Set while a capped frame is already waiting its turn. */
    private val frameWaiting = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastFrameAt = 0L

    /**
     * Ask for a repaint, no more often than [maxFps].
     *
     * Called from a core thread, so everything here has to be safe off the main
     * thread — which is why it is `postInvalidateDelayed` rather than a posted
     * Runnable. Dropping a frame loses nothing: the core coalesces damage until
     * somebody reads the grid, so the frame that does land is the current one,
     * not a stale one caught up with later.
     *
     * Without a cap set, the pace still depends on what the damage looks
     * like. A change on its own — the echo of a key — goes out on the next
     * vsync, because that is latency somebody feels. Changes arriving back to
     * back are output streaming, where a 120 Hz panel drew every one of them
     * and nobody can read text scrolling at that rate: those are held to
     * [STREAMING_FPS], which halves the work without a visible difference.
     */
    private fun requestFrame() {
        val now = android.os.SystemClock.uptimeMillis()
        val streaming = now - lastFrameAt < STREAMING_GAP_MS
        // A cap somebody chose is theirs, higher or lower; this only fills in
        // for the default of none.
        val cap = if (maxFps <= 0 && streaming) STREAMING_FPS else maxFps
        if (cap <= 0) {
            postInvalidateOnAnimation()
            return
        }
        // Compared a few ms early on purpose. A frame can only land on a vsync, and 1000/30 is 33ms
        // against a 33.33ms one, so measuring the interval exactly means missing it about as often
        // as not — and a miss costs a whole frame period, which is how a cap of 30 drew 20.
        val interval = 1000L / cap - VSYNC_SLACK_MS
        val since = now - lastFrameAt
        if (since >= interval) {
            lastFrameAt = now
            postInvalidateOnAnimation()
        } else if (frameWaiting.compareAndSet(false, true)) {
            postInvalidateDelayed(interval - since)
        }
    }

    var session: TerminalSession? = null
        set(value) {
            field?.removeDamageListener(onDamage)
            releaseSessionHandle()
            field = value
            sink.primary = value?.let(::SessionTarget)
            value?.addDamageListener(onDamage)
            applyHighlights()
            snapshot = null
            dropBitmaps()
            imagesSeen = -1
            predictedSeen = -1
            predicted = emptyList()
            if (reshapesGrid && value != null && cols > 0 && !value.destroyed) {
                value.core.resize(cols.toUShort(), rows.toUShort())
            }
            pushCellSize()
            postInvalidateOnAnimation()
        }

    /**
     * Whether the grid is reshaped to fit this view, or left alone and drawn
     * scaled to fit.
     *
     * Reshaping is what a full screen wants. A floating window is a few
     * hundred pixels across, and reshaping the session to that would reflow
     * the program on the other end and throw away everything that was on
     * screen — which is the one thing a glance at a floating terminal is for.
     * It shows the same rows and columns, smaller.
     */
    var reshapesGrid: Boolean = true

    /**
     * The session in the other pane, while typing is broadcast across the
     * split: everything sent from here is sent to it as well.
     */
    var mirror: TerminalSession? = null
        set(value) {
            field = value
            sink.mirror = value?.let(::SessionTarget)
        }

    /**
     * Enter was pressed, for the history that backs completions. Called before
     * the key goes out, because after it the line is whatever the shell prints
     * next; the listener reads the line itself, in the one trip it needs.
     */
    var onCommandEntered: (() -> Unit)? = null

    /**
     * A sideways fling asking for the next (`true`) or previous session.
     * Returns whether there was one to go to, so the terminal can fall back to
     * its own handling when there is not.
     */
    var onSwitchSession: ((next: Boolean) -> Boolean)? = null

    /** Called when the user pinch-zooms; persist it from here. */
    var onFontSizeChanged: ((Float) -> Unit)? = null
    var onSelectionChanged: ((Boolean) -> Unit)? = null
    /** A tap landed on a URL or an absolute path; show the chip. */
    var onLinkTap: ((Link) -> Unit)? = null
    /** Turn detection off (also disables the dotted underline). */
    var detectLinks = true
        set(value) {
            if (field == value) return
            field = value
            linksSeen = -1
        }

    data class Link(val text: String, val isUrl: Boolean)
    private class LinkSpan(val row: Int, val start: Int, val end: Int, val link: Link)
    private val links = ArrayList<LinkSpan>()
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(2f, 3f), 0f)
    }
    /** Solid underline marking a keystroke the server has not confirmed. */
    private val predictPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f }

    /**
     * The links on the visible rows, fetched only when the core says the set
     * of them changed.
     *
     * The core found them while it filled the snapshot and says in the header
     * whether the set moved, so text with no links in it streams past without
     * a single call for them. Keyword colours need nothing from here at all:
     * the core paints them into the cells it packs.
     */
    private fun scanLinks() {
        val c = core ?: return
        if (!detectLinks) {
            links.clear()
        } else if (snapLinks != linksSeen) {
            linksSeen = snapLinks
            links.clear()
            runCatching { c.visibleLinks() }.getOrNull()?.forEach {
                links += LinkSpan(it.row.toInt(), it.start.toInt(), it.end.toInt(), Link(it.text, it.isUrl))
            }
        }
    }

    private fun linkAt(col: Int, row: Int): Link? = links.firstOrNull { it.row == row && col >= it.start && col < it.end }?.link

    /** The keyword rules in force, or empty when highlighting is off. */
    private var highlightRules: List<HighlightRule> = emptyList()

    /** The placement count the placements were last fetched at, or -1; see [pullImages]. */
    private var imagesSeen = -1

    /** Keyword highlighting; an empty list turns it off and costs nothing to draw. */
    fun setHighlights(rules: List<HighlightRule>) {
        highlightRules = rules
        applyHighlights()
        invalidate()
    }

    /** Hand the rules to the core, which is what matches them. */
    private fun applyHighlights() {
        val c = core ?: return
        runCatching {
            c.setHighlightRules(
                highlightRules.map {
                    dev.flint.term.core.HighlightRule(it.id, it.pattern, it.color, it.wholeLine, it.enabled)
                },
            )
        }
    }

    private val modifierState = ModifierState()
    val modifiers: StateFlow<Modifiers> = modifierState.flow

    /**
     * The settings that decide what a key *means*, read straight from the store.
     *
     * Everything about how the terminal looks is pushed in by the screen, but
     * these are consulted in the middle of translating a keystroke, and a
     * keystroke does not wait for a recomposition to have happened first.
     */
    private val settings: Settings
        get() = (context.applicationContext as? App)?.store?.settings?.value ?: Settings()

    /** Default text color, used for predicted cells that have no attributes yet. */
    var foregroundColorInt: Int = 0xFFABB2BF.toInt()
        set(value) {
            field = value
            invalidate()
        }

    var backgroundColorInt: Int = 0xFF1E2127.toInt()
        set(value) {
            field = value
            setBackgroundColor(value)
            invalidate()
        }
    var accentColor: Int = 0xFF61AFEF.toInt()

    var fontSizeSp: Float = 13f
        set(value) {
            val clamped = value.coerceIn(MIN_SP, MAX_SP)
            if (clamped == field) return
            field = clamped
            updateMetrics()
            relayoutGrid()
            invalidate()
        }

    // ---- metrics ----------------------------------------------------------

    private val density = resources.displayMetrics.density
    private var cellW = 1f
    private var cellH = 1f
    private var baseline = 0f
    private var cols = 0
    private var rows = 0
    private var paddingPx = (4 * density)

    private var faces = TermFonts.load(context, TermFonts.DEFAULT)
    private val regular: Typeface get() = faces.regular
    private val bold: Typeface get() = faces.bold
    private val italic: Typeface get() = faces.italic
    private val boldItalic: Typeface get() = faces.boldItalic
    private var fontId = TermFonts.DEFAULT
    private var ligatures = false

    /**
     * Draw private-use codepoints with the bundled symbols font, so a prompt
     * like starship's shows its icons instead of a row of tofu.
     */
    var nerdGlyphs: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The symbols-only face, loaded the first time an icon actually turns up. */
    private val symbolFace: Typeface? by lazy {
        runCatching { ResourcesCompat.getFont(context, R.font.symbols_nerd_font_mono) }.getOrNull()
    }

    /** The shape to draw when the program has not asked for one; see [CursorShapes]. */
    var cursorStyle: CursorStyle = CursorStyle.BLOCK
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * Blink the cursor every [BLINK_MS].
     *
     * It stops while keys are arriving — a cursor that winks out from under
     * your fingers is worse than one that never blinks — and while the view is
     * off screen, so an idle session does not wake the display twice a second
     * for a rectangle nobody is looking at.
     */
    var cursorBlink: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            cursorLit = true
            scheduleBlink()
            invalidate()
        }

    /** Whether the blinking cursor is in its visible half. Always true when it does not blink. */
    private var cursorLit = true
    private var onScreen = true
    private val blinkTick = Runnable {
        cursorLit = !cursorLit
        scheduleBlink()
        invalidate()
    }

    private fun scheduleBlink() {
        removeCallbacks(blinkTick)
        if (cursorBlink && onScreen && isAttachedToWindow) postDelayed(blinkTick, BLINK_MS)
    }

    /** A keystroke relights the cursor and pushes the next blink out. */
    private fun keepCursorLit() {
        if (!cursorBlink) return
        if (!cursorLit) {
            cursorLit = true
            invalidate()
        }
        scheduleBlink()
    }

    /** Switch font family / ligatures; the grid is re-measured. */
    fun setFont(id: String, ligatures: Boolean) {
        if (id == fontId && ligatures == this.ligatures) return
        fontId = id
        this.ligatures = ligatures
        faces = TermFonts.load(context, id)
        textPaint.fontFeatureSettings = if (ligatures) "'liga' on, 'calt' on" else "'liga' off, 'calt' off"
        updateMetrics()
        relayoutGrid()
        invalidate()
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = faces.regular; fontFeatureSettings = "'liga' off, 'calt' off" }
    private val bgPaint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = max(1f, density) }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val runChars = CharArray(1024)
    private val tmpRect = RectF()

    // ---- inline images ----------------------------------------------------

    /**
     * Bitmaps by image id and generation. The core hands over the pixels once;
     * after that only positions cross the boundary, and a bitmap is freed as
     * soon as the placement using it is gone.
     */
    private val bitmaps = HashMap<Long, android.graphics.Bitmap>()
    private var placements: List<dev.flint.term.core.ImagePlacement> = emptyList()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val imageSrc = Rect()
    private val imageDst = RectF()

    /**
     * Let go of every cached bitmap. They are dropped rather than recycled: a
     * bitmap the render thread is still holding from the last frame throws if
     * it is recycled underneath it, and the garbage collector frees them
     * perfectly well once nothing points at them.
     */
    private fun dropBitmaps() {
        bitmaps.clear()
        placements = emptyList()
    }

    /** Images are measured in cells, so the core needs the size of one. */
    private fun pushCellSize() {
        if (!reshapesGrid) return
        val w = cellW.roundToInt()
        val h = cellH.roundToInt()
        if (w > 0 && h > 0) runCatching { core?.setCellSize(w.toUInt(), h.toUInt()) }
    }

    private fun bitmapFor(p: dev.flint.term.core.ImagePlacement): android.graphics.Bitmap? {
        val key = (p.id.toLong() shl 32) or p.generation.toLong()
        bitmaps[key]?.let { return it }
        val handle = frameSession()
        val w = p.width.toInt()
        val h = p.height.toInt()
        if (handle == 0L || w <= 0 || h <= 0) return null
        // The core writes the pixels straight into the bitmap's memory. Over
        // uniffi they were copied three times on the way, a whole image each.
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        if (FrameBridge.imageInto(handle, p.id.toInt(), p.generation.toInt(), bmp) != FrameBridge.IMAGE_COPIED) return null
        bitmaps[key] = bmp
        return bmp
    }

    /** Pull the placements for this frame and forget bitmaps nobody is using. */
    private fun pullImages() {
        // Zero until this session has shown an image, which most never do;
        // until then nothing is asked for at all.
        if (snapImages == 0) {
            if (placements.isNotEmpty()) dropBitmaps()
            return
        }
        // The core counts the times what it would answer changed, so a frame
        // of new text beside a picture that stayed put asks nothing.
        if (imagesSeen == snapImages) return
        imagesSeen = snapImages
        placements = runCatching { core?.images() }.getOrNull() ?: emptyList()
        if (bitmaps.isEmpty()) return
        val live = placements.mapTo(HashSet()) { (it.id.toLong() shl 32) or it.generation.toLong() }
        bitmaps.keys.retainAll(live)
    }

    private fun drawImages(canvas: Canvas, ox: Float, oy: Float, above: Boolean) {
        if (placements.isEmpty()) return
        var clipped = false
        for (p in placements) {
            if ((p.z >= 0) != above) continue
            val bmp = bitmapFor(p) ?: continue
            if (!clipped) {
                // An image that has scrolled half out of view must stop at the
                // edge of the grid rather than paint over the padding.
                canvas.save()
                canvas.clipRect(ox, oy, ox + snapCols * cellW, oy + snapRows * cellH)
                clipped = true
            }
            imageSrc.set(
                p.srcX.toInt(),
                p.srcY.toInt(),
                (p.srcX + p.srcWidth).toInt(),
                (p.srcY + p.srcHeight).toInt(),
            )
            imageDst.set(
                ox + p.col * cellW,
                oy + p.row * cellH,
                ox + (p.col + p.cols.toInt()) * cellW,
                oy + (p.row + p.rows.toInt()) * cellH,
            )
            canvas.drawBitmap(bmp, imageSrc, imageDst, imagePaint)
        }
        if (clipped) canvas.restore()
    }

    // ---- snapshot state ---------------------------------------------------

    private var snapshot: ByteBuffer? = null
    private val headerBytes = snapshotHeaderBytes().toInt()
    private val cellBytes = snapshotCellBytes().toInt()
    private val linksOffset = snapshotLinksOffset().toInt()
    private val imagesOffset = snapshotImagesOffset().toInt()
    private val predictionsOffset = snapshotPredictionsOffset().toInt()
    private val predictionsCountOffset = snapshotPredictionsCountOffset().toInt()
    private val kittyFlagsOffset = snapshotKittyFlagsOffset().toInt()
    private val inputOffset = snapshotInputOffset().toInt()
    private val rowsOffset = snapshotRowsOffset().toInt()
    private val rowEntryBytes = snapshotRowEntryBytes().toInt()
    private var snapCols = 0
    private var snapRows = 0
    private var cursorCol = -1
    private var cursorRow = -1
    private var cursorShape = 3

    /** Whether the program is on the alternate screen, as of the last frame. */
    var altScreen = false
        private set
    private var mouseReporting = false
    private var displayOffset = 0
    private var historySize = 0
    private var selStartCol = Int.MIN_VALUE
    private var selStartRow = 0
    private var selEndCol = 0
    private var selEndRow = 0
    private val hasSelection get() = selStartCol != Int.MIN_VALUE

    // ---- gesture state ----------------------------------------------------

    private enum class Drag { NONE, SELECT, HANDLE_START, HANDLE_END }
    private var drag = Drag.NONE
    private var scrollRemainder = 0f
    private var scaling = false
    private var multiTouch = false
    private var pendingFontSp = 0f
    private var lastResizeTime = 0L
    private var actionMode: ActionMode? = null
    private val scroller = OverScroller(context)
    private var lastFlingY = 0
    private val handleRadius = 8 * density
    private val handleTouchSlop = 28 * density

    /**
     * What a second finger turned out to mean. Decided once, on the first
     * movement that is decisive, and kept for the rest of the gesture: a pinch
     * that hesitated into arrows halfway through would be unusable.
     */
    private enum class TwoFinger { NONE, PENDING, ARROWS, PINCH }
    private var twoFinger = TwoFinger.NONE
    private var twoFingerSpan = 0f
    private var twoFingerX = 0f
    private var twoFingerY = 0f
    private val twoFingerSlop = 12 * density
    private val twoFingerDrag = ArrowDrag()
    private val navDrag = ArrowDrag()
    private val arrowOut = ArrowDrag.Emit { code, times -> repeat(times) { sendKey(code) } }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Tab is the key a phone keyboard hides and a shell needs most.
            //
            // Sent even while a program is reading the mouse, which is where it
            // is needed most: an editor or an agent CLI that turned mouse
            // tracking on is exactly the place a phone has no Tab for, and a
            // gesture that quietly stops working inside half the programs reads
            // as a broken one. What that program loses is the double click
            // alone — every single tap still goes to it as one.
            if (!doubleTapSendsTab) return false
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sendKey(KeyCode.Tab)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val s = session ?: return true
            if (hasSelection) {
                core?.selectClear()
                finishActionMode()
                return true
            }
            val c = colAt(e.x)
            val r = rowAt(e.y)
            if (mouseReporting) {
                core?.pointer(c.toUShort(), r.toUShort(), PointerButton.LEFT, true, false)
                core?.pointer(c.toUShort(), r.toUShort(), PointerButton.LEFT, false, false)
            } else {
                linkAt(c, r)?.let { link ->
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onLinkTap?.invoke(link)
                    return true
                }
            }
            showKeyboard()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val s = session ?: return
            if (scaling || multiTouch) return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val c = colAt(e.x)
            val r = rowAt(e.y)
            core?.selectStart(c.toUShort(), r.toUShort(), SelectKind.WORD)
            drag = Drag.SELECT
            onSelectionChanged?.invoke(true)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            // The finger left over when a two-finger gesture is let go of one
            // at a time is not a scroll; it is the tail of what just happened.
            if (scaling || drag != Drag.NONE || twoFinger != TwoFinger.NONE) return true
            scrollBy(-distanceY, e2)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (scaling || drag != Drag.NONE || altScreen || mouseReporting || twoFinger != TwoFinger.NONE) return false
            // A decisive sideways fling means "next session". The bar is set high
            // and it must be clearly more sideways than not, because the terminal's
            // own gesture — scrolling the scrollback — is the one that matters and
            // must never be stolen by a slightly crooked swipe.
            if (abs(velocityX) > 1200 && abs(velocityX) > abs(velocityY) * 2.5f && !hasSelection) {
                val handled = onSwitchSession?.invoke(velocityX < 0) ?: false
                if (handled) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    return true
                }
            }
            if (abs(velocityY) < 200) return false
            lastFlingY = 0
            scroller.fling(0, 0, 0, velocityY.roundToInt(), 0, 0, -1_000_000, 1_000_000)
            postInvalidateOnAnimation()
            return true
        }
    }).apply { setIsLongpressEnabled(true) }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // The two fingers were already claimed for arrow keys. Refusing
            // here is how the detector is told to keep out of this gesture.
            if (twoFinger == TwoFinger.ARROWS) return false
            scaling = true
            pendingFontSp = fontSizeSp
            drag = Drag.NONE
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (DEBUG_TOUCH) android.util.Log.d("TerminalView", "onScale factor=${detector.scaleFactor} span=${detector.currentSpan} pending=$pendingFontSp font=$fontSizeSp")
            // scaleFactor is incremental (since the previous event), so accumulate it.
            pendingFontSp = (pendingFontSp * detector.scaleFactor).coerceIn(MIN_SP, MAX_SP)
            val now = System.currentTimeMillis()
            if (abs(pendingFontSp - fontSizeSp) >= 0.5f && now - lastResizeTime > 90) {
                fontSizeSp = pendingFontSp // relayouts the grid + resizes the emulator
            }
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
            fontSizeSp = pendingFontSp
            onFontSizeChanged?.invoke(fontSizeSp)
        }
    }).apply { isQuickScaleEnabled = false }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setOnDragListener { _, event ->
            when (event.action) {
                // Saying yes to the whole drag is what makes the terminal light
                // up as a target while a file is being held over it.
                android.view.DragEvent.ACTION_DRAG_STARTED ->
                    event.clipDescription?.hasMimeType("*/*") == true || (event.clipDescription?.mimeTypeCount ?: 0) > 0
                android.view.DragEvent.ACTION_DROP -> {
                    val clip = event.clipData
                    val files = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
                    files.isNotEmpty() && onDrop?.invoke(event, files) == true
                }
                else -> true
            }
        }
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        updateMetrics()
        setBackgroundColor(backgroundColorInt)
    }

    // ---- layout -----------------------------------------------------------

    private fun updateMetrics() {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        textPaint.textSize = px
        textPaint.typeface = regular
        val fm = textPaint.fontMetrics
        cellW = textPaint.measureText("M")
        cellH = floor((fm.descent - fm.ascent) * LINE_SPACING + 0.5f)
        baseline = -fm.ascent + (cellH - (fm.descent - fm.ascent)) / 2f
        pushCellSize()
    }

    /**
     * Take the size the view has settled on, once it has settled.
     *
     * A keyboard sliding in resizes this view on every frame of its animation,
     * and answering each one with a real resize means the emulator rewraps its
     * scrollback twenty times and the program on the other end is told the
     * window changed twenty times and redraws for each — which is the flicker.
     * Only the last size in a burst was ever meant, so only that one is used.
     * In between, the grid that is already there is simply drawn into whatever
     * room there is: a strip clipped off the bottom, or an empty strip below
     * it, for as long as a finger takes to lift.
     */
    private val applyPendingGrid = Runnable { applyGrid(pendingCols, pendingRows) }
    private var pendingCols = 0
    private var pendingRows = 0

    private fun relayoutGrid() {
        if (!reshapesGrid) return
        val w = width - paddingLeft - paddingRight - 2 * paddingPx
        val h = height - paddingTop - paddingBottom - 2 * paddingPx
        if (w <= 0 || h <= 0) return
        val newCols = max(2, floor(w / cellW).toInt())
        val newRows = max(1, floor(h / cellH).toInt())
        removeCallbacks(applyPendingGrid)
        if (newCols == cols && newRows == rows) return
        pendingCols = newCols
        pendingRows = newRows
        // The first layout is the size the session opens at, and nothing is
        // animating yet: waiting there would only start the session at the
        // wrong shape and correct it a moment later.
        if (cols == 0) applyGrid(newCols, newRows) else postDelayed(applyPendingGrid, RESIZE_SETTLE_MS)
    }

    private fun applyGrid(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        cols = newCols
        rows = newRows
        GridMemory.remember(cols, rows)
        core?.resize(cols.toUShort(), rows.toUShort())
        lastResizeTime = System.currentTimeMillis()
        invalidate()
    }


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayoutGrid()
    }

    val gridCols get() = cols
    val gridRows get() = rows

    // ---- drawing ----------------------------------------------------------

    /**
     * The grid the core packs into, kept by this view rather than by the
     * session: a session can be let go from another thread, and the bytes have
     * to stay put until the frame that is reading them is finished.
     */
    private val snapBuffer = dev.flint.term.core.SnapshotBuffer()

    /**
     * How many times the line being typed has changed, as of the last frame.
     *
     * The core counts it into the header: the cursor's row reading differently,
     * or the cursor moving along it, and nothing at all while no command is
     * being typed. The suggestions upstairs read the line again when it moves,
     * where they used to wake up every 400 ms to ask whether it had.
     */
    val inputChanges: StateFlow<Int> get() = inputFlow
    private val inputFlow = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** The core's count of changes to the set of links, from the same header. */
    private var snapLinks = 0
    /** The same for the images placed on screen; zero until one has been. */
    private var snapImages = 0
    /** The same for predictive echo, and how many cells it is drawing. */
    private var snapPredictions = 0
    private var snapPredictionCount = 0
    /** The kitty keyboard flags the program has asked for, as of the last frame. */
    private var snapKittyFlags = 0

    /**
     * The predicted cells, fetched when the header says they changed; the
     * count they were fetched at, or -1 to fetch again.
     */
    private var predicted: List<dev.flint.term.core.PredictedCell> = emptyList()
    private var predictedSeen = -1
    /** The count the links in [links] were fetched at; -1 to fetch again. */
    private var linksSeen = -1

    /** Strong references for [FrameBridge]; 0 when not held. */
    private var sessionHandle = 0L
    private var bufferHandle = 0L

    private fun releaseSessionHandle() {
        if (sessionHandle != 0L) {
            FrameBridge.releaseSession(sessionHandle)
            sessionHandle = 0L
        }
        linksSeen = -1
    }

    /** The same memory seen as a buffer; remade only when it moves or grows. */
    private var snapDirect: ByteBuffer? = null
    private var snapInts: java.nio.IntBuffer? = null
    private var snapFloats: java.nio.FloatBuffer? = null
    private var snapPtr = 0L
    private var snapLen = 0L

    /**
     * This view's strong reference to the session for [FrameBridge], or 0
     * when there is no live session to hold.
     *
     * Taken once per session and per attach, through uniffi; every frame
     * after that goes through FrameBridge, which is where the time went.
     */
    private fun frameSession(): Long {
        val s = session ?: return 0L
        if (s.destroyed) {
            releaseSessionHandle()
            return 0L
        }
        if (sessionHandle == 0L) {
            sessionHandle = runCatching { s.core.frameHandle().toLong() }.getOrDefault(0L)
        }
        return sessionHandle
    }

    private fun pullSnapshot() {
        if (frameSession() == 0L) return
        if (bufferHandle == 0L) bufferHandle = snapBuffer.frameHandle().toLong()
        var flags = 0
        if (detectLinks) flags = flags or FrameBridge.WANT_LINKS
        if (highlightRules.isNotEmpty()) flags = flags or FrameBridge.WANT_HIGHLIGHTS
        syncLayout()
        val len = FrameBridge.snapshotInto(sessionHandle, bufferHandle, flags)
        if (len <= 0L) return
        // The address is asked for on every frame, not only when the length
        // changes: the row layout reserves room ahead of what it writes and
        // pads the length to a high-water mark, so the buffer can move to a new
        // allocation and come back the same length, and a view kept over the
        // old one would read freed memory.
        val ptr = FrameBridge.bufferAddress(bufferHandle)
        if (len != snapLen || ptr != snapPtr) {
            val direct = com.sun.jna.Pointer(ptr)
                .getByteBuffer(0, len)
                .order(ByteOrder.LITTLE_ENDIAN)
            snapDirect = direct
            // Views of the same memory, for copying glyph numbers and
            // positions out in bulk; they take the byte order set above.
            snapInts = direct.asIntBuffer()
            snapFloats = direct.asFloatBuffer()
            snapPtr = ptr
            snapLen = len
        }
        snapLayout = layoutSerial
        val buf = snapDirect ?: return
        snapCols = buf.getShort(0).toInt() and 0xffff
        snapRows = buf.getShort(2).toInt() and 0xffff
        cursorCol = buf.getShort(4).toInt()
        cursorRow = buf.getShort(6).toInt()
        cursorShape = CursorShapes.effective(buf.get(8).toInt(), cursorStyle)
        val mode = buf.get(9).toInt()
        altScreen = mode and 1 != 0
        mouseReporting = mode and 2 != 0
        displayOffset = buf.getInt(10)
        historySize = buf.getInt(14)
        val sc = buf.getShort(18).toInt()
        val wasSelected = hasSelection
        val wasStartCol = selStartCol
        val wasStartRow = selStartRow
        val wasEndCol = selEndCol
        val wasEndRow = selEndRow
        if (sc == Short.MIN_VALUE.toInt()) {
            selStartCol = Int.MIN_VALUE
        } else {
            selStartCol = sc
            selStartRow = buf.getShort(20).toInt()
            selEndCol = buf.getShort(22).toInt()
            selEndRow = buf.getShort(24).toInt()
        }
        if (wasSelected != hasSelection) onSelectionChanged?.invoke(hasSelection)
        // Only when an end moved: every frame asked the floating toolbar to
        // lay itself out again, and output streaming under a selection is a
        // lot of frames.
        val moved = wasStartCol != selStartCol || wasStartRow != selStartRow || wasEndCol != selEndCol || wasEndRow != selEndRow
        if (hasSelection && moved) {
            actionMode?.invalidateContentRect()
        }
        inputFlow.value = buf.getInt(inputOffset)
        snapLinks = buf.get(linksOffset).toInt() and 0xff
        snapImages = buf.getInt(imagesOffset)
        snapPredictions = buf.getInt(predictionsOffset)
        snapPredictionCount = buf.getShort(predictionsCountOffset).toInt() and 0xffff
        snapKittyFlags = buf.get(kittyFlagsOffset).toInt() and 0xff
        snapshot = buf
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // drawingTime, not uptimeMillis: onDraw runs a few ms after the vsync it belongs to, and
        // measuring the gap from here pushed every capped frame past the next vsync and onto the
        // one after, so a cap of 30 drew 20.
        lastFrameAt = drawingTime
        frameWaiting.set(false)
        val s = session ?: return
        pullSnapshot()
        pullImages()
        val buf = snapshot ?: return
        val sc = snapCols
        val sr = snapRows
        if (sc == 0 || sr == 0) return

        val ox = paddingLeft + paddingPx
        val oy = paddingTop + paddingPx
        // A view that does not reshape the grid has to make the grid fit
        // instead: one uniform scale, centered, so a floating window is the
        // same screen and not a corner of it.
        val scaled = !reshapesGrid && applyContentScale(canvas, ox, oy, sc, sr)
        val focused = isFocused && s.isConnected
        val bgColor = backgroundColorInt and 0xffffff
        // The dark half of a blink: the cell under the cursor is drawn like any
        // other, which is the whole point of the cursor going away.
        val block = focused && cursorLit && cursorShape == CursorShapes.BLOCK

        // The underlines are drawn from these at the end.
        scanLinks()

        // Pass 1: backgrounds. Recorded rows where the platform can draw
        // them; the cells walked every frame where it cannot.
        val rowNodes = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas.isHardwareAccelerated && rowsAt(buf, sr) > 0
        if (rowNodes) {
            fitRowNodes(sc, sr, bgColor)
            drawRowNodes(canvas, buf, ox, oy, sc, sr, bgColor, block, text = false)
        } else {
            for (r in 0 until sr) {
                drawRowBackground(canvas, buf, r, sc, ox, oy + r * cellH, bgColor, blockCol(block, r, sc))
            }
        }

        // The search hit, under the text like any other background. Whether
        // the cursor is showing has nothing to do with it, and a program that
        // hides its cursor — a pager, which is where searching happens — used
        // to hide the hit with it.
        searchHighlight?.let { (hr, hc, hl) ->
            if (hr in 0 until sr) {
                val y = oy + hr * cellH
                canvas.drawRect(ox + hc * cellW, y, ox + (hc + hl) * cellW, y + cellH, searchPaint)
            }
        }

        // Cursor block (drawn under text so the glyph stays readable).
        if (cursorRow in 0 until sr && cursorCol in 0 until sc && cursorShape != CursorShapes.HIDDEN) {
            val x = ox + cursorCol * cellW
            val y = oy + cursorRow * cellH
            val wide = buf.getShort(headerBytes + (cursorRow * sc + cursorCol) * cellBytes + 12).toInt() and FLAG_WIDE != 0
            val w = if (wide) cellW * 2 else cellW
            // An unfocused terminal never blinks: its hollow outline is already
            // saying that this is not where the typing goes.
            if (cursorLit || !focused) {
                cursorPaint.color = accentColor
                when {
                    !focused -> {
                        cursorPaint.style = Paint.Style.STROKE
                        cursorPaint.strokeWidth = max(1f, density)
                        canvas.drawRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + cellH - 0.5f, cursorPaint)
                        cursorPaint.style = Paint.Style.FILL
                    }
                    cursorShape == CursorShapes.BLOCK -> canvas.drawRect(x, y, x + w, y + cellH, cursorPaint)
                    cursorShape == CursorShapes.UNDERLINE -> canvas.drawRect(x, y + cellH - 2 * density, x + w, y + cellH, cursorPaint)
                    else -> canvas.drawRect(x, y, x + 2 * density, y + cellH, cursorPaint)
                }
            }
        }

        // The suggestion, dimmed, starting at the cursor and clipped to the row
        // it is on: it is a hint about the line being typed, not a second line.
        ghost?.let { hint ->
            val gRow = cursorRow
            val gCol = cursorCol
            if (gRow in 0 until sr && gCol in 0 until sc) {
                val room = sc - gCol
                if (room > 0) {
                    val text = ghostClipped(hint, room)
                    val textY = oy + gRow * cellH + baseline
                    textPaint.typeface = faces.regular
                    // The block cursor sits on the first character, so that one
                    // is drawn in the background color the way the cell under a
                    // cursor always is; the rest is the dim hint.
                    val underCursor = block
                    if (underCursor) {
                        textColor(bgColor or 0xff000000.toInt())
                        canvas.drawText(text, 0, 1, ox + gCol * cellW, textY, textPaint)
                    }
                    if (text.length > (if (underCursor) 1 else 0)) {
                        textColor(ghostColor())
                        val from = if (underCursor) 1 else 0
                        canvas.drawText(text, from, text.length, ox + (gCol + from) * cellW, textY, textPaint)
                    }
                }
            }
        }

        // Images the program asked to keep under the text (kitty's z < 0).
        drawImages(canvas, ox, oy, above = false)

        // Pass 2: text runs. Gathered by colour and put down in one go at the
        // end, so Skia builds a handful of glyph containers instead of one per
        // run; see [GlyphBatch].
        if (rowNodes) {
            drawRowNodes(canvas, buf, ox, oy, sc, sr, bgColor, block, text = true)
        } else {
            batching = glyphRuns()
            for (r in 0 until sr) {
                drawRowText(canvas, buf, r, sc, ox, oy + r * cellH, bgColor, blockCol(block, r, sc))
            }
            flushBatches(canvas, prune = true)
            batching = false
        }

        drawImages(canvas, ox, oy, above = true)

        // Predicted keystrokes: drawn over the authoritative screen and underlined,
        // so it is always visible which characters the server has not confirmed.
        val guesses = predictedCells(s)
        if (guesses.isNotEmpty()) {
            for (cell in guesses) {
                val r = cell.row.toInt()
                val c = cell.col.toInt()
                if (r !in 0 until sr || c !in 0 until sc) continue
                val x = ox + c * cellW
                val y = oy + r * cellH
                // Cover whatever is underneath, then draw the guess.
                bgPaint.color = backgroundColorInt
                canvas.drawRect(x, y, x + cellW, y + cellH, bgPaint)
                val n = Character.toChars(cell.codepoint.toInt(), runChars, 0)
                setPaintFor(foregroundColorInt, 0)
                canvas.drawText(runChars, 0, n, x, y + baseline, textPaint)
                predictPaint.color = (accentColor and 0xffffff) or 0xCC000000.toInt()
                canvas.drawLine(x, y + cellH - 1.5f * density, x + cellW, y + cellH - 1.5f * density, predictPaint)
            }
        }

        // Dotted underline under tappable URLs / paths, from the pass above.
        if (links.isNotEmpty()) {
            linkPaint.color = (accentColor and 0xffffff) or 0xAA000000.toInt()
            for (l in links) {
                val ly = oy + (l.row + 1) * cellH - 1.5f * density
                canvas.drawLine(ox + l.start * cellW, ly, ox + l.end * cellW, ly, linkPaint)
            }
        }

        // Selection handles.
        if (hasSelection && !mouseReporting) {
            handlePaint.color = accentColor
            val sx = ox + selStartCol * cellW
            val sy = oy + (selStartRow + 1) * cellH
            val ex = ox + (selEndCol + 1) * cellW
            val ey = oy + (selEndRow + 1) * cellH
            if (selStartRow in 0 until sr) drawHandle(canvas, sx, sy, left = true)
            if (selEndRow in 0 until sr) drawHandle(canvas, ex, ey, left = false)
        }

        // Back to window pixels: what is left is drawn against the edges of
        // the view rather than against the grid.
        if (scaled) canvas.restore()

        // Scroll position indicator while browsing history.
        if (displayOffset > 0 && historySize > 0) {
            val trackH = height - 2 * paddingPx
            val visible = sr.toFloat() / (historySize + sr)
            val thumbH = max(24 * density, trackH * visible)
            val pos = 1f - displayOffset.toFloat() / historySize
            val top = paddingPx + (trackH - thumbH) * pos
            handlePaint.color = (accentColor and 0xffffff) or 0x99000000.toInt()
            canvas.drawRoundRect(width - 6 * density, top, width - 2 * density, top + thumbH, 2 * density, 2 * density, handlePaint)
        }

        if (!scroller.isFinished) computeFling()
    }

    /** Whether glyphs can be put down by number rather than shaped: API 31, and no ligatures. */
    private fun glyphRuns(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ligatures

    /** The column of the lit block cursor on row [r], or -1 when it is not there. */
    private fun blockCol(block: Boolean, r: Int, sc: Int): Int =
        if (block && r == cursorRow && cursorCol in 0 until sc) cursorCol else -1

    /**
     * One row's backgrounds, the row's top left at ([x], [y]). The cell under
     * a block cursor, [blockCol], is left for the cursor to fill. Returns
     * whether anything was drawn.
     */
    private fun drawRowBackground(canvas: Canvas, buf: ByteBuffer, r: Int, sc: Int, x: Float, y: Float, bgColor: Int, blockCol: Int): Boolean {
        var drew = false
        var runStart = -1
        var runBg = 0
        var off = headerBytes + r * sc * cellBytes + 8
        for (c in 0..sc) {
            var bg = if (c < sc) buf.getInt(off) else -1
            off += cellBytes
            if (c < sc && c == blockCol) bg = -2
            if (bg != runBg || c == sc) {
                if (runStart >= 0 && runBg != bgColor && runBg != -2) {
                    bgPaint.color = 0xff000000.toInt() or runBg
                    canvas.drawRect(x + runStart * cellW, y, x + c * cellW, y + cellH, bgPaint)
                    drew = true
                }
                runStart = c
                runBg = bg
            }
        }
        return drew
    }

    /** Set by the text pass whenever it puts something down, so a row that drew nothing is known. */
    private var rowInk = false

    /**
     * One row's text and decorations, the row's top left at ([x], [y]). The
     * glyph under a block cursor, at [blockCol], is drawn in the background
     * colour. Batched glyphs are left for the caller to flush. Returns whether
     * anything was drawn or batched.
     */
    private fun drawRowText(canvas: Canvas, buf: ByteBuffer, r: Int, sc: Int, x: Float, y: Float, bgColor: Int, blockCol: Int): Boolean {
        rowInk = false
        val textY = y + baseline
        var runLen = 0
        var runStartCol = 0
        var runFg = 0
        var runFlags = 0
        var off = headerBytes + r * sc * cellBytes
        for (c in 0 until sc) {
            // Codepoint and foreground are adjacent little-endian u32s, so
            // one read fetches both: every one of these is bounds checked,
            // and there are four per cell of the grid on every frame.
            val pair = buf.getLong(off)
            val cp = pair.toInt()
            var fg = (pair ushr 32).toInt()
            val flags = buf.getShort(off + 12).toInt() and 0xffff
            off += cellBytes
            if (c == blockCol) fg = bgColor
            val styleFlags = flags and STYLE_MASK
            val ascii = cp in 0x20..0x7e
            val blank = cp == 0 || cp == 0x20
            val hidden = flags and FLAG_HIDDEN != 0
            // Flush when attributes change or a non-ASCII glyph appears. A
            // hidden cell ends the run as well: skipped inside one, the text
            // after it was drawn a column early. The core cuts its runs the
            // same way, and the two have to agree.
            if (runLen > 0 && (fg != runFg || styleFlags != runFlags || !ascii || hidden || runLen >= runChars.size - 2)) {
                drawRun(canvas, x + runStartCol * cellW, textY, runLen, runFg, runFlags)
                runLen = 0
            }
            if (flags and FLAG_WIDE_SPACER != 0 || hidden) continue
            if (ascii) {
                if (runLen == 0) {
                    runStartCol = c
                    runFg = fg
                    runFlags = styleFlags
                }
                runChars[runLen++] = cp.toChar()
            } else if (!blank) {
                // A non-ASCII cell is already a run of its own, which is
                // where the symbols font gets its chance to take over.
                rowInk = true
                setPaintFor(fg, styleFlags, nerdGlyphs && RunSplitter.isSymbol(cp))
                if (!batchGlyph(cp, x + c * cellW, textY)) {
                    textColor(paintColor)
                    val n = Character.toChars(cp, runChars, 0)
                    canvas.drawText(runChars, 0, n, x + c * cellW, textY, textPaint)
                }
                drawDecorations(canvas, x + c * cellW, y, if (flags and FLAG_WIDE != 0) cellW * 2 else cellW, fg, styleFlags)
            }
        }
        if (runLen > 0) drawRun(canvas, x + runStartCol * cellW, textY, runLen, runFg, runFlags)
        return rowInk
    }

    // ---- recorded rows ----------------------------------------------------

    /** Where the row table starts in [buf], or 0 when the core appended none. */
    private fun rowsAt(buf: ByteBuffer, sr: Int): Int {
        val at = buf.getInt(rowsOffset)
        return if (at > 0 && at.toLong() + sr.toLong() * rowEntryBytes <= snapLen) at else 0
    }

    /** Recorded backgrounds and recorded text, each keyed on the row hash; see [RowNodes]. */
    private val bgNodes by lazy(LazyThreadSafetyMode.NONE) { RowNodes() }
    private val textNodes by lazy(LazyThreadSafetyMode.NONE) { RowNodes() }

    /** What the recordings were drawn with; any of it moving throws them all away. */
    private var nodesFaces: TermFonts.Faces? = null
    private var nodesTextSize = 0f
    private var nodesCellW = 0f
    private var nodesCellH = 0f
    private var nodesBg = 0
    private var nodesCols = 0
    private var nodesLigatures = false
    private var nodesNerd = false

    /**
     * Size the caches to the grid, and empty them if anything a recording
     * depends on besides the cells has changed: the font, its size, the
     * background a default cell is left to show, ligatures or icons. A new
     * palette needs nothing here, because the colours are in the cells.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun fitRowNodes(sc: Int, sr: Int, bgColor: Int) {
        bgNodes.fit(sr)
        textNodes.fit(sr)
        if (nodesFaces === faces && nodesTextSize == textPaint.textSize && nodesCellW == cellW && nodesCellH == cellH &&
            nodesBg == bgColor && nodesCols == sc && nodesLigatures == ligatures && nodesNerd == nerdGlyphs
        ) {
            return
        }
        nodesFaces = faces
        nodesTextSize = textPaint.textSize
        nodesCellW = cellW
        nodesCellH = cellH
        nodesBg = bgColor
        nodesCols = sc
        nodesLigatures = ligatures
        nodesNerd = nerdGlyphs
        bgNodes.clear()
        textNodes.clear()
    }

    /** Let go of every recording, on the way off screen; the next frame records again. */
    private fun dropRowNodes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        bgNodes.clear()
        textNodes.clear()
    }

    /**
     * Put down every row's backgrounds, or every row's text, from recordings,
     * recording only the rows whose content has not been recorded yet.
     *
     * Backgrounds and text are kept apart so the frame is layered as before:
     * every background first, then the search hit, the cursor, the ghost text
     * and images meant to sit under the text, then all of the text. A glyph
     * that reaches into the row below is not painted over by that row's
     * background.
     *
     * The row under a lit block cursor is recorded under a key of its own:
     * that cell leaves its background to the cursor and draws its glyph in the
     * background colour. The key is the row's with the cursor column mixed
     * in, so both halves of a blink stay recorded and blinking records
     * nothing once each has been seen.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun drawRowNodes(canvas: Canvas, buf: ByteBuffer, ox: Float, oy: Float, sc: Int, sr: Int, bgColor: Int, block: Boolean, text: Boolean) {
        val cache = if (text) textNodes else bgNodes
        val table = rowsAt(buf, sr)
        val w = kotlin.math.ceil(sc * cellW).toInt()
        val h = cellH.toInt()
        var recorded = false
        canvas.save()
        canvas.translate(ox, oy)
        for (r in 0 until sr) {
            val blockCol = blockCol(block, r, sc)
            var key = buf.getLong(table + r * rowEntryBytes)
            if (blockCol >= 0) key = cursorKey(key, blockCol)
            var slot = cache.find(r, key)
            if (slot < 0) {
                slot = cache.claim(r, key)
                recorded = true
                val node = cache.node(slot)
                node.setPosition(0, 0, w, h)
                val rc = node.beginRecording(w, h)
                // The core's runs for this row, where it laid them out with
                // what the view has now. The row under a block cursor walks
                // its cells instead: the runs know nothing of the cursor.
                val runsAt = if (blockCol < 0 && snapLayout == layoutSerial) {
                    buf.getInt(table + r * rowEntryBytes + if (text) 12 else 8)
                } else {
                    0
                }
                val inked = if (text) {
                    if (runsAt > 0) {
                        recordTextRuns(rc, buf, runsAt)
                    } else {
                        batching = glyphRuns()
                        val drew = drawRowText(rc, buf, r, sc, 0f, 0f, bgColor, blockCol)
                        flushBatches(rc, prune = false)
                        batching = false
                        drew
                    }
                } else if (runsAt > 0) {
                    recordBackgroundRuns(rc, buf, runsAt, bgColor)
                } else {
                    drawRowBackground(rc, buf, r, sc, 0f, 0f, bgColor, blockCol)
                }
                node.endRecording()
                cache.setInked(slot, inked)
            }
            if (cache.inked(slot)) canvas.drawRenderNode(cache.node(slot))
            canvas.translate(0f, cellH)
        }
        canvas.restore()
        // Only after a frame that used them: one drawn wholly from recordings
        // would otherwise throw away every colour the next change needs.
        if (text && recorded) flushBatches(canvas, prune = true)
    }

    /** A row's key with a block cursor at [col] in it; see [drawRowNodes]. */
    private fun cursorKey(hash: Long, col: Int): Long =
        (hash xor ((col + 1).toLong() * -0x61c8864680b583ebL)) * -0x3d4d51c2d82b14b1L

    // ---- row runs from the core -------------------------------------------

    /** What the core's row runs were last laid out for; any of it moving lays them out again. */
    private var layoutFaces: TermFonts.Faces? = null
    private var layoutTextSize = 0f
    private var layoutCellW = 0f
    private var layoutBaseline = 0f
    private var layoutFlags = -1
    private var layoutLigatures = false
    private var layoutBuffer = 0L

    /** Counts the times the core's layout was reset; [snapLayout] is the count the snapshot was packed at. */
    private var layoutSerial = 0
    private var snapLayout = -1

    /**
     * The fonts glyph numbers in the runs refer to, by the number the core
     * was given. Glyph numbers only mean anything inside their own font.
     */
    private val runFonts = ArrayList<android.graphics.fonts.Font>(8)

    /** Whether each face's ASCII glyphs have been handed to the core since the last reset. */
    private val slotAsked = BooleanArray(FrameBridge.SLOTS)

    /**
     * Tell the core what to lay row runs out with before it packs, whenever
     * that has changed: the cell size and baseline the glyphs are placed by,
     * and whether icons have their own face. The glyph numbers it was told
     * are forgotten with it, and handed over again as rows need them.
     *
     * Before the packing, not after: a row recorded from runs placed for the
     * last font size would be kept under its hash and drawn wrong until it
     * changed.
     */
    private fun syncLayout() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || bufferHandle == 0L) return
        val flags = FrameBridge.LAYOUT_RUNS or if (nerdGlyphs) FrameBridge.LAYOUT_NERD else 0
        if (layoutBuffer == bufferHandle && layoutFaces === faces && layoutTextSize == textPaint.textSize &&
            layoutCellW == cellW && layoutBaseline == baseline && layoutFlags == flags && layoutLigatures == ligatures
        ) {
            return
        }
        layoutBuffer = bufferHandle
        layoutFaces = faces
        layoutTextSize = textPaint.textSize
        layoutCellW = cellW
        layoutBaseline = baseline
        layoutFlags = flags
        layoutLigatures = ligatures
        layoutSerial++
        runFonts.clear()
        slotAsked.fill(false)
        FrameBridge.resetGlyphs(bufferHandle, flags, cellW, baseline)
    }

    /** One row's backgrounds from the core's merged rectangles; the default colour is left to the view. */
    private fun recordBackgroundRuns(canvas: Canvas, buf: ByteBuffer, at: Int, bgColor: Int): Boolean {
        var drew = false
        var p = at + 4
        repeat(buf.getInt(at)) {
            val span = buf.getInt(p)
            val color = buf.getInt(p + 4)
            p += 8
            if (color != bgColor) {
                bgPaint.color = 0xff000000.toInt() or color
                canvas.drawRect((span and 0xffff) * cellW, 0f, (span ushr 16) * cellW, cellH, bgPaint)
                drew = true
            }
        }
        return drew
    }

    /**
     * One row's text from the core's runs: what could not be drawn by number
     * as text, then decorations, then one [Canvas.drawGlyphs] per colour and
     * font with the numbers and positions copied out in bulk. No cell is
     * looked at here.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun recordTextRuns(canvas: Canvas, buf: ByteBuffer, at: Int): Boolean {
        val fallbacks = buf.getInt(at)
        val decorations = buf.getInt(at + 4)
        val batches = buf.getInt(at + 8)
        var p = at + 12
        repeat(fallbacks) {
            val span = buf.getInt(p)
            val color = buf.getInt(p + 4)
            val meta = buf.getInt(p + 8)
            p += 12
            val n = span ushr 16
            val first = buf.getInt(p)
            var len = 0
            repeat(n) {
                len += Character.toChars(buf.getInt(p), runChars, len)
                p += 4
            }
            val slot = (meta ushr 16) and 0xff
            setPaintFor(color, meta and 0xffff, slot == FrameBridge.SLOT_SYMBOL)
            canvas.drawText(runChars, 0, len, (span and 0xffff) * cellW, baseline, textPaint)
            if (meta and FrameBridge.FALLBACK_LEARN != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ligatures) {
                learnGlyphs(slot, n, first)
            }
        }
        repeat(decorations) {
            val span = buf.getInt(p)
            drawDecorations(canvas, (span and 0xffff) * cellW, 0f, (span ushr 16) * cellW, buf.getInt(p + 4), buf.getInt(p + 8))
            p += 12
        }
        if (batches > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ints = snapInts ?: return true
            val floats = snapFloats ?: return true
            repeat(batches) {
                val color = buf.getInt(p)
                val font = buf.getInt(p + 4)
                val n = buf.getInt(p + 8)
                p += 12
                if (glyphIds.size < n) {
                    glyphIds = IntArray(n * 2)
                    glyphPos = FloatArray(n * 4)
                }
                ints.position(p / 4)
                ints.get(glyphIds, 0, n)
                p += n * 4
                floats.position(p / 4)
                floats.get(glyphPos, 0, n * 2)
                p += n * 8
                if (font in runFonts.indices) {
                    textColor(color)
                    canvas.drawGlyphs(glyphIds, 0, glyphPos, 0, n, runFonts[font], textPaint)
                }
            }
        }
        return fallbacks + decorations + batches > 0
    }

    /**
     * Teach the core the glyphs a fallback run could have been drawn with, so
     * the next packing draws them by number: the face's ASCII table the first
     * time the face turns up, and a character outside ASCII once it has been
     * shaped. The paint is already set to the run's face.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun learnGlyphs(slot: Int, n: Int, cp: Int) {
        // The icon slot without an icon font is drawn in the style's face,
        // which a slot of its own cannot stand for.
        val usable = slot != FrameBridge.SLOT_SYMBOL || symbolFace != null
        val table = if (usable) cachedTable() else null
        if (!slotAsked[slot]) {
            slotAsked[slot] = true
            if (table == null) {
                FrameBridge.setGlyphs(bufferHandle, slot, -1, 0L)
            } else {
                val ids = com.sun.jna.Memory((table.ids.size * 4).toLong())
                ids.write(0, table.ids, 0, table.ids.size)
                FrameBridge.setGlyphs(bufferHandle, slot, fontIndex(table.font), com.sun.jna.Pointer.nativeValue(ids))
                ids.close()
            }
        }
        if (table == null || n != 1 || cp in 0x20..0x7e) return
        val ref = glyphOf(table, cp)
        FrameBridge.learnGlyph(bufferHandle, slot, cp, ref?.let { fontIndex(it.font) } ?: -1, ref?.id ?: -1)
    }

    /** The core's number for [font], given one the first time it turns up. */
    private fun fontIndex(font: android.graphics.fonts.Font): Int {
        for (i in runFonts.indices) if (runFonts[i] === font || runFonts[i] == font) return i
        runFonts.add(font)
        return runFonts.size - 1
    }

    /**
     * The cells predictive echo is drawing, fetched only when the header says
     * they changed, and not at all while it says there are none — which is
     * every frame of every session that is not Mosh on a slow link.
     */
    private fun predictedCells(s: TerminalSession): List<dev.flint.term.core.PredictedCell> {
        if (snapPredictionCount == 0) {
            predicted = emptyList()
            predictedSeen = -1
            return predicted
        }
        if (predictedSeen != snapPredictions) {
            predictedSeen = snapPredictions
            predicted = s.predictions
        }
        return predicted
    }

    /** The suggestion cut to the room left on its row, kept while neither changes. */
    private var ghostClip: String? = null
    private var ghostClipOf: String? = null
    private var ghostClipRoom = -1

    private fun ghostClipped(hint: String, room: Int): String {
        if (hint !== ghostClipOf || room != ghostClipRoom) {
            ghostClipOf = hint
            ghostClipRoom = room
            ghostClip = if (hint.length > room) hint.substring(0, room) else hint
        }
        return ghostClip ?: hint
    }

    /**
     * Fit `sc` x `sr` cells into the view. Returns whether the canvas was
     * saved, so the caller knows to restore it.
     */
    private fun applyContentScale(canvas: Canvas, ox: Float, oy: Float, sc: Int, sr: Int): Boolean {
        val naturalW = 2 * ox + sc * cellW
        val naturalH = 2 * oy + sr * cellH
        if (naturalW <= 0f || naturalH <= 0f || width <= 0 || height <= 0) return false
        val scale = min(width / naturalW, height / naturalH)
        canvas.save()
        canvas.translate((width - scale * naturalW) / 2f, (height - scale * naturalH) / 2f)
        canvas.scale(scale, scale)
        return true
    }

    private fun drawRun(canvas: Canvas, x: Float, textY: Float, len: Int, fg: Int, flags: Int) {
        // Skip runs of spaces entirely; the background pass already painted them.
        var allSpace = true
        for (i in 0 until len) if (runChars[i] != ' ') { allSpace = false; break }
        // Nothing to draw at all, which is most of a screen: not even the
        // paint needs to hear about it.
        if (allSpace && flags and DECORATIONS == 0) return
        rowInk = true
        setPaintFor(fg, flags)
        if (!allSpace && !drawRunAsGlyphs(canvas, x, textY, len)) {
            textColor(paintColor)
            canvas.drawText(runChars, 0, len, x, textY, textPaint)
        }
        drawDecorations(canvas, x, textY - baseline, len * cellW, fg, flags)
    }

    /**
     * Paint a run as glyphs the font has already been asked about, or return
     * false to let [Canvas.drawText] do it the ordinary way.
     *
     * `drawText` shapes and lays out the run every time it is called: harfbuzz
     * decides which glyphs the characters become and minikin decides where they
     * go. On a grid neither answer can change — the glyph for `a` is always the
     * same glyph and column `n` is always `n` cells across — and yet a screen
     * of changing output asks both questions again for every run of every
     * frame. Between them they were a fifth of the app's work.
     *
     * So each character is shaped once, its glyph kept, and a run becomes a
     * list of glyphs at known positions. Ligatures are the exception and take
     * the ordinary path: joining `!=` into one glyph is exactly the shaping
     * this skips.
     */
    private fun drawRunAsGlyphs(canvas: Canvas, x: Float, textY: Float, len: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ligatures) return false
        val table = cachedTable() ?: return false
        if (glyphIds.size < len) {
            glyphIds = IntArray(len * 2)
            glyphPos = FloatArray(len * 4)
        }
        if (batching) {
            // The paint is reused for the next run, so what it is set to now —
            // the colour, and the face the table came from — keys this batch.
            for (i in 0 until len) {
                val ch = runChars[i]
                if (ch == ' ') continue // the background pass drew it
                val id = table.ids[ch.code - 0x21]
                if (id < 0) return false
                batchInto(table.font, id, x + i * cellW, textY)
            }
            return true
        }
        var n = 0
        for (i in 0 until len) {
            val ch = runChars[i]
            if (ch == ' ') continue // the background pass drew it
            val id = table.ids[ch.code - 0x21]
            if (id < 0) return false // this font had nothing to say about it
            glyphIds[n] = id
            glyphPos[n * 2] = x + i * cellW
            glyphPos[n * 2 + 1] = textY
            n++
        }
        if (n > 0) canvas.drawGlyphs(glyphIds, 0, glyphPos, 0, n, table.font, textPaint)
        return true
    }

    /**
     * The glyph [table] draws [cp] with, or [MISSING] when this font has no
     * single glyph for it and the ordinary path has to draw it.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun glyphOf(table: GlyphTable, cp: Int): GlyphRef? {
        if (cp in 0x21..0x7e) {
            val id = table.ids[cp - 0x21]
            return if (id == MISSING) null else GlyphRef(table.font, id)
        }
        // A codepoint with no answer is remembered as MISSING_GLYPH rather than
        // as null, because a null would be indistinguishable from "never asked"
        // and would reshape the same character on every frame that draws it.
        val known = table.extra.get(cp)
        if (known != null) return known as? GlyphRef
        val chars = CharArray(2)
        val n = Character.toChars(cp, chars, 0)
        val shaped = runCatching {
            android.graphics.text.TextRunShaper.shapeTextRun(chars, 0, n, 0, n, 0f, 0f, false, textPaint)
        }.getOrNull()
        // One glyph is the whole condition now: which font drew it no longer
        // matters, because the batch it joins is keyed on that font.
        val ref = if (shaped != null && shaped.glyphCount() == 1) {
            GlyphRef(table.canonical(shaped.getFont(0)), shaped.getGlyphId(0))
        } else {
            null
        }
        table.extra.put(cp, ref ?: MISSING_GLYPH)
        return ref
    }

    /**
     * Add one character to the batch it belongs in, and say whether it went.
     *
     * This is the path a TUI's box drawing takes: not ASCII, but the same few
     * characters on every frame, so worth asking the font about once.
     */
    private fun batchGlyph(cp: Int, x: Float, textY: Float): Boolean {
        if (!batching || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val table = cachedTable() ?: return false
        val ref = glyphOf(table, cp) ?: return false
        batchInto(ref.font, ref.id, x, textY)
        return true
    }

    /**
     * Put one glyph in the batch for its colour and font.
     *
     * The batch a glyph belongs in almost never changes between one glyph and
     * the next — a run is one colour in one font by construction — so the last
     * one is kept. Looking it up per glyph meant hashing a `Font` tens of
     * thousands of times a frame, which cost more than the batching saved.
     */
    private fun batchInto(font: android.graphics.fonts.Font, id: Int, x: Float, textY: Float) {
        val color = paintColor
        var b = lastBatch
        if (b == null || b.color != color || b.font !== font) {
            b = null
            for (i in batches.indices) {
                val candidate = batches[i]
                if (candidate.color == color && candidate.font === font) {
                    b = candidate
                    break
                }
            }
            if (b == null) {
                b = GlyphBatch(color, font)
                batches.add(b)
            }
            lastBatch = b
        }
        b.add(id, x, textY)
    }

    /** The batch the previous glyph went in; a run's worth land in the same one. */
    private var lastBatch: GlyphBatch? = null

    /** The table last asked for, for the same reason. */
    private var lastTable: GlyphTable? = null
    /** Whether [lastTable] has been worked out, which a null table cannot say. */
    private var lastTableSet = false
    private var lastTableFace: android.graphics.Typeface? = null
    private var lastTableSize = 0f

    /** [glyphsFor] without the map lookup when the paint has not moved. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun cachedTable(): GlyphTable? {
        val face = textPaint.typeface
        val size = textPaint.textSize
        if (lastTableSet && face === lastTableFace && size == lastTableSize) return lastTable
        val t = glyphsFor(face)
        lastTable = t
        lastTableSet = true
        lastTableFace = face
        lastTableSize = size
        return t
    }

    /**
     * Put down everything the text pass gathered: one call per colour and face.
     *
     * With [prune], a batch nothing went into since the last prune is a colour
     * that has left the screen, and it goes. Kept, they piled up for the life
     * of the view, one per colour ever shown, and every change of colour
     * walked them all: after a truecolor gradient that was hundreds per glyph.
     * Recorded rows flush one row at a time and prune once a frame, so a
     * colour the next row uses is not thrown away and made again.
     */
    private fun flushBatches(canvas: Canvas, prune: Boolean) {
        if (!batching && !prune) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        var kept = 0
        for (i in batches.indices) {
            val batch = batches[i]
            if (batch.n > 0) {
                textColor(batch.color)
                canvas.drawGlyphs(batch.ids, 0, batch.pos, 0, batch.n, batch.font, textPaint)
                batch.n = 0
                batch.used = true
            }
            if (prune && batch.used) {
                batch.used = false
                batches[kept++] = batch
            }
        }
        if (prune) while (batches.size > kept) batches.removeAt(batches.size - 1)
        lastBatch = null
    }

    /**
     * One glyph per printable ASCII character, for one typeface at one size,
     * plus whatever else this screen has turned out to need.
     *
     * The box drawing a TUI is made of lives in [extra]: it is not worth
     * shaping all of Unicode up front, but a terminal draws the same handful of
     * lines and corners over and over, so each one is asked about once. A
     * character this font does not own is remembered as [MISSING] so it is not
     * asked about again on every frame.
     */
    private class GlyphTable(val font: android.graphics.fonts.Font, val ids: IntArray) {
        /**
         * Every distinct font these glyphs come out of, one instance each.
         *
         * The platform hands back a fresh wrapper from every shaping call, so
         * two glyphs out of the same fallback font arrive as unequal objects
         * that are equal by value. Comparing them by value meant hashing a Font
         * to find the batch a glyph belongs in, which is not cheap and happened
         * for every run on the screen. Kept once here, identity is enough.
         */
        val fonts = ArrayList<android.graphics.fonts.Font>(4)

        /**
         * Characters outside ASCII, each with the font it actually came out of.
         *
         * A box corner comes from the terminal font, but the braille a CPU
         * graph is drawn with is usually somebody else's, and glyph numbers
         * only mean anything within one font.
         */
        val extra = android.util.SparseArray<Any>()

        init {
            // The face ASCII comes out of is the first one everything else is
            // compared against, so a fallback that turns out to be the same
            // font shares its batch rather than opening another.
            fonts.add(font)
        }

        fun canonical(f: android.graphics.fonts.Font): android.graphics.fonts.Font {
            for (known in fonts) if (known == f) return known
            fonts.add(f)
            return f
        }
    }

    /** One glyph, and the font it is numbered in. */
    private class GlyphRef(val font: android.graphics.fonts.Font, val id: Int)

    /** A face at a size; both matter, and neither may be reduced to a hash. */
    private data class TableKey(val face: android.graphics.Typeface?, val size: Float)

    private val glyphTables = HashMap<TableKey, GlyphTable?>()
    private var glyphIds = IntArray(0)
    private var glyphPos = FloatArray(0)

    /**
     * Glyphs waiting to be drawn, gathered by the colour and style they share.
     *
     * Skia builds a container of glyph runs for every draw call, and a screen
     * of coloured output is hundreds of short runs — `ls --color`, a build log,
     * anything with a status line. Collecting the whole frame first turns that
     * into one call per colour on screen, which is rarely more than a handful,
     * and the glyphs of one colour never overlap so the order they go down in
     * does not matter.
     */
    private class GlyphBatch(val color: Int, val font: android.graphics.fonts.Font) {
        var ids = IntArray(256)
        var pos = FloatArray(512)
        var n = 0
        /** Whether anything went down from this batch since the last prune. */
        var used = false
        fun add(id: Int, x: Float, y: Float) {
            if (n == ids.size) {
                ids = ids.copyOf(n * 2)
                pos = pos.copyOf(n * 4)
            }
            ids[n] = id
            pos[n * 2] = x
            pos[n * 2 + 1] = y
            n++
        }
    }

    /**
     * What one call can draw: everything of one colour out of one font.
     *
     * A list rather than a map. A screen holds a handful of these, and walking
     * a handful comparing an int and a reference is cheaper than hashing a Font
     * to find one — which is what the map was doing for every run drawn.
     *
     * Glyph numbers only mean anything inside the font they came from, so a
     * batch is per font as well as per colour: two fonts sharing one draws the
     * right text in the wrong alphabet.
     */
    private val batches = ArrayList<GlyphBatch>(16)
    private var batching = false

    /**
     * The glyphs [face] draws printable ASCII with, or null when it cannot be
     * done this way — a character the font renders with more than one glyph, or
     * out of a different font, means the shaper has something to say after all.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun glyphsFor(face: android.graphics.Typeface?): GlyphTable? {
        // Size is part of the key: the same face at another size is other glyphs.
        val key = TableKey(face, textPaint.textSize)
        // Pinch-zoom walks through sizes and each one is its own table; the
        // ones behind it are never asked for again.
        if (glyphTables.size > 8) glyphTables.clear()
        // Not getOrPut: it treats a stored null as absent, so a face with no
        // usable table ran its whole loop of shaping calls again every time it
        // was asked, which for a symbols-only font is once per icon per frame.
        if (glyphTables.containsKey(key)) return glyphTables[key]
        return glyphTables.getOrPut(key) {
            val ids = IntArray(0x7f - 0x21)
            var font: android.graphics.fonts.Font? = null
            var usable = true
            val one = CharArray(1)
            for (cp in 0x21..0x7e) {
                // The space is skipped on purpose: it is never drawn (the
                // background pass has already painted it) and on some devices
                // it is the one character that comes out of a different font.
                one[0] = cp.toChar()
                val shaped = runCatching {
                    android.graphics.text.TextRunShaper.shapeTextRun(one, 0, 1, 0, 1, 0f, 0f, false, textPaint)
                }.getOrNull()
                if (shaped == null) { usable = false; break }
                // More than one glyph means the shaper had something to say
                // about this character after all, so the whole table is off.
                if (shaped.glyphCount() != 1) { usable = false; break }
                val f = shaped.getFont(0)
                // Compared by value: the platform hands back a fresh wrapper
                // for every call, so identity is never equal past the first.
                if (font == null) font = f else if (font != f) { usable = false; break }
                ids[cp - 0x21] = shaped.getGlyphId(0)
            }
            if (!usable) null else font?.let { GlyphTable(it, ids) }
        }
    }

    /**
     * Point the paint at a style and colour, touching it only where it moves:
     * both setters cross into native code, and runs mostly share both.
     */
    private fun setPaintFor(fg: Int, flags: Int, symbol: Boolean = false) {
        val face = (if (symbol) symbolFace else null) ?: when {
            flags and FLAG_BOLD != 0 && flags and FLAG_ITALIC != 0 -> boldItalic
            flags and FLAG_BOLD != 0 -> bold
            flags and FLAG_ITALIC != 0 -> italic
            else -> regular
        }
        if (textPaint.typeface !== face) textPaint.typeface = face
        paintColor = 0xff000000.toInt() or fg
        // While batching, the colour only says which batch a glyph joins; the
        // paint is given it when the batch goes down, or by [textColor] right
        // before a glyph has to be drawn the ordinary way.
        if (!batching) textColor(paintColor)
    }

    /** What [setPaintFor] last set, so batching does not ask the paint once per glyph. */
    private var paintColor = 0

    /** The colour [textPaint] holds, so it is only set when it changes. */
    private var textPaintColor = Color.BLACK

    private fun textColor(color: Int) {
        if (color == textPaintColor) return
        textPaint.color = color
        textPaintColor = color
    }

    private fun drawDecorations(canvas: Canvas, x: Float, y: Float, w: Float, fg: Int, flags: Int) {
        if (flags and (FLAG_UNDERLINE or FLAG_STRIKEOUT or FLAG_DOUBLE_UNDERLINE or FLAG_UNDERCURL) == 0) return
        linePaint.color = 0xff000000.toInt() or fg
        val bottom = y + cellH
        if (flags and (FLAG_UNDERLINE or FLAG_UNDERCURL) != 0) {
            canvas.drawLine(x, bottom - 1.5f * density, x + w, bottom - 1.5f * density, linePaint)
        }
        if (flags and FLAG_DOUBLE_UNDERLINE != 0) {
            canvas.drawLine(x, bottom - 1f * density, x + w, bottom - 1f * density, linePaint)
            canvas.drawLine(x, bottom - 3f * density, x + w, bottom - 3f * density, linePaint)
        }
        if (flags and FLAG_STRIKEOUT != 0) {
            val mid = y + cellH * 0.55f
            canvas.drawLine(x, mid, x + w, mid, linePaint)
        }
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float, left: Boolean) {
        val r = handleRadius
        val cx = if (left) x - r * 0.35f else x + r * 0.35f
        canvas.drawCircle(cx, y + r, r, handlePaint)
        // Little "stem" pointing at the selection edge.
        tmpRect.set(cx - r * 0.5f, y, cx + r * 0.5f, y + r)
        canvas.drawRect(if (left) cx else cx - r * 0.5f, y, if (left) cx + r * 0.5f else cx, y + r, handlePaint)
    }

    // ---- fling ------------------------------------------------------------

    private fun computeFling() {
        if (scroller.computeScrollOffset()) {
            val y = scroller.currY
            val dy = y - lastFlingY
            lastFlingY = y
            scrollBy(dy.toFloat(), null)
            postInvalidateOnAnimation()
        }
    }

    private fun scrollBy(dyPixels: Float, e: MotionEvent?) {
        val s = session ?: return
        // Here rather than at the gesture, so the fling that follows a drag
        // covers the same ground per screenful that the drag did — a fling
        // that suddenly scrolled at a different rate would read as a bug.
        scrollRemainder += dyPixels * scrollSpeed
        val lines = (scrollRemainder / cellH).toInt()
        if (lines == 0) return
        scrollRemainder -= lines * cellH
        val c = if (e != null) colAt(e.x) else 0
        val r = if (e != null) rowAt(e.y) else 0
        // Over JNI: a drag or a fling sends one of these for every line it
        // crosses, and each through uniffi was a round of reflection.
        val handle = frameSession()
        if (handle != 0L) FrameBridge.scroll(handle, c, r, lines) else core?.scroll(c.toUShort(), r.toUShort(), lines)
    }

    // ---- touch ------------------------------------------------------------

    // Touch arrives at 120-240 Hz, far faster than a finger crosses cells, so
    // the core is only told when the cell under it changes.
    private var dragCell = -1L

    private fun dragSelect(x: Float, y: Float, moveStart: Boolean) {
        val c = colAt(x)
        val r = rowAt(y)
        val cell = (r.toLong() shl 32) or c.toLong()
        if (cell == dragCell) return
        dragCell = cell
        val handle = frameSession()
        if (handle != 0L) FrameBridge.selectUpdate(handle, c, r, moveStart) else core?.selectUpdate(c.toUShort(), r.toUShort(), moveStart)
    }

    /** The column under `x`, clamped to the grid; see [rowAt]. */
    private fun colAt(x: Float): Int =
        floor((x - paddingLeft - paddingPx) / cellW).toInt().coerceIn(0, max(0, snapCols - 1))

    private fun rowAt(y: Float): Int =
        floor((y - paddingTop - paddingPx) / cellH).toInt().coerceIn(0, max(0, snapRows - 1))

    private fun handleAt(x: Float, y: Float): Drag {
        if (!hasSelection) return Drag.NONE
        val ox = paddingLeft + paddingPx
        val oy = paddingTop + paddingPx
        val sx = ox + selStartCol * cellW - handleRadius * 0.35f
        val sy = oy + (selStartRow + 1) * cellH + handleRadius
        val ex = ox + (selEndCol + 1) * cellW + handleRadius * 0.35f
        val ey = oy + (selEndRow + 1) * cellH + handleRadius
        val dS = abs(x - sx) + abs(y - sy)
        val dE = abs(x - ex) + abs(y - ey)
        return when {
            dE <= handleTouchSlop && dE <= dS -> Drag.HANDLE_END
            dS <= handleTouchSlop -> Drag.HANDLE_START
            else -> Drag.NONE
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A scaled view maps pixels to cells through a factor the gesture code
        // knows nothing about, and the one view that is scaled — the floating
        // window — is handed no touches by the system anyway.
        if (!reshapesGrid) return false
        val s = session ?: return super.onTouchEvent(event)
        if (DEBUG_TOUCH) android.util.Log.d("TerminalView", "touch action=${event.actionMasked} pointers=${event.pointerCount} scaling=$scaling drag=$drag")
        // A second finger always means pinch-zoom: abandon any selection / handle drag.
        multiTouch = event.pointerCount > 1
        if (multiTouch && drag != Drag.NONE) {
            drag = Drag.NONE
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                twoFinger = TwoFinger.NONE
                val h = handleAt(event.x, event.y)
                if (h != Drag.NONE) {
                    drag = h
                    finishActionMode()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> when (drag) {
                Drag.SELECT, Drag.HANDLE_END -> {
                    dragSelect(event.x, event.y, false)
                    return true
                }
                Drag.HANDLE_START -> {
                    dragSelect(event.x, event.y, true)
                    return true
                }
                Drag.NONE -> {}
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = drag
                drag = Drag.NONE
                dragCell = -1L
                parent?.requestDisallowInterceptTouchEvent(false)
                if (was != Drag.NONE) {
                    if (core?.hasSelection() == true) startActionMode() else finishActionMode()
                    return true
                }
            }
        }
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1) {
            twoFingerTouch(event)
            return true
        }
        gestures.onTouchEvent(event)
        return true
    }

    /**
     * Two fingers on the glass: a pinch, or a drag that walks the cursor.
     *
     * They start out the same shape, so the gesture is left undecided until one
     * of the two things it could be actually happens. The span growing or
     * shrinking past [PINCH_SPAN_RATIO] is a pinch — that test comes first, and
     * so does a pinch the detector has already begun, because a zoom that gets
     * taken away mid-squeeze is worse than an arrow key that never arrives.
     * Anything else that moves the pair as a whole is the drag.
     *
     * This deliberately sits above mouse reporting: htop and vim ask for drags
     * and would swallow the gesture, and moving the cursor in vim without
     * hunting for the arrow caps is exactly what this is for.
     *
     * Called for every finger movement, so nothing here allocates.
     */
    private fun twoFingerTouch(event: MotionEvent) {
        val span = twoFingerSpan(event)
        val cx = (event.getX(0) + event.getX(1)) / 2f
        val cy = (event.getY(0) + event.getY(1)) / 2f
        if (twoFinger == TwoFinger.NONE) {
            // The second finger rarely lands at the same moment as the first,
            // so the span and the centroid are taken from here, not from where
            // the gesture began.
            twoFinger = if (twoFingerDragArrows && !scaling) TwoFinger.PENDING else TwoFinger.PINCH
            twoFingerSpan = span
            twoFingerX = cx
            twoFingerY = cy
            return
        }
        if (twoFinger == TwoFinger.PENDING) {
            val stretched = twoFingerSpan > 0f && abs(span - twoFingerSpan) / twoFingerSpan > PINCH_SPAN_RATIO
            twoFinger = when {
                scaling || stretched -> TwoFinger.PINCH
                abs(cx - twoFingerX) > twoFingerSlop || abs(cy - twoFingerY) > twoFingerSlop -> {
                    // From where the pair is now, not from where it landed: the
                    // slop already spent would otherwise arrive as a jump.
                    twoFingerDrag.start(cx, cy, cellW, cellH)
                    TwoFinger.ARROWS
                }
                else -> TwoFinger.PENDING
            }
            return
        }
        if (twoFinger == TwoFinger.ARROWS && event.actionMasked == MotionEvent.ACTION_MOVE) {
            if (twoFingerDrag.move(cx, cy, arrowOut)) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun twoFingerSpan(event: MotionEvent): Float =
        hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    /**
     * A joystick drag on the NAV cap, in the bar's own pixels.
     *
     * The steps are the terminal's cells rather than the cap's, because the cap
     * is a fingertip wide and a cursor that crossed a line per millimetre would
     * be unusable. Same [ArrowDrag] as the two-finger gesture, so the two feel
     * alike.
     */
    fun navDragStart(x: Float, y: Float) = navDrag.start(x, y, cellW, cellH)

    fun navDragMove(x: Float, y: Float) {
        if (navDrag.move(x, y, arrowOut)) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // ---- selection / clipboard -------------------------------------------

    fun copySelection(): Boolean {
        val s = session ?: return false
        val text = core?.selectedText() ?: return false
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        core?.selectClear()
        finishActionMode()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
        }
        return true
    }

    fun paste() {
        val s = session ?: return
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip ?: return
        // A screenshot or a download copied from another app arrives as a Uri
        // rather than as text. Pasting the Uri itself would type
        // "content://media/…" into a shell that cannot open it, so the file is
        // sent to the host instead and its path typed in its place.
        val files = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (files.isNotEmpty()) {
            if (onFilesDropped?.invoke(files) == true) {
                finishActionMode()
                return
            }
            // Nowhere to send it — a local shell has no host to upload to. What
            // is left is the Uri's own text, and typing "content://media/…" at a
            // prompt looks far more like a bug than saying so does.
            if (clip.getItemAt(0).text == null) {
                android.widget.Toast
                    .makeText(context, "That was copied as a file, and this session has no host to send it to", android.widget.Toast.LENGTH_LONG)
                    .show()
                finishActionMode()
                return
            }
        }
        val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        pasteText(text)
        core?.selectClear()
        finishActionMode()
    }

    /**
     * Text the app already has in hand — a composed line — sent the way a paste
     * is, so a program that asked for bracketed paste is told this arrived in
     * one piece rather than typed.
     */
    fun pasteText(text: String) {
        sink.paste(text)
    }

    /**
     * Files dropped on the terminal, or pasted into it as Uris. Answering true
     * means they were taken care of; the caller sends them to the host and
     * types the paths back.
     */
    var onFilesDropped: ((List<android.net.Uri>) -> Boolean)? = null

    /**
     * A drop from another app, with the event still in hand: reading those Uris
     * needs a permission that only the activity can ask for, and only while the
     * event is alive.
     */
    var onDrop: ((android.view.DragEvent, List<android.net.Uri>) -> Boolean)? = null

    fun selectAll() {
        core?.selectAll()
        startActionMode()
    }

    /**
     * Select whole rows [firstRow] to [lastRow] and copy them.
     *
     * The rows are the core's, counted from the top visible line, so both ends
     * may be off the screen — the output of one command is regularly taller
     * than the screen, and older than it. The selection API speaks in visible
     * rows, so the viewport is walked to each end in turn to place it there:
     * the selection itself is anchored to the buffer rather than to the screen,
     * so it survives the trip, and the view is put back where it was.
     */
    fun copyRows(firstRow: Int, lastRow: Int): Boolean {
        val core = core ?: return false
        val was = core.displayOffset().toInt()
        val history = core.historySize().toInt()
        // Scroll `row` to the top of the screen, as far as the buffer allows,
        // and say where it ended up.
        fun show(row: Int): Int {
            val offset = (was - row).coerceIn(0, history)
            core.scrollToOffset(offset.toUInt())
            return (row - was + offset).coerceIn(0, rows - 1)
        }
        core.selectStart(0u, show(firstRow).toUShort(), SelectKind.SIMPLE)
        core.selectUpdate((cols - 1).coerceAtLeast(0).toUShort(), show(lastRow).toUShort(), false)
        val copied = copySelection()
        core.scrollToOffset(was.toUInt())
        return copied
    }

    fun clearSelection() {
        core?.selectClear()
        finishActionMode()
    }

    /**
     * Turn what is selected into a rectangle: the same rows, but only the
     * columns between the two ends.
     *
     * This is how one column comes out of `docker ps` or `ls -l` — the names
     * without the ids, the sizes without the dates — and the copied text keeps
     * its columns because every row is cut at the same two places.
     *
     * On the toolbar rather than under a two-finger long press: a second finger
     * on this view already means a pinch or the arrow-key drag, so the gesture
     * would have to be taken away from one of them, and a gesture nobody is
     * told about is one nobody finds. The handles go on working afterwards —
     * the core drags a block selection exactly as it drags a flowing one.
     */
    fun columnSelect() {
        if (!hasSelection) return
        // Both ends can sit off the top of a screen that has been scrolled;
        // the core takes view coordinates, which start at the first row.
        val startRow = selStartRow.coerceAtLeast(0)
        val endRow = selEndRow.coerceAtLeast(0)
        core?.selectStart(selStartCol.coerceAtLeast(0).toUShort(), startRow.toUShort(), SelectKind.BLOCK)
        core?.selectUpdate(selEndCol.coerceAtLeast(0).toUShort(), endRow.toUShort(), false)
        actionMode?.invalidate()
    }

    private fun startActionMode() {
        if (actionMode != null) {
            actionMode?.invalidate()
            return
        }
        actionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, 1, 0, "Copy").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0, 2, 1, "Paste").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add(0, 3, 2, "Select all").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(0, 4, 3, "Share").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(0, 5, 4, "Column select").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    1 -> copySelection()
                    2 -> paste()
                    3 -> core?.selectAll()
                    4 -> {
                        val text = core?.selectedText() ?: return true
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, null))
                        clearSelection()
                    }
                    5 -> columnSelect()
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                if (!hasSelection) {
                    outRect.set(0, 0, width, height)
                    return
                }
                val ox = paddingLeft + paddingPx
                val oy = paddingTop + paddingPx
                val top = (oy + selStartRow.coerceIn(0, snapRows - 1) * cellH).toInt()
                val bottom = (oy + (selEndRow.coerceIn(0, snapRows - 1) + 1) * cellH + handleRadius * 2).toInt()
                val left = if (selStartRow == selEndRow) (ox + selStartCol * cellW).toInt() else ox.toInt()
                val right = if (selStartRow == selEndRow) (ox + (selEndCol + 1) * cellW).toInt() else width
                outRect.set(left, top, right, bottom)
            }
        }, ActionMode.TYPE_FLOATING)
    }

    private fun finishActionMode() {
        actionMode?.finish()
        actionMode = null
    }

    // ---- keyboard ---------------------------------------------------------

    /**
     * Told when the terminal wants a keyboard up or down, so the screen can
     * raise the app's own instead of the system one.
     */
    var onKeyboardWanted: ((Boolean) -> Unit)? = null

    fun showKeyboard() {
        requestFocus()
        if (settings.builtInKeyboard) {
            // Ours is a view on the screen, not an input method, so asking the
            // system for one as well would put two keyboards on the phone.
            hideKeyboard()
            onKeyboardWanted?.invoke(true)
            return
        }
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun toggleKeyboard() {
        if (settings.builtInKeyboard) {
            onKeyboardWanted?.invoke(!builtInKeyboardUp)
            return
        }
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        if (imm.isAcceptingText && imm.isActive(this)) hideKeyboard() else showKeyboard()
    }

    /** Whether the app's own keyboard is on screen, as the screen last reported. */
    var builtInKeyboardUp: Boolean = false

    /**
     * Coming back from a screenshot, a share sheet or the recents list, the
     * framework puts the system keyboard back up for whatever had focus. With
     * this app's own keyboard already drawn on the screen that is two
     * keyboards, so it goes away again — posted, because the restore happens
     * after focus is handed back.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus || !settings.builtInKeyboard || !isFocused) return
        post {
            if (settings.builtInKeyboard && isFocused) {
                context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
            }
        }
    }

    fun hideKeyboard() {
        onKeyboardWanted?.invoke(false)
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    /**
     * Whether the system should offer an input method for this view.
     *
     * No, while the app draws a keyboard of its own: this is what the framework
     * asks before putting the system keyboard back up for the focused view, and
     * answering yes is how coming back from another app ended up with the wrong
     * keyboard, or with two. The compose line is a real text field and answers
     * for itself.
     */
    override fun onCheckIsTextEditor(): Boolean = !settings.builtInKeyboard

    /**
     * The keyboard's own way in, and the only one that can hand over a picture.
     *
     * Gboard's clipboard keeps screenshots and GIFs, and it delivers them as
     * content rather than as text — a keyboard cannot type a PNG. Declaring
     * that the terminal accepts images is what makes that key light up at all;
     * what arrives then takes the road files dropped on the terminal already
     * take, up to the host with its path typed at the cursor.
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        EditorInfoCompat.setContentMimeTypes(outAttrs, IMAGE_MIME_TYPES)
        val connection = object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                sendText(text.toString())
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                // Some IMEs compose even with TYPE_NULL; commit happens through commitText.
                return super.setComposingText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(max(1, beforeLength)) { sendKey(KeyCode.Backspace) }
                repeat(afterLength) { sendKey(KeyCode.Delete) }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                sendKey(KeyCode.Enter)
                return true
            }
        }
        return InputConnectionCompat.createWrapper(connection, outAttrs) { content, flags, _ ->
            // The Uri belongs to the keyboard's process until it is asked for;
            // the grant is deliberately not released, because the upload reads
            // the file on its own thread long after this call has returned.
            if (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) {
                if (runCatching { content.requestPermission() }.isFailure) return@createWrapper false
            }
            onFilesDropped?.invoke(listOf(content.contentUri)) == true
        }
    }

    /** Text from the IME. Applies sticky modifiers to single characters. */
    fun sendText(text: String) {
        val s = session ?: return
        keepCursorLit()
        val mods = modifierState.value
        if (text.length == 1 && (mods.ctrl != ModState.OFF || mods.alt != ModState.OFF || mods.shift != ModState.OFF)) {
            var ch = text[0]
            if (mods.shift != ModState.OFF && ch.isLetter()) ch = ch.uppercaseChar()
            sendPress(KeyCode.Char(ch.code.toUInt()), mods.ctrl != ModState.OFF, mods.alt != ModState.OFF, false, KeyEventKind.PRESS)
            consumeOnceModifiers()
        } else if (text == "\n") {
            sendKey(KeyCode.Enter)
        } else {
            sink.text(text)
        }
    }

    /**
     * The color a suggestion is drawn in: mixed between the scheme's own
     * background and foreground rather than drawn with an alpha.
     *
     * Alpha over an unknown background is how a hint ends up invisible on one
     * color scheme and shouting on another. Mixing means it always lands
     * between the two, so it reads as "not typed yet" on a light scheme and a
     * dark one alike.
     */
    private fun ghostColor(): Int {
        val bg = backgroundColorInt
        val fg = foregroundColorInt
        fun mix(shift: Int): Int {
            val b = (bg shr shift) and 0xff
            val f = (fg shr shift) and 0xff
            return (b + (f - b) * GHOST_MIX).toInt().coerceIn(0, 255)
        }
        return 0xff000000.toInt() or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /**
     * Where the cursor is drawn, in this view's pixels: left, top and the
     * height of one cell. Null when there is no grid yet.
     *
     * Anything the app wants to put *next to what is being typed* — the list of
     * suggestions — has to ask, because only the view knows where the cell grid
     * ended up after padding and rounding.
     */
    fun cursorPosition(): FloatArray? {
        if (cursorRow < 0 || cursorCol < 0 || cellH <= 0f) return null
        return floatArrayOf(
            paddingLeft + paddingPx + cursorCol * cellW,
            paddingTop + paddingPx + cursorRow * cellH,
            cellH,
        )
    }

    fun sendKey(key: KeyCode, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false, kind: KeyEventKind = KeyEventKind.PRESS) {
        val s = session ?: return
        val press = kind != KeyEventKind.RELEASE
        if (press) keepCursorLit()
        // Plain Tab only: Ctrl+Tab and friends are the app's own chords, and a
        // modified Tab was never meant for the suggestion.
        if (press && key == KeyCode.Tab && !ctrl && !alt && !shift && modifierState.value.let { it.ctrl == ModState.OFF && it.alt == ModState.OFF }) {
            if (onAcceptGhost?.invoke() == true) return
        }
        if (press && key == KeyCode.Enter && !ctrl && !alt) {
            onCommandEntered?.invoke()
        }
        val mods = modifierState.value
        val wantsCtrl = ctrl || mods.ctrl != ModState.OFF
        val wantsAlt = alt || mods.alt != ModState.OFF
        val wantsShift = shift || mods.shift != ModState.OFF
        sendPress(key, wantsCtrl, wantsAlt, wantsShift, kind)
        // A sticky modifier is spent by the press; letting go of the key is not
        // a second keystroke.
        if (press) consumeOnceModifiers()
    }

    /**
     * The C0 byte a Ctrl+letter stands for, when that is what should go out
     * instead of a key event.
     *
     * Ctrl+C from the cap above the keyboard is the only interrupt a phone
     * has. Under the keyboard protocol it would leave as `CSI 99;5u`, which is
     * correct and which a wedged program will never read, so the byte goes
     * instead. Five letters are left alone: their control code *is* another
     * key — Ctrl+I is Tab, Ctrl+M is Enter, Ctrl+H is Backspace, Ctrl+J is a
     * line feed, Ctrl+[ is Escape — and telling those apart is the whole
     * reason the protocol exists. Nothing changes while it is switched off,
     * where the encoder already produces exactly these bytes.
     */
    /**
     * The one place a press leaves by. Keys arrive here from three directions —
     * the key bar, the soft keyboard with a sticky modifier, and a hardware
     * keyboard — and a rule applied to only one of them is a rule that holds
     * until somebody presses the same key a different way.
     */
    private fun sendPress(key: KeyCode, ctrl: Boolean, alt: Boolean, shift: Boolean, kind: KeyEventKind) {
        val raw = if (kind != KeyEventKind.RELEASE && settings.rawControlKeys) controlByte(key, ctrl, alt, shift) else null
        if (raw != null) sink.text(raw.toString()) else sink.key(KeyPress(key, ctrl, alt, shift, kind))
    }

    private fun controlByte(key: KeyCode, ctrl: Boolean, alt: Boolean, shift: Boolean): Char? {
        if (!ctrl || alt || shift) return null
        val cp = (key as? KeyCode.Char)?.codepoint?.toInt() ?: return null
        val letter = cp.toChar().lowercaseChar()
        if (letter !in 'a'..'z' || letter in AMBIGUOUS_CONTROL) return null
        return (letter - 'a' + 1).toChar()
    }

    /**
     * Kitty keyboard flags the program has asked for, or 0 when it has asked
     * for nothing — which is also what the core reports while the protocol is
     * switched off for this host.
     *
     * As of the last frame, out of its header: asked over uniffi instead, it
     * was a round trip on every key that came back up.
     */
    private fun kittyFlags(): Int = snapKittyFlags

    /** A tap on a modifier cap, or a long press when [lock]. */
    fun toggleModifier(which: Char, lock: Boolean = false) {
        modifierState.doubleTapLocks = settings.doubleTapLocksModifier
        if (lock) modifierState.lock(which) else modifierState.tap(which)
    }

    fun setModifier(which: Char, state: ModState) = modifierState.set(which, state)

    private fun consumeOnceModifiers() = modifierState.consumeOnce()

    /** Volume key bindings: extra-keys tokens or "NONE"; see [ExtraKeys]. */
    var volumeDownAction: String = "NONE"
    var volumeUpAction: String = "NONE"
    var volumeDownMode: dev.flint.term.data.VolumeModifierMode = dev.flint.term.data.VolumeModifierMode.STICKY
    var volumeUpMode: dev.flint.term.data.VolumeModifierMode = dev.flint.term.data.VolumeModifierMode.STICKY
    /**
     * The rest of a command from this host's history, drawn where the cursor is
     * in a dim color — the shell's own trick, without needing the shell's help.
     * Set by the screen; null means nothing is being offered.
     */
    var ghost: String? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }


    /**
     * Asked before Tab reaches the host. Returning true means the suggestion was
     * taken and the shell should never see the keystroke — which is what makes
     * Tab feel like "accept" while there is something to accept, and like tab
     * completion the rest of the time.
     */
    var onAcceptGhost: (() -> Boolean)? = null

    /** Two taps send Tab, the way Termius does it. Off unless the app says so. */
    var doubleTapSendsTab: Boolean = false

    /** Two fingers dragged together walk the cursor. Off unless the app says so. */
    var twoFingerDragArrows: Boolean = false

    /**
     * How far a drag scrolls, as a multiple of the finger's own travel.
     *
     * Clamped rather than trusted: this arrives from the settings file, and a
     * zero or a negative there would leave the scrollback unreachable or
     * upside down with nothing on screen to say why.
     */
    var scrollSpeed: Float = 1f
        set(value) {
            field = value.coerceIn(MIN_SCROLL_SPEED, MAX_SCROLL_SPEED)
        }

    /** Hooks for actions that live in the Compose layer. */
    var onSnippets: (() -> Unit)? = null
    var onSearch: (() -> Unit)? = null
    /** Open the file picker; what is picked goes to the host, as if dropped. */
    var onInsertFile: (() -> Unit)? = null
    /** Open the compose line, the field a long prompt is written in. */
    var onCompose: (() -> Unit)? = null

    /**
     * A hardware-keyboard chord the app claims; see [KeyShortcuts]. Returns
     * whether it was acted on, because a chord that finds nothing to do — copy
     * with no selection — has to reach the remote after all.
     */
    var onShortcut: ((KeyShortcuts.Shortcut) -> Boolean)? = null
    /** Modifier currently held down through a volume key (HOLD mode). */
    private var heldModifier: Char? = null

    /** Handle a volume key press; returns true when it was bound to something. */
    private fun volumeKey(action: String, mode: dev.flint.term.data.VolumeModifierMode, down: Boolean, repeat: Boolean): Boolean {
        if (action == "NONE" || action.isBlank()) return false
        val def = ExtraKeys.resolve(action)
        when (val a = def.action) {
            is ExtraKeys.Action.Modifier -> {
                when (mode) {
                    dev.flint.term.data.VolumeModifierMode.STICKY -> if (down && !repeat) toggleModifier(a.which)
                    dev.flint.term.data.VolumeModifierMode.TOGGLE -> if (down && !repeat) {
                        val cur = when (a.which) { 'c' -> modifierState.value.ctrl; 'a' -> modifierState.value.alt; else -> modifierState.value.shift }
                        if (cur == ModState.LOCKED) setModifier(a.which, ModState.OFF) else setModifier(a.which, ModState.LOCKED)
                    }
                    dev.flint.term.data.VolumeModifierMode.HOLD -> if (down) {
                        if (!repeat) { heldModifier = a.which; setModifier(a.which, ModState.LOCKED) }
                    } else {
                        heldModifier = null; setModifier(a.which, ModState.OFF)
                    }
                }
            }
            is ExtraKeys.Action.Key -> if (down) sendKey(a.code, a.ctrl, a.alt, a.shift)
            is ExtraKeys.Action.Text -> if (down && !repeat) sendText(a.text)
            ExtraKeys.Action.Snippets -> if (down && !repeat) onSnippets?.invoke()
            ExtraKeys.Action.Search -> if (down && !repeat) onSearch?.invoke()
            ExtraKeys.Action.ToggleKeyboard -> if (down && !repeat) toggleKeyboard()
            ExtraKeys.Action.Paste -> if (down && !repeat) paste()
            ExtraKeys.Action.InsertFile -> if (down && !repeat) onInsertFile?.invoke()
            ExtraKeys.Action.Compose -> if (down && !repeat) onCompose?.invoke()
            // A volume key has nowhere to drag to.
            ExtraKeys.Action.Nav -> return false
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val bound = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> volumeDownAction to volumeDownMode
            KeyEvent.KEYCODE_VOLUME_UP -> volumeUpAction to volumeUpMode
            else -> null
        }
        if (bound != null && volumeKey(bound.first, bound.second, down = false, repeat = false)) return true
        if (capsLock(keyCode, down = false)) return true
        sendRelease(keyCode, event)
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Tell the program the key came back up.
     *
     * Only a program that turned on kitty event types has anywhere to put a
     * release, and only a hardware keyboard produces one — a soft keyboard
     * commits text and never says a key stopped being held — so this is silent
     * the rest of the time.
     */
    private fun sendRelease(keyCode: Int, event: KeyEvent) {
        val flags = kittyFlags()
        if (flags and 2 == 0) return
        val modifier = HardwareKeyMap.modifier(keyCode)
        val key = when {
            modifier != null -> if (flags and 8 != 0) KeyCode.Modifier(modifier) else return
            else -> HardwareKeyMap.special(keyCode) ?: run {
                val ch = event.getUnicodeChar(HardwareKeyMap.unicodeMeta(event.metaState, settings.capsLockAs))
                if (ch == 0 || ch and KeyCharacterMap.COMBINING_ACCENT != 0) return
                KeyCode.Char(ch.toUInt())
            }
        }
        sink.key(KeyPress(key, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, KeyEventKind.RELEASE))
    }

    /**
     * Caps Lock in whatever job it has been given; returns whether it had one.
     *
     * As Control it is held, not sent, so it works the way the volume keys in
     * HOLD mode do: down locks the modifier, up lets it go.
     */
    private fun capsLock(keyCode: Int, down: Boolean): Boolean {
        return when (val action = HardwareKeyMap.capsLock(keyCode, down, settings.capsLockAs)) {
            is HardwareKeyMap.CapsAction.Send -> { sendKey(action.code); true }
            HardwareKeyMap.CapsAction.HoldControl -> { setModifier('c', ModState.LOCKED); true }
            HardwareKeyMap.CapsAction.ReleaseControl -> { setModifier('c', ModState.OFF); true }
            HardwareKeyMap.CapsAction.Swallow -> true
            HardwareKeyMap.CapsAction.None -> false
        }
    }


    /** (row, col, length) of the current search hit in viewport coordinates, or null. */
    var searchHighlight: Triple<Int, Int, Int>? = null
        set(value) { field = value; postInvalidateOnAnimation() }
    private val searchPaint = Paint().apply { color = 0x66FFD54F }

    /** Type text and press Enter. */
    fun sendLine(text: String) {
        sendText(text)
        sendKey(KeyCode.Enter)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return super.onKeyDown(keyCode, event)
        val bound = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> volumeDownAction to volumeDownMode
            KeyEvent.KEYCODE_VOLUME_UP -> volumeUpAction to volumeUpMode
            else -> null
        }
        if (bound != null && volumeKey(bound.first, bound.second, down = true, repeat = event.repeatCount > 0)) return true
        if (capsLock(keyCode, down = true)) return true
        // Before the key is translated: Ctrl+Shift+Tab is a chord here and a Tab below.
        val chord = KeyShortcuts.resolve(keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, settings.shortcuts)
        if (chord != null && onShortcut?.invoke(chord) == true) return true
        // The keyboard repeating a held key is its own event type; without the
        // kitty flag for it the core encodes it as another press.
        val kind = if (event.repeatCount > 0) KeyEventKind.REPEAT else KeyEventKind.PRESS
        val modifier = HardwareKeyMap.modifier(keyCode)
        if (modifier != null) {
            // Nothing but a program asking for every key has a use for a
            // modifier struck on its own; the platform still gets the event,
            // because it is what keeps the meta state honest.
            if (kittyFlags() and 8 != 0) {
                sink.key(
                    KeyPress(KeyCode.Modifier(modifier), event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, kind),
                )
            }
            return super.onKeyDown(keyCode, event)
        }
        val special = HardwareKeyMap.special(keyCode)
        if (special != null) {
            sendKey(special, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, kind)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyDown(keyCode, event)
        }
        val ch = event.getUnicodeChar(HardwareKeyMap.unicodeMeta(event.metaState, settings.capsLockAs))
        if (ch != 0 && ch and KeyCharacterMap.COMBINING_ACCENT == 0) {
            keepCursorLit()
            sendPress(
                KeyCode.Char(ch.toUInt()),
                event.isCtrlPressed || modifierState.value.ctrl != ModState.OFF,
                event.isAltPressed || modifierState.value.alt != ModState.OFF,
                false,
                kind,
            )
            consumeOnceModifiers()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Both of them, and on the way in: being attached is what makes a view
        // the visible one, so this is where a session changes hands when a
        // floating window opens over a screen or closes back into it.
        session?.addDamageListener(onDamage)
        post { requestFocus() }
        scheduleBlink()
    }

    override fun onDetachedFromWindow() {
        session?.removeDamageListener(onDamage)
        removeCallbacks(blinkTick)
        removeCallbacks(applyPendingGrid)
        dropBitmaps()
        dropRowNodes()
        finishActionMode()
        releaseSessionHandle()
        if (bufferHandle != 0L) {
            FrameBridge.releaseBuffer(bufferHandle)
            bufferHandle = 0L
        }
        super.onDetachedFromWindow()
    }

    /**
     * Behind another screen, or scrolled out of the pager: the timer stops, so
     * a session nobody can see costs nothing.
     */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (onScreen == isVisible) return
        onScreen = isVisible
        if (isVisible) cursorLit = true
        scheduleBlink()
    }

    companion object {
        /** How far a suggestion sits from the background towards the text color. */
        private const val GHOST_MIX = 0.55f

        private const val DEBUG_TOUCH = false

        /** How far apart two fingers may drift and still count as held together. */
        private const val PINCH_SPAN_RATIO = 0.15f

        /** What the keyboard may hand over besides text; GIFs count as images. */
        private val IMAGE_MIME_TYPES = arrayOf("image/*")

        /** Ctrl+these are another key's byte, so they stay a key event. */
        private val AMBIGUOUS_CONTROL = setOf('i', 'm', 'h', 'j')

        /** No single glyph in this font for that character. */
        private const val MISSING = -1

        /**
         * Stands in for "this font has no single glyph for that character".
         *
         * A SparseArray cannot tell a stored null from an absent key, and the
         * whole point of the entry is to stop the shaper being asked again.
         */
        private val MISSING_GLYPH = Any()

        /** Half a blink, the rate xterm has used since forever. */
        private const val BLINK_MS = 530L

        /** How early [requestFrame] may let a capped frame through, to catch the vsync it wants. */
        private const val VSYNC_SLACK_MS = 4L

        /** The most frames a second streaming output is drawn at; see [requestFrame]. */
        private const val STREAMING_FPS = 60

        /**
         * Damage this soon after the last frame counts as a stream rather than
         * a change on its own. A few frames at any refresh rate, and far below
         * the gap between two keystrokes.
         */
        private const val STREAMING_GAP_MS = 50L

        /**
         * How long a new size has to hold before the grid is reshaped to it.
         * Long enough to swallow a keyboard animation, which is a few hundred
         * milliseconds of a new size every frame; short enough that letting go
         * of a pinch does not feel like waiting.
         */
        private const val RESIZE_SETTLE_MS = 110L
        const val MIN_SP = 6f
        const val MAX_SP = 32f

        /**
         * The far ends of the scroll-speed knob. Wider than the slider offers,
         * because these exist to keep a hand-edited settings file from making
         * the scrollback unusable, not to second-guess the screen.
         */
        const val MIN_SCROLL_SPEED = 0.25f
        const val MAX_SCROLL_SPEED = 5f
        private const val LINE_SPACING = 1.0f
        private const val FLAG_BOLD = 1
        private const val FLAG_ITALIC = 1 shl 1
        private const val FLAG_UNDERLINE = 1 shl 2
        private const val FLAG_STRIKEOUT = 1 shl 3
        private const val FLAG_DIM = 1 shl 4
        private const val FLAG_WIDE = 1 shl 5
        private const val FLAG_WIDE_SPACER = 1 shl 6
        private const val FLAG_SELECTED = 1 shl 7
        private const val FLAG_HIDDEN = 1 shl 8
        private const val FLAG_DOUBLE_UNDERLINE = 1 shl 9
        private const val FLAG_UNDERCURL = 1 shl 10
        private const val DECORATIONS = FLAG_UNDERLINE or FLAG_STRIKEOUT or FLAG_DOUBLE_UNDERLINE or FLAG_UNDERCURL
        private const val STYLE_MASK = FLAG_BOLD or FLAG_ITALIC or FLAG_UNDERLINE or FLAG_STRIKEOUT or FLAG_DOUBLE_UNDERLINE or FLAG_UNDERCURL
    }
}

private typealias KeyCharacterMap = android.view.KeyCharacterMap

/**
 * Last grid size any terminal view laid out with. New sessions start at this
 * size so their first output (the connection log) does not have to be reflowed
 * — alacritty pushes wrapped rows into scrollback on a column shrink.
 */
object GridMemory {
    @Volatile var cols: Int = 0
        private set
    @Volatile var rows: Int = 0
        private set

    fun remember(c: Int, r: Int) {
        if (c >= 10 && r >= 4) { cols = c; rows = r }
    }
}

