package dev.flint.term.terminal

import android.graphics.RenderNode
import androidx.annotation.RequiresApi

/**
 * Recorded rows of the grid, keyed on what is in them rather than where they
 * are.
 *
 * Most frames change a line or two, and a frame that scrolls moves every row
 * up by one without changing any of them. Recording a row once and drawing
 * the recording again costs a translate and a pointer; walking its cells again
 * cost the text pass its shaping, batching and hundreds of draw calls. The key
 * is the row hash the core packs next to the cells, so a line that scrolled
 * finds the recording it had one row further down.
 *
 * A fixed set of slots, reused least recently used first, and scanned rather
 * than hashed: a handful of rows are looked up in a frame that changed some,
 * the scan is over a few hundred longs, and nothing here boxes a key. Each
 * slot keeps its [RenderNode] for life, so evicting one re-records into the
 * same object instead of making another.
 */
@RequiresApi(29)
internal class RowNodes {
    private var keys = LongArray(0)
    private var filled = BooleanArray(0)
    private var inked = BooleanArray(0)
    private var used = LongArray(0)
    private var nodes = arrayOfNulls<RenderNode>(0)
    /** The slot each row was drawn from last frame, the one worth trying first. */
    private var atRow = IntArray(0)
    private var tick = 0L

    /**
     * Room for [rows] rows, several screens' worth.
     *
     * More than one screen is not only for scrolling back and forth. A frame
     * draws at most [rows] slots, so with three times that the least recently
     * used slot is never one this frame has drawn already — re-recording that
     * one would change a row above this one that has already been put down.
     */
    fun fit(rows: Int) {
        val capacity = rows * 3 + 4
        if (keys.size == capacity && atRow.size == rows) return
        clear()
        keys = LongArray(capacity)
        filled = BooleanArray(capacity)
        inked = BooleanArray(capacity)
        used = LongArray(capacity)
        nodes = arrayOfNulls(capacity)
        atRow = IntArray(rows) { -1 }
    }

    /** The slot already holding [key] for [row], or -1 when it has to be recorded. */
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

    /**
     * A slot to record [key] into for [row]: an empty one, or the one drawn
     * longest ago. Its node's old recording is dropped.
     */
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
        val node = nodes[slot] ?: RenderNode("row").also {
            // Ink that pokes out of a cell — a descender, an accent, italic
            // overhang — is drawn in the row next to it today, so a row is
            // not cut off at its own edges.
            it.setClipToBounds(false)
            nodes[slot] = it
        }
        node.discardDisplayList()
        keys[slot] = key
        filled[slot] = true
        inked[slot] = false
        used[slot] = ++tick
        atRow[row] = slot
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
        for (i in nodes.indices) {
            nodes[i]?.discardDisplayList()
            filled[i] = false
        }
        atRow.fill(-1)
    }
}
