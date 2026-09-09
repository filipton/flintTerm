package dev.flint.term.data.imports

import dev.flint.term.data.Host
import dev.flint.term.data.Protocol

/**
 * A host as some other app described it, before anything here has agreed to it.
 *
 * Only the fields every source can supply live here. Each parser fills in what
 * its format actually carries and leaves the rest alone, so the merge below has
 * one shape to reason about no matter which file it came from.
 */
data class ImportedHost(
    val label: String = "",
    val hostname: String = "",
    val port: Int = 22,
    val username: String = "",
    /** ARGB accent, or 0 for "no opinion". */
    val color: Int = 0,
    /** Typed into the shell after login — ConnectBot calls this the post-login command. */
    val startupCommand: String = "",
    val protocol: Protocol = Protocol.SSH,
) {
    /** Whether this names a machine at all; a row of blanks is not an error, just nothing. */
    val usable: Boolean get() = hostname.isNotBlank() && port in 1..65535

    fun toHost(groupId: String? = null): Host = Host(
        label = label.trim(),
        hostname = hostname.trim(),
        port = port,
        username = username.trim(),
        color = color,
        startupCommand = startupCommand.trim(),
        protocol = protocol,
        groupId = groupId,
    )
}

/**
 * What a file would add, once what is already saved has been taken out of it.
 *
 * Importing is only ever additive: a host that is already here is left exactly
 * as it is, because the file cannot know which of the two is the newer truth,
 * and quietly overwriting a working host with an old export is the one failure
 * that would cost somebody a login.
 */
data class ImportPlan(val fresh: List<ImportedHost>, val alreadyHere: Int) {
    val total: Int get() = fresh.size + alreadyHere

    /** "12 hosts, 3 already here" — the phrasing the OpenSSH importer uses. */
    fun describe(): String {
        val hosts = "${fresh.size} host" + if (fresh.size == 1) "" else "s"
        return if (alreadyHere == 0) hosts else "$hosts, $alreadyHere already here"
    }
}

/**
 * Sorts [imported] into what is new and what is a repeat of a saved host.
 *
 * Two hosts are the same machine when the hostname, port and username agree —
 * the same triple `ssh` itself would dial. The hostname is compared without
 * case because DNS does not have any.
 *
 * A file that lists the same machine twice contributes it once and is not
 * counted as a repeat: "already here" is about what is saved, and a person
 * reading that count is being told how much of their file this app already
 * knew, not how tidy the file was.
 */
fun planImport(imported: List<ImportedHost>, existing: List<Host>): ImportPlan {
    fun key(hostname: String, port: Int, username: String) =
        Triple(hostname.trim().lowercase(), port, username.trim())

    val saved = existing.mapTo(mutableSetOf()) { key(it.hostname, it.port, it.username) }
    val seen = mutableSetOf<Triple<String, Int, String>>()
    val fresh = mutableListOf<ImportedHost>()
    var alreadyHere = 0
    for (h in imported.filter { it.usable }) {
        val k = key(h.hostname, h.port, h.username)
        if (!seen.add(k)) continue
        if (k in saved) alreadyHere++ else fresh += h
    }
    return ImportPlan(fresh, alreadyHere)
}
