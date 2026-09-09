package dev.flint.term.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferRouteTest {

    @Test
    fun `a copy names both machines and an ordinary transfer only its own`() {
        val copy = Transfer(id = "1", kind = TransferKind.COPY, name = "dump.sql", sessionLabel = "web-01", destLabel = "backup")
        assertEquals("web-01 → backup", copy.route)
        val download = Transfer(id = "2", kind = TransferKind.DOWNLOAD, name = "dump.sql", sessionLabel = "web-01")
        assertEquals("web-01", download.route)
    }

    @Test
    fun `a destination folder joins onto its path exactly once`() {
        assertEquals("/srv/backups/dump.sql", TransferManager.join("/srv/backups", "dump.sql"))
        assertEquals("/dump.sql", TransferManager.join("/", "dump.sql"))
    }
}
