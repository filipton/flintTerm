package dev.flint.term.files

import dev.flint.term.files.SftpDocumentsProvider.Companion.streams
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SftpDocumentsStreamTest {
    private val big = 50L shl 20

    @Test
    fun `a large file read front to back is streamed`() {
        assertTrue(streams("text/plain", big))
        assertTrue(streams("application/octet-stream", big))
        assertTrue(streams("application/gzip", big))
    }

    @Test
    fun `a small file keeps the cached copy`() {
        assertFalse(streams("text/plain", 4096))
        assertFalse(streams("application/octet-stream", SftpDocumentsProvider.STREAM_ABOVE))
    }

    @Test
    fun `anything a reader has to seek in keeps the cached copy`() {
        assertFalse(streams("video/mp4", big))
        assertFalse(streams("audio/flac", big))
        assertFalse(streams("image/jpeg", big))
        assertFalse(streams("application/pdf", big))
        assertFalse(streams("application/zip", big))
        assertFalse(streams("application/vnd.android.package-archive", big))
        assertFalse(streams("application/vnd.openxmlformats-officedocument.wordprocessingml.document", big))
    }
}
