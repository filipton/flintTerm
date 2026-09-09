package dev.flint.term.data.imports

import dev.flint.term.data.Protocol

/**
 * Hosts out of a spreadsheet — Termius's export template, or any CSV at all.
 *
 * The two are the same code path with a different amount of guessing. A file
 * whose headers this recognizes is mapped straight away; one whose headers mean
 * nothing here is handed back as [Table] so the person can say which column is
 * which, because a CSV that came out of somebody's own notes is far more common
 * than one that came out of another SSH client.
 */
object CsvImport {
    /**
     * Header names understood without being asked, per field.
     *
     * Termius's own template is
     * `Groups,Label,Tags,Hostname/IP,Protocol,Port,Username,Password`, taken
     * from the `termius_import.csv` its desktop app offers for download.
     * Termius illustrates that file with screenshots rather than quoting it, so
     * the row is corroborated rather than read first-hand — which is the reason
     * the lists below are a superset rather than that row exactly. They also
     * take the names OpenSSH and PuTTY use for the same things and the words
     * somebody writing the file by hand would reach for, and anything they miss
     * is what the mapping step is for.
     *
     * `Tags`, `Groups` and `Password` have no column here on purpose: the first
     * two have no equivalent this could honour per row, and a password read out
     * of a spreadsheet is one this app would then be keeping.
     *
     * Matching ignores case and anything that is not a letter or a digit, so
     * "Host Name", "hostname", "HOST_NAME" and "Hostname/IP" are one name.
     */
    private val KNOWN = mapOf(
        Field.HOSTNAME to listOf("hostname", "hostnameip", "host", "address", "ip", "ipaddress", "server"),
        Field.USERNAME to listOf("username", "user", "login", "account"),
        Field.PORT to listOf("port", "sshport"),
        Field.LABEL to listOf("label", "name", "nickname", "alias", "title", "description"),
        Field.PROTOCOL to listOf("protocol", "scheme"),
    )

    /** The things a column can be. Everything else in the file is ignored. */
    enum class Field(val label: String) {
        HOSTNAME("Hostname"),
        USERNAME("Username"),
        PORT("Port"),
        LABEL("Label"),
        PROTOCOL("Protocol"),
    }

    /**
     * A parsed file, before any column has been given a meaning.
     *
     * [suggested] is what the header row was recognized as; a field missing from
     * it is one nobody has claimed yet. The UI shows this as the starting point
     * of the mapping step, so a Termius file needs no clicks and a hand-written
     * one needs only the columns that were not obvious.
     */
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val suggested: Map<Field, Int>,
    ) {
        /** Whether every column that must be named has been, i.e. the hostname. */
        fun ready(mapping: Map<Field, Int>): Boolean = mapping[Field.HOSTNAME] != null
    }

    /**
     * Reads [text] as CSV, keeping the first row as headers.
     *
     * Returns null when there is nothing to work with — no rows, or a header
     * row that is entirely blank.
     */
    fun read(text: String): Table? {
        val all = parseRows(text).filter { row -> row.any { it.isNotBlank() } }
        if (all.isEmpty()) return null
        val headers = all.first().map { it.trim() }
        if (headers.all { it.isBlank() }) return null
        val suggested = mutableMapOf<Field, Int>()
        for ((field, names) in KNOWN) {
            val at = headers.indexOfFirst { normalize(it) in names }
            if (at >= 0) suggested[field] = at
        }
        return Table(headers, all.drop(1), suggested)
    }

    /** The hosts [table]'s rows describe, given which column means what. */
    fun hosts(table: Table, mapping: Map<Field, Int>): List<ImportedHost> {
        fun cell(row: List<String>, field: Field): String =
            mapping[field]?.let { row.getOrNull(it) }?.trim().orEmpty()

        return table.rows.mapNotNull { row ->
            val hostname = cell(row, Field.HOSTNAME)
            if (hostname.isEmpty()) return@mapNotNull null
            // A hostname column often holds "user@host:port" verbatim, because
            // that is what people paste; taking it apart here means the other
            // columns are not needed for the file to be useful.
            val target = splitTarget(hostname)
            val protocol = if (cell(row, Field.PROTOCOL).lowercase() == "telnet") Protocol.TELNET else Protocol.SSH
            val port = cell(row, Field.PORT).toIntOrNull()?.takeIf { it in 1..65535 }
                ?: target.port
                ?: if (protocol == Protocol.TELNET) 23 else 22
            ImportedHost(
                label = cell(row, Field.LABEL),
                hostname = target.host,
                port = port,
                username = cell(row, Field.USERNAME).ifEmpty { target.user.orEmpty() },
                protocol = protocol,
            )
        }.filter { it.usable }
    }

    /** The one-step form: read the file and take its headers at their word. */
    fun parse(text: String): List<ImportedHost> =
        read(text)?.let { hosts(it, it.suggested) } ?: emptyList()

    private data class Target(val host: String, val user: String?, val port: Int?)

    private fun splitTarget(raw: String): Target {
        val afterUser = raw.substringAfterLast('@')
        val user = if (afterUser == raw) null else raw.substringBeforeLast('@').ifBlank { null }
        // Only a trailing ":number" is a port; a bare IPv6 address has colons of
        // its own and no port at all.
        val port = afterUser.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 }
        val host = if (port != null) afterUser.substringBeforeLast(':') else afterUser
        return Target(host.trim().removeSurrounding("[", "]"), user, port)
    }

    private fun normalize(header: String) = header.filter { it.isLetterOrDigit() }.lowercase()

    /**
     * RFC 4180 as far as it goes: commas separate, `"` quotes, `""` inside a
     * quoted field is one quote, and a newline inside quotes is part of the
     * value rather than the end of the row.
     *
     * Semicolons are accepted as separators too when the header row has more of
     * them than commas, which is what a spreadsheet saves in most of Europe.
     */
    private fun parseRows(text: String): List<List<String>> {
        val separator = separatorOf(text)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        // The BOM a spreadsheet writes would otherwise become part of the first
        // header, and a header nobody can match is a column nobody can use.
        if (text.startsWith('\uFEFF')) i = 1
        fun endCell() { row.add(cell.toString()); cell.setLength(0) }
        fun endRow() { endCell(); rows.add(row); row = mutableListOf() }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == separator -> endCell()
                !quoted && (c == '\n' || c == '\r') -> {
                    endRow()
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    private fun separatorOf(text: String): Char {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        return if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
    }
}
