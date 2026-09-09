package dev.flint.term.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every stored preference has to survive a trip through the file, including
 * the ones nobody has touched: a setting that reads back as its default after
 * having been changed is worse than one that was never offered.
 */
class SettingsRoundTripTest {
    private fun roundTrip(snap: Snapshot): Snapshot =
        StoreJson.read(StoreJson.write(snap) { it }) { it }

    @Test fun `settings survive the file`() {
        val settings = Settings(
            keyboardProtocol = false,
            rawMenuControls = false,
            doubleTapLocksModifier = false,
            twoFingerDragArrows = false,
            capsLockAs = CapsLockAction.CTRL,
            composeRemembersState = false,
            tmuxControls = false,
            ctrlLongPressOpensChords = false,
            chords = listOf(Chord(label = "New window", keys = "C-b c", tab = "tmux")),
            showAuthBanners = false,
            discoverNearbyHosts = false,
            restoreSessions = false,
            notifyFromEscapes = false,
            pipOnLeave = true,
            editor = EditorChoice.EXTERNAL,
            editorBackup = true,
            nerdGlyphs = false,
            boldIsBright = true,
            cursorStyle = CursorStyle.BAR,
            cursorBlink = true,
            highlightEnabled = true,
            highlightRules = listOf(HighlightRule(pattern = "ERROR", color = -65536, wholeLine = true)),
            terminalImages = TerminalImages.SIXEL,
            shortcuts = listOf(KeyBinding("COPY", 31, ctrl = true, shift = true)),
        )
        assertEquals(settings, roundTrip(Snapshot(settings = settings)).settings)
    }

    @Test fun `an untouched file reads back the defaults`() {
        assertEquals(Settings(), roundTrip(Snapshot()).settings)
    }

    @Test fun `a host keeps its overrides, and its silence`() {
        val host = Host(hostname = "rig", keyboardProtocol = false, tmuxControls = true, tmuxPrefix = "C-a", fontSizeSp = 17f)
        val back = roundTrip(Snapshot(hosts = listOf(host))).hosts.single()
        assertEquals(false, back.keyboardProtocol)
        assertEquals(true, back.tmuxControls)
        assertEquals("C-a", back.tmuxPrefix)
        assertEquals(17f, back.fontSizeSp, 0.01f)
        // Unset has to stay unset: false would pin the host to today's default.
        assertNull(back.terminalImages)
    }

    @Test fun `a hashed host key keeps its salt, and a plain one stays plain`() {
        val hashed = KnownHost(
            host = "", port = 0, keyType = "ssh-ed25519", keyBase64 = "AAAAC3NzaC1lZDI1NTE5",
            fingerprint = "SHA256:whatever",
            hashSalt = "flNVUVlqXAVh8yauJM8455CWxVc=", hashedHost = "ArNwUzL/d2ouWvSNk/2UGLs2OPs=",
        )
        // A plain entry writes neither field, which is also what a file from before hashing looks like.
        val plain = KnownHost("rig", 2222, "ssh-rsa", "AAAAB3Nza", "SHA256:other")
        val back = roundTrip(Snapshot(knownHosts = listOf(hashed, plain))).knownHosts
        assertEquals(listOf(hashed, plain), back)
        assertTrue(back[0].isHashed)
        assertTrue(!back[1].isHashed)
    }

    @Test fun `overrides win over the app setting, silence follows it`() {
        val on = Settings(keyboardProtocol = true, terminalImages = TerminalImages.BOTH, tmuxControls = true)
        assertTrue(Host().usesKeyboardProtocol(on))
        assertTrue(!Host(keyboardProtocol = false).usesKeyboardProtocol(on))
        assertEquals(TerminalImages.BOTH, Host().imageProtocols(on))
        assertEquals(TerminalImages.OFF, Host(terminalImages = false).imageProtocols(on))
        // A host that attaches to tmux gets the controls without being asked.
        assertTrue(Host(persistent = true).usesTmuxControls(on))
        assertTrue(!Host(persistent = false).usesTmuxControls(on))
        assertTrue(Host(persistent = false, tmuxControls = true).usesTmuxControls(on))
        assertTrue(!Host(persistent = true).usesTmuxControls(on.copy(tmuxControls = false)))
    }
}
