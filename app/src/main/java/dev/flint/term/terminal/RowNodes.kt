package dev.flint.term.terminal

import android.graphics.RenderNode
import androidx.annotation.RequiresApi

/**
 * Which of a fixed set of slots holds the recording of which row content.
 *
 * Most frames change a line or two, and a frame that scrolls moves every row
 * up by one without changing any of them. The key is the row hash the core
 * packs next to the cells, so a line that scrolled finds the recording it had
 * one row further down.
 *
 * Slots are reused least recently used first, and scanned rather than hashed:
 * only rows that changed are looked for past the slot they were in last
 * frame, the scan is over a few hundred longs, and nothing here boxes a key.
 * Kept apart from the nodes themselves so the bookkeeping can be tested off a
 * device.
 */
internal class RowSlots {
    private var keys = LongArray(0)
    private var filled = BooleanArray(0)
    private var used = LongArray(0)
    /** The slot each row was drawn from last, the one worth trying first. */
    private var atRow = IntArray(0)
    private var tick = 0L

    val capacity get() = keys.size

    /**
     * Room for [rows] rows, several screens' worth; true when that emptied
     * every slot.
     *
     * More than one screen is not only for scrolling back and forth. A frame
     * draws at most [rows] slots, so with three times that the least recently
     * used slot is never one this frame has drawn already — re-recording that
     * one would change a row above this one that has already been put down.
     */
    fun fit(rows: Int): Boolean {
        val capacity = rows * 3 + 4
        if (keys.size == capacity && atRow.size == rows) return false
        keys = LongArray(capacity)
        filled = BooleanArray(capacity)
        used = LongArray(capacity)
        atRow = IntArray(rows) { -1 }
        return true
    }

    /** The slot already holding [key], for [row], or -1 when it has to be recorded. */
    fun find(row: Int, key: Long): Int {
        tick++
        val last = atRow[row]
        if (last >= 0 && filled[last] && keys[last] == key) {
            used[last] = tick
            return last
        }
        for (i in keys.indices) {
            if (filled[i] && keys[i] == key) {
                used[i] = tick
                atRow[row] = i
                return i
            }
        }
        return -1
    }

    /** A slot for [key] at [row]: an empty one, or the one drawn longest ago. */
    fun claim(row: Int, key: Long): Int {
        var slot = -1
        var oldest = Long.MAX_VALUE
        for (i in keys.indices) {
            if (!filled[i]) {
                slot = i
                break
            }
            if (used[i] < oldest) {
                oldest = used[i]
                slot = i
            }
        }
        keys[slot] = key
        filled[slot] = true
        used[slot] = ++tick
        atRow[row] = slot
        return slot
    }

    /** Forget what [slot] holds, so the next [claim] may take it. */
    fun forget(slot: Int) {
        filled[slot] = false
    }

    fun clear() {
        filled.fill(false)
        atRow.fill(-1)
    }
}

/**
 * Recorded rows of the grid, keyed on what is in them rather than where they
 * are; see [RowSlots].
 *
 * Recording a row once and drawing the recording again costs a translate and
 * a pointer; walking its cells again cost the text pass its shaping, batching
 * and hundreds of draw calls. Each slot keeps its [RenderNode] for life, so
 * evicting one re-records into the same object instead of making another.
 */
@RequiresApi(29)
internal class RowNodes {
    private val slots = RowSlots()
    private var nodes = arrayOfNulls<RenderNode>(0)
    private var inked = BooleanArray(0)

    fun fit(rows: Int) {
        if (!slots.fit(rows)) return
        discard()
        nodes = arrayOfNulls(slots.capacity)
        inked = BooleanArray(slots.capacity)
    }

    /**
     * The slot already holding [key] for [row], or -1 when it has to be
     * recorded.
     *
     * A recording is only as good as the renderer left it. A node that sat
     * out a frame — the prompt row while a command was being typed over it, a
     * menu while another item was lit — counts for the render thread as taken
     * out of the tree, and it frees the node's display list. Drawn again, that
     * node puts down nothing: the row stayed blank until it changed or a
     * resize threw every recording away.
     */
    fun find(row: Int, key: Long): Int {
        val slot = slots.find(row, key)
        if (slot < 0 || !inked[slot] || nodes[slot]!!.hasDisplayList()) return slot
        slots.forget(slot)
        return -1
    }

    /** A slot to record [key] into for [row], its node's old recording dropped. */
    fun claim(row: Int, key: Long): Int {
        val slot = slots.claim(row, key)
        val node = nodes[slot] ?: RenderNode("row").also {
            // Ink that pokes out of a cell — a descender, an accent, italic
            // overhang — has always been drawn into the row next to it, so a
            // row is not cut off at its own edges.
            it.setClipToBounds(false)
            nodes[slot] = it
        }
        node.discardDisplayList()
        inked[slot] = false
        return slot
    }

    fun node(slot: Int): RenderNode = nodes[slot]!!

    /** Whether the slot's recording drew anything; an empty row is not put down at all. */
    fun inked(slot: Int): Boolean = inked[slot]

    fun setInked(slot: Int, value: Boolean) {
        inked[slot] = value
    }

    /** Forget every recording: what they were drawn with has changed. */
    fun clear() {
        slots.clear()
        discard()
    }

    private fun discard() {
        for (node in nodes) node?.discardDisplayList()
    }
}
