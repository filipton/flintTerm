package dev.flint.term.session

import dev.flint.term.core.Options
import dev.flint.term.data.Host
import dev.flint.term.data.Settings
import dev.flint.term.data.imageProtocols
import dev.flint.term.data.usesKeyboardProtocol

/**
 * What a session opened for [host] is allowed to do, given these settings.
 *
 * The core has no idea what a preference screen is, so the answer is worked
 * out here once per session and handed over — and handed over again whenever
 * a switch moves, since a terminal already on screen should not have to be
 * reconnected for a setting to take.
 */
fun Settings.coreOptions(host: Host?): Options {
    val images = host?.imageProtocols(this) ?: terminalImages
    return Options(
        keyboardProtocol = host?.usesKeyboardProtocol(this) ?: keyboardProtocol,
        notifications = notifyFromEscapes,
        // The marks are what turns "a command finished" from a guess into a
        // fact, so they are read whenever either notice could use them.
        promptMarks = notifyOnCommandFinish || notifyFromEscapes,
        // No switch of its own: OSC 7 arrives only from a shell that was
        // configured to send it, so reading it costs nothing anybody has to
        // opt out of, and a switch would only be one more thing to have set
        // wrong when the directory does not show up.
        workingDirectory = true,
        kittyImages = images.kitty,
        sixelImages = images.sixel,
        boldIsBright = boldIsBright,
        showBanner = showAuthBanners,
    )
}
