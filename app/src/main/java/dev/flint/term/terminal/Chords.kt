package dev.flint.term.terminal

import dev.flint.term.data.Chord

/**
 * The chords sheet's built-in set, and the tabs it is sorted into.
 *
 * These are what `Settings.chords` holds when the user has never touched it.
 * The tmux ones are written with the word `prefix` rather than `C-b` so that a
 * host which rebound its prefix gets its own key: see [ChordParser].
 */
object Chords {
    const val TMUX = "tmux"
    const val CTRL = "ctrl"
    const val AGENT = "agent"

    /** The tabs in the order the sheet shows them, with the name on the tab. */
    val tabs: List<Pair<String, String>> = listOf(TMUX to "tmux", CTRL to "Ctrl", AGENT to "Agent")

    fun tabLabel(tab: String): String = tabs.firstOrNull { it.first == tab }?.second ?: tab

    val defaults: List<Chord> = listOf(
        Chord("tmux-new", "New window", "prefix c", TMUX),
        Chord("tmux-next", "Next", "prefix n", TMUX),
        Chord("tmux-prev", "Previous", "prefix p", TMUX),
        Chord("tmux-last", "Last", "prefix l", TMUX),
        Chord("tmux-detach", "Detach", "prefix d", TMUX),
        Chord("tmux-split-h", "Split horizontally", "prefix %", TMUX),
        Chord("tmux-split-v", "Split vertically", "prefix \"", TMUX),
        Chord("tmux-zoom", "Zoom", "prefix z", TMUX),
        Chord("tmux-kill", "Kill pane", "prefix x", TMUX),
        Chord("tmux-copy", "Copy mode", "prefix [", TMUX),
    ) + (0..9).map { n -> Chord("tmux-window-$n", "Window $n", "prefix $n", TMUX) } + listOf(
        Chord("ctrl-c", "Ctrl+C", "C-c", CTRL),
        Chord("ctrl-d", "Ctrl+D", "C-d", CTRL),
        Chord("ctrl-z", "Ctrl+Z", "C-z", CTRL),
        Chord("ctrl-l", "Ctrl+L", "C-l", CTRL),
        Chord("ctrl-a", "Ctrl+A", "C-a", CTRL),
        Chord("ctrl-e", "Ctrl+E", "C-e", CTRL),
        Chord("ctrl-r", "Ctrl+R", "C-r", CTRL),
        Chord("ctrl-w", "Ctrl+W", "C-w", CTRL),
        Chord("ctrl-u", "Ctrl+U", "C-u", CTRL),
        Chord("ctrl-k", "Ctrl+K", "C-k", CTRL),
        Chord("agent-clear", "/clear", "/clear Enter", AGENT),
        Chord("agent-compact", "/compact", "/compact Enter", AGENT),
        Chord("agent-resume", "/resume", "/resume Enter", AGENT),
        Chord("agent-help", "/help", "/help Enter", AGENT),
        Chord("agent-stab", "Shift+Tab", "S-Tab", AGENT),
        Chord("agent-esc-esc", "Esc Esc", "Esc Esc", AGENT),
    )
}

/**
 * Send a chord, reporting whether it was one we could read.
 *
 * The sticky modifiers are dropped first. A chord says in full what it sends,
 * and a Ctrl left armed from a tap before the sheet opened would turn the `c`
 * of "new window" into an interrupt — the one keystroke nobody meant.
 */
fun TerminalView.sendChord(keys: String, prefix: String): Boolean {
    val presses = ChordParser.parse(keys, prefix) ?: return false
    setModifier('c', ModState.OFF)
    setModifier('a', ModState.OFF)
    setModifier('s', ModState.OFF)
    presses.forEach { sendKey(it.code, it.ctrl, it.alt, it.shift) }
    return true
}
