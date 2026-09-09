package dev.flint.term.session

import dev.flint.term.data.Host

/**
 * What counts as somewhere to connect to, typed into the search field or
 * arriving as an `ssh://` link.
 *
 * One definition rather than two. The search field and the link handler have to
 * agree about what a target looks like — otherwise a link that opens is a
 * string the search field refuses, and the two disagree in ways nobody notices
 * until a host is missing. It stays clear of Android's `Uri` on purpose: this
 * is where the rule lives, and the rule is worth testing without a device.
 */
object QuickConnect {
    /** `sftp://` names the same machine over the same protocol, so both are accepted. */
    private val schemes = setOf("ssh", "sftp")

    /** A DNS label, as generously as anything actually resolvable is written. */
    private val label = Regex("[A-Za-z0-9_]([A-Za-z0-9_-]*[A-Za-z0-9_])?")

    /** A tmux target: a session, optionally with the window inside it. */
    private val tmuxTarget = Regex("[A-Za-z0-9_.-]+(:[A-Za-z0-9_.-]+)?")

    /**
     * `[user@]host[:port]`, or the same thing written as an `ssh://` URL, as an
     * unsaved [Host]. Null when [text] is not an address at all.
     *
     * Being strict is the point: the search field offers to connect to whatever
     * comes back from here, so anything that is really a search term ("web
     * server", "prod:") has to be turned down rather than dressed up as a
     * machine.
     */
    fun parse(text: String): Host? {
        var rest = text.trim()
        if (rest.isEmpty() || rest.any { it.isWhitespace() }) return null
        val scheme = rest.substringBefore("://", "").lowercase()
        val hasScheme = rest.contains("://")
        if (hasScheme) {
            if (scheme !in schemes) return null
            rest = rest.substringAfter("://")
        }
        // A path, a query or a fragment belongs to a link; typed by hand it is
        // not an address, so only a link may carry one.
        val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (cut >= 0 && !hasScheme) return null
        val authority = if (cut >= 0) rest.substring(0, cut) else rest
        val query = if (cut >= 0 && rest[cut] != '#') rest.substring(cut).substringAfter('?', "").substringBefore('#') else ""

        val at = authority.lastIndexOf('@')
        // A password in a URL is dropped rather than kept: it would be stored in
        // the clear on the way to the editor, and nothing asks for it here.
        val written = if (at >= 0) authority.substring(0, at).substringBefore(':') else ""
        if (at >= 0 && (written.isEmpty() || written.any { it == '@' || it == '/' })) return null
        // Judged as written, spent as decoded: `%20` in a link is a deliberate
        // space in a name, while a bare one has already been turned down above.
        val user = decode(written)
        val (hostname, port) = splitHostPort(authority.substring(at + 1)) ?: return null
        return Host(hostname = hostname, port = port, username = user, startupCommand = tmuxCommand(query).orEmpty())
    }

    /**
     * The saved host this target already names, if there is one.
     *
     * A target that leaves the username out matches on the address alone: a
     * link to `ssh://rig` means the `rig` that is already saved, whoever it logs
     * in as.
     */
    fun saved(hosts: List<Host>, target: Host): Host? = hosts.firstOrNull {
        it.hostname.equals(target.hostname, true) && it.port == target.port &&
            (target.username.isBlank() || it.username == target.username)
    }

    private fun splitHostPort(text: String): Pair<String, Int>? {
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close < 0) return null
            val host = text.substring(1, close)
            val tail = text.substring(close + 1)
            val port = when {
                tail.isEmpty() -> 22
                tail.startsWith(":") -> portOf(tail.drop(1)) ?: return null
                else -> return null
            }
            return if (isIpv6(host)) host to port else null
        }
        // An unbracketed address with two colons is an IPv6 address written
        // without its brackets, and there is no telling where the port would be.
        val colon = text.indexOf(':')
        if (colon >= 0 && text.indexOf(':', colon + 1) >= 0) return null
        val host = if (colon >= 0) text.substring(0, colon) else text
        val port = if (colon >= 0) portOf(text.substring(colon + 1)) ?: return null else 22
        return if (isHostname(host)) host to port else null
    }

    private fun portOf(text: String): Int? =
        text.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it in 1..65535 }

    private fun isHostname(host: String): Boolean =
        host.isNotEmpty() && host.length <= 253 && host.split('.').all { it.length <= 63 && label.matches(it) }

    private fun isIpv6(host: String): Boolean =
        host.contains(':') && host.all { it.isDigit() || it in "abcdefABCDEF:.%" }

    /**
     * `?tmux=session:window` as the line that puts the session there.
     *
     * The window is selected before attaching, so the client lands on it
     * whatever the session was last looking at. A link is something a web page
     * can hand the phone and this ends up in a shell, so a value that is not a
     * plain tmux target is dropped: connecting without the tmux part beats
     * running whatever was appended to it.
     */
    private fun tmuxCommand(query: String): String? {
        val value = query.split('&')
            .firstOrNull { it.substringBefore('=') == "tmux" }
            ?.let { decode(it.substringAfter('=', "")) }
            ?: return null
        if (!tmuxTarget.matches(value)) return null
        val session = value.substringBefore(':')
        return if (value.contains(':')) "tmux select-window -t $value \\; attach -t $session" else "tmux attach -t $session"
    }

    private fun decode(text: String): String =
        runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
}
