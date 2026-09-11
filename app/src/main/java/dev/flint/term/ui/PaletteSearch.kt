package dev.flint.term.ui

/**
 * What the command palette searches over, and how it decides what comes first.
 *
 * Deliberately plain Kotlin: no Compose, no Android, nothing that needs a
 * device to run. The ranking is the part with the judgment in it, so it is the
 * part that has to be testable.
 */
enum class PaletteKind(val label: String) {
    HOST("Hosts"),
    SNIPPET("Snippets"),
    SETTING("Settings"),
    ACTION("Actions"),
}

/**
 * One thing the palette can find.
 *
 * [route] is where picking it goes; [id] names the host or snippet it stands
 * for, since those are looked up in the store rather than navigated to.
 */
data class PaletteEntry(
    val title: String,
    val subtitle: String = "",
    val kind: PaletteKind = PaletteKind.ACTION,
    val route: String = "",
    val id: String = "",
)

/**
 * An entry the query found, with the characters that found it.
 *
 * [titleHits] and [subtitleHits] are offsets into the entry's own strings, so
 * the row can draw exactly those characters bold — which is the only honest
 * way to say why a result is in the list at all.
 */
data class PaletteMatch(
    val entry: PaletteEntry,
    val score: Int,
    val titleHits: List<Int> = emptyList(),
    val subtitleHits: List<Int> = emptyList(),
)

object PaletteSearch {

    /** How many hosts an empty field offers. Enough to be useful, few enough to read. */
    const val RECENT = 6

    // Three tiers, a thousand apart, so nothing in a weaker tier can ever climb
    // over a stronger one however well it scores inside its own.
    private const val TITLE_PREFIX = 3000
    private const val TITLE_SUBSEQUENCE = 2000
    private const val DESCRIPTION = 1000
    private const val TIER = 999

    /**
     * [entries] ranked against [query], best first.
     *
     * An empty query is not "everything": a list of two hundred settings is
     * nothing anyone reads. It is the hosts, most recently connected first,
     * which is what a palette opened by reflex is nearly always for — so
     * [entries] is expected to carry its hosts in that order.
     */
    fun rank(entries: List<PaletteEntry>, query: String, limit: Int = 40): List<PaletteMatch> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            return entries.asSequence()
                .filter { it.kind == PaletteKind.HOST }
                .take(RECENT)
                .map { PaletteMatch(it, 0) }
                .toList()
        }
        return entries.asSequence()
            .mapNotNull { score(it, q) }
            .sortedWith(
                compareByDescending<PaletteMatch> { it.score }
                    .thenBy { it.entry.kind.ordinal }
                    .thenBy { it.entry.title.lowercase() },
            )
            .take(limit)
            .toList()
    }

    /**
     * The same matches under one heading per kind, the kinds ordered by their
     * best result.
     *
     * The list stays a ranked list — whatever the query is really about leads —
     * while a heading still says what each run of rows is.
     */
    fun sections(matches: List<PaletteMatch>): List<Pair<PaletteKind, List<PaletteMatch>>> =
        matches.groupBy { it.entry.kind }
            .toList()
            .sortedByDescending { (_, rows) -> rows.first().score }

    private fun score(entry: PaletteEntry, q: String): PaletteMatch? {
        val title = entry.title.lowercase()
        if (title.startsWith(q)) {
            // Among titles that all begin with what was typed, the shortest is
            // the one that is most nearly the answer.
            val score = (TITLE_PREFIX - title.length).coerceAtLeast(TITLE_PREFIX - TIER)
            return PaletteMatch(entry, score, (0 until q.length).toList())
        }
        subsequence(title, q)?.let { hits ->
            // A tight match is worth more than an early one: "tab" in "Session
            // tabs" is the word, while the t, a and b scattered through "Tell me
            // about the bell" are an accident of spelling.
            val gaps = hits.last() - hits.first() + 1 - q.length
            val score = (TITLE_SUBSEQUENCE - hits.first() * 2 - gaps * 8).coerceAtLeast(TITLE_SUBSEQUENCE - TIER)
            return PaletteMatch(entry, score, hits)
        }
        // Last resort: the words under the title. Somebody who remembers "the
        // one about the wallpaper" and not "Material You" has to be able to
        // find it, and the subsequence runs across both so a query can start in
        // the title and finish in the description.
        val subtitle = entry.subtitle.lowercase()
        if (subtitle.isEmpty()) return null
        val hits = subsequence("$title $subtitle", q) ?: return null
        val gaps = hits.last() - hits.first() + 1 - q.length
        val score = (DESCRIPTION - hits.first() - gaps).coerceAtLeast(DESCRIPTION - TIER)
        val split = entry.title.length
        return PaletteMatch(
            entry,
            score,
            titleHits = hits.filter { it < split },
            subtitleHits = hits.filter { it > split }.map { it - split - 1 },
        )
    }

    /**
     * Where each character of [query] sits in [haystack], or null when they do
     * not all appear in order.
     *
     * Leftmost and greedy: "nrdgl" lands on the n, r and d of Nerd and the g
     * and l of glyphs. A cleverer search could find a tighter set of positions,
     * but the first one found is the one a person reading the bold characters
     * would have picked out anyway.
     */
    private fun subsequence(haystack: String, query: String): List<Int>? {
        val hits = ArrayList<Int>(query.length)
        var from = 0
        for (c in query) {
            val at = haystack.indexOf(c, from)
            if (at < 0) return null
            hits += at
            from = at + 1
        }
        return hits
    }
}

/**
 * Everything the palette can find that is not a host or a snippet.
 *
 * There is no reflection over Compose, so a settings row exists here because
 * somebody wrote it here: **a new setting has to be added to this list, or the
 * palette will not know about it.** The cost of that is one line; the cost of
 * the alternative was remembering which of ten sections a switch lives in.
 */
object PaletteCatalog {

    /** Every row in every settings section, each jumping to the section holding it. */
    fun settings(): List<PaletteEntry> = listOf(
        setting("App theme", "Light, dark, or whatever the system is doing", Routes.SETTINGS_APPEARANCE),
        setting("Material You colors", "Tint the app with your wallpaper palette", Routes.SETTINGS_APPEARANCE),
        setting("Font size", "How big the terminal draws. Pinching changes it on the fly", Routes.SETTINGS_APPEARANCE),
        setting("Font", "The monospaced family the terminal draws with, imported ones included", Routes.SETTINGS_APPEARANCE),
        setting("Ligatures", "Join => -> != into single glyphs", Routes.SETTINGS_APPEARANCE),
        setting("Nerd Font glyphs", "Draw prompt and file icons from the bundled symbols font", Routes.SETTINGS_APPEARANCE),
        setting("Color scheme", "The terminal palette. Hosts and groups can override it", Routes.SETTINGS_APPEARANCE),
        setting("Bold text is bright", "Draw bold text in the brighter colors, the way xterm does", Routes.SETTINGS_APPEARANCE),
        setting("Cursor shape", "Block, bar or underline", Routes.SETTINGS_APPEARANCE),
        setting("Cursor blink", "Pauses while you type and while the terminal is off screen", Routes.SETTINGS_APPEARANCE),
        setting("Highlighting", "Keyword rules that recolor lines as they are drawn", Routes.HIGHLIGHTS),
        setting("Extra keys", "The bar above the keyboard: which keys, in which order", Routes.EXTRA_KEYS),
        setting("Chords", "The tmux, Ctrl and agent keys on the sheet you get by holding Ctrl", Routes.CHORDS),
        setting("Keyboard protocol", "Lets a program see the difference between Ctrl+[ and Escape, and see Shift+Enter at all", Routes.SETTINGS_KEYBOARD),
        setting("Type with the app's keyboard", "A plain layout with Ctrl on the bottom row, drawn in the app", Routes.SETTINGS_KEYBOARD),
        setting("Ctrl keys always send control bytes", "Ctrl+C still interrupts a program that has taken the keyboard over", Routes.SETTINGS_KEYBOARD),
        setting("Keep the compose line open", "The ✎ field and what was being written in it survive leaving the session", Routes.SETTINGS_KEYBOARD),
        setting("Caps Lock acts as", "Escape, Ctrl, or Caps Lock", Routes.SETTINGS_KEYBOARD),
        setting("Double tap locks a modifier", "Tap Ctrl twice and it stays down until you tap it again", Routes.SETTINGS_KEYBOARD),
        setting("Double tap sends Tab", "The key a phone keyboard hides, two taps away", Routes.SETTINGS_KEYBOARD),
        setting("Two-finger drag sends arrows", "Slide two fingers to walk the cursor. Pinch still zooms", Routes.SETTINGS_KEYBOARD),
        setting("Hold Ctrl for chords", "A long press on Ctrl opens the chords sheet. A tap is still the modifier", Routes.SETTINGS_KEYBOARD),
        setting("Swipe between sessions", "Drag sideways in the terminal to move along the tab strip", Routes.SETTINGS_KEYBOARD),
        setting("Scrollback", "How many lines a session keeps behind the screen", Routes.SETTINGS_TERMINAL),
        setting("Redraw limit", "Caps repaints under heavy output to save battery", Routes.SETTINGS_TERMINAL),
        setting("Inline images", "Pictures drawn in the terminal by chafa, timg or kitty's icat", Routes.SETTINGS_TERMINAL),
        setting("Predictive echo", "Mosh can draw a keystroke before the server confirms it", Routes.SETTINGS_TERMINAL),
        setting("Complete from history", "The rest of a command you have run here, in gray after the cursor", Routes.SETTINGS_TERMINAL),
        setting("Tab takes the suggestion", "Only while one is showing. Otherwise Tab is the shell's own completion", Routes.SETTINGS_TERMINAL),
        setting("Session recordings", "Record every session, and in which format", Routes.SETTINGS_TERMINAL),
        setting("Session tabs", "The strip of names under the terminal's bar", Routes.SETTINGS_SESSIONS),
        setting("Reopen sessions", "After the app is killed, what was open is dialled again", Routes.SETTINGS_SESSIONS),
        setting("Keep floating when you leave", "Switching apps leaves the terminal in a small window", Routes.SETTINGS_SESSIONS),
        setting("tmux controls", "The chords sheet, the window list, and the sideways swipe", Routes.SETTINGS_SESSIONS),
        setting("Keep screen on", "While a terminal is open", Routes.SETTINGS_SESSIONS),
        setting("Notify on bell", "When the app is in the background", Routes.SETTINGS_SESSIONS),
        setting("Vibrate on bell", "A buzz to go with it", Routes.SETTINGS_SESSIONS),
        setting("Long command finished", "A notification when a command over 30 seconds ends in the background", Routes.SETTINGS_SESSIONS),
        setting("Let programs raise a notification", "A script on the server can ask for one with an escape sequence", Routes.SETTINGS_SESSIONS),
        setting("Keepalive", "How often an idle session pokes the server", Routes.SETTINGS_CONNECTIONS),
        setting("Data saver", "Holds transfers for Wi-Fi and spaces out keepalives", Routes.SETTINGS_CONNECTIONS),
        setting("Ask before agent signing", "A forwarded key asks before it signs", Routes.SETTINGS_CONNECTIONS),
        setting("Find hosts on this network", "Servers that announce SSH on this network appear under Nearby", Routes.SETTINGS_CONNECTIONS),
        setting("Show the server's message", "What a server prints before login", Routes.SETTINGS_CONNECTIONS),
        setting("Learn the host's shell history", "Read its history so it completes what you type", Routes.SETTINGS_CONNECTIONS),
        setting("Resolver", "Which DNS a tunnelled name is asked of", Routes.SETTINGS_CONNECTIONS),
        setting("Open a text file with", "The built-in editor, or an app on the phone", Routes.SETTINGS_FILES),
        setting("Keep a .bak", "Copy the file on the host before saving over it", Routes.SETTINGS_FILES),
        setting("Show hidden files", "Dotfiles in the SFTP browser", Routes.SETTINGS_FILES),
        setting("Where files dropped on the terminal go", "The folder an upload lands in", Routes.SETTINGS_FILES),
        setting("Back up to a file", "Hosts, keys, snippets and settings, sealed with a passphrase", Routes.SETTINGS_BACKUP),
        setting("Restore from a file", "Adds what the file has. Nothing here is deleted", Routes.SETTINGS_BACKUP),
        setting("App lock", "Fingerprint, face or screen lock before hosts and keys open", Routes.SETTINGS_SECURITY),
        setting("Lock again after", "How long a trip to another app may last", Routes.SETTINGS_SECURITY),
        setting("Let other apps drive sessions", "Tasker, Automate and adb can connect, run a command or disconnect", Routes.SETTINGS_AUTOMATION),
        setting("Recent calls from other apps", "The last ten, with the app that made them", Routes.SETTINGS_AUTOMATION),
        setting("About", "Version, licenses, and what the core is built from", Routes.SETTINGS_ABOUT),
    )

    /** The things the app does that are not a setting. */
    fun actions(): List<PaletteEntry> = listOf(
        action("New host", "Add a server", Routes.hostEdit("new")),
        action("Keys", "SSH keys on this phone: generate, import, replace", Routes.KEYS),
        action("Accounts", "A login several hosts share", Routes.ACCOUNTS),
        action("Groups", "Folders that hand their hosts a jump host, a VPN, a proxy, an account", Routes.GROUPS),
        action("Snippets", "Commands worth keeping, typed into a session", Routes.SNIPPETS),
        action("VPNs and proxies", "WireGuard tunnels, Tailscale accounts and saved proxies", Routes.TUNNELS),
        action("Known hosts", "The host keys this phone has trusted", Routes.KNOWN_HOSTS),
        action("Transfers", "Files on their way to or from a host", Routes.TRANSFERS),
        action("Recordings", "Play back a recording, read a log, share or delete one", Routes.RECORDINGS),
        action("Run on many hosts", "One command across several servers, each answer beside its host", Routes.BROADCAST),
        action("Color scheme", "Preview every scheme as a real session", Routes.THEME),
        action("Settings", "The index of all of it", Routes.SETTINGS),
    )

    private fun setting(title: String, subtitle: String, route: String) =
        PaletteEntry(title, subtitle, PaletteKind.SETTING, route)

    private fun action(title: String, subtitle: String, route: String) =
        PaletteEntry(title, subtitle, PaletteKind.ACTION, route)
}
