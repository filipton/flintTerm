package dev.flint.term.terminal

import dev.flint.term.core.PaletteConfig
import dev.flint.term.data.Schemes
import dev.flint.term.data.TermScheme

/** The palette the terminal core is handed, for a scheme chosen by id. */
object Palettes {
    /**
     * The palette for the first of [ids] that names a known scheme, else the
     * app default. Pass the host's choice first and the settings' second, so a
     * host pointing at a scheme that no longer exists draws the app default.
     */
    fun forTheme(vararg ids: String?): PaletteConfig = of(Schemes.resolve(*ids))

    fun of(scheme: TermScheme): PaletteConfig =
        PaletteConfig(scheme.ansi, scheme.foreground, scheme.background, scheme.cursor, scheme.selection)
}
