package dev.flint.term.ui

import dev.flint.term.core.CertValidity
import dev.flint.term.core.CertificateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a parsed certificate is put into words on the key's page. */
class CertificateTextTest {

    private fun info(
        principals: List<String> = listOf("deploy"),
        validAfter: Long = 1_700_000_000,
        validBefore: Long = 1_700_086_400,
        validity: CertValidity = CertValidity.CURRENT,
    ) = CertificateInfo(
        keyType = "ssh-ed25519-cert-v01@openssh.com",
        keyId = "someone@example.com",
        principals = principals,
        validAfter = validAfter,
        validBefore = validBefore,
        serial = 7uL,
        host = false,
        caFingerprint = "SHA256:abc",
        comment = "",
        validity = validity,
    )

    /**
     * An empty principals list is OpenSSH's wildcard, not an empty answer —
     * printing nothing there would read as "logs in as nobody".
     */
    @Test
    fun `no principals means any account`() {
        assertEquals("any account", certPrincipals(info(principals = emptyList())))
        assertEquals("deploy, root", certPrincipals(info(principals = listOf("deploy", "root"))))
    }

    @Test
    fun `a live certificate says when it runs out`() {
        val text = certValidity(info())
        assertTrue(text, text.startsWith("Valid until"))
    }

    @Test
    fun `a certificate with no expiry is not given an unreadable date`() {
        assertEquals("No expiry", certValidity(info(validBefore = Long.MAX_VALUE)))
    }

    @Test
    fun `an expired one leads with that`() {
        val text = certValidity(info(validity = CertValidity.EXPIRED))
        assertTrue(text, text.startsWith("Expired"))
    }

    /** A CA's clock is right more often than a phone's, so say so. */
    @Test
    fun `one that is not valid yet blames the clock`() {
        val text = certValidity(info(validity = CertValidity.NOT_YET_VALID))
        assertTrue(text, text.contains("clock"))
    }
}
