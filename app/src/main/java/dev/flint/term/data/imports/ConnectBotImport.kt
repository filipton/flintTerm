package dev.flint.term.data.imports

import dev.flint.term.data.Protocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * ConnectBot's "Export hosts" file.
 *
 * Written against schema version 8, the Room database version ConnectBot 1.10.9
 * exports (`SchemaBasedExporter.exportToJson`), which produces:
 *
 * ```json
 * { "version": 8,
 *   "profiles":      [ { "id": 1, "name": "Default", "fontSize": 12, … } ],
 *   "hosts":         [ { "id": 10, "nickname": "…", "hostname": "…", "port": 22, … } ],
 *   "port_forwards": [ … ] }
 * ```
 *
 * Two things about that file shape everything below. Booleans are written as
 * the integers `0` and `1`, never `true`/`false`, because the exporter reads
 * them straight out of SQLite's INTEGER columns. And a column that is NULL is
 * left out of the object altogether rather than written as `null` — so
 * "missing" is the normal case for an optional field, not a sign of damage.
 *
 * The version is read but not enforced. ConnectBot's own importer calls itself
 * "intentionally permissive", and a host with a nickname and a hostname is worth
 * importing whatever number is at the top of the file.
 *
 * Four fields are left behind on purpose. `fontSize`, `delKey`, `encoding` and
 * `emulation` moved to ConnectBot's `profiles` rows and have no per-host
 * equivalent here — a font size in this app belongs to the whole app, so an
 * imported one would be quietly ignored, which is worse than not taking it.
 * `pubkeyId` goes nowhere because the keys themselves are not in this file:
 * ConnectBot's backup keeps them in a separate copy of its database.
 */
object ConnectBotImport {
    /** ConnectBot's whole palette, as `HostConstants` spells it. */
    private val COLORS = mapOf(
        "red" to 0xFFE05252.toInt(),
        "green" to 0xFF4CAF50.toInt(),
        "blue" to 0xFF4A90D9.toInt(),
        "gray" to 0xFF9E9E9E.toInt(),
    )

    /**
     * The hosts in a ConnectBot export, in the order the file lists them.
     *
     * Returns an empty list for anything that is not one, rather than throwing:
     * the caller picked a file out of a file picker and a wrong pick deserves a
     * sentence, not a crash.
     */
    fun parse(text: String): List<ImportedHost> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val hosts = root.optJSONArray("hosts") ?: return emptyList()
        return (0 until hosts.length()).mapNotNull { i -> hosts.optJSONObject(i)?.let(::host) }
            .filter { it.usable }
    }

    /** The `version` at the top of the file, or null if it does not say. */
    fun schemaVersion(text: String): Int? =
        runCatching { JSONObject(text) }.getOrNull()?.let { if (it.has("version")) it.optInt("version") else null }

    private fun host(o: JSONObject): ImportedHost? {
        // ConnectBot's "local" protocol is a shell on the phone itself, which is
        // not a host in any sense this app could dial.
        val protocol = when (o.optString("protocol", "ssh")) {
            "telnet" -> Protocol.TELNET
            "ssh" -> Protocol.SSH
            else -> return null
        }
        val hostname = o.optString("hostname").trim()
        if (hostname.isEmpty()) return null
        return ImportedHost(
            label = o.optString("nickname").trim(),
            hostname = hostname,
            port = o.optInt("port", if (protocol == Protocol.TELNET) 23 else 22).takeIf { it in 1..65535 }
                ?: return null,
            username = o.optString("username").trim(),
            color = COLORS[o.optString("color").lowercase()] ?: 0,
            startupCommand = o.optString("postLogin").trim(),
            protocol = protocol,
        )
    }

    /** Whether [text] even looks like one of these, for telling files apart in the picker. */
    fun looksLikeExport(text: String): Boolean {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        return root.optJSONArray("hosts") is JSONArray
    }
}
