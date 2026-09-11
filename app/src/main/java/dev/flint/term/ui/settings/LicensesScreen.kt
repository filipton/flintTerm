package dev.flint.term.ui.settings

import dev.flint.term.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import dev.flint.term.ui.Group
import dev.flint.term.ui.GroupRow
import dev.flint.term.ui.RowDivider

/** One dependency worth crediting: what it is, whose it is, and under what terms. */
private data class Credit(val name: String, val what: String, val license: String)

/**
 * Everything the app is built from that is not ours.
 *
 * A static list, because the set of libraries changes with the code and not
 * with the user; a line here is added in the same commit that adds the
 * dependency.
 */
private val CREDITS = listOf(
    "Rust core" to listOf(
        Credit("russh", "The SSH client, with the ring crypto backend", "Apache-2.0"),
        Credit("russh-sftp", "The SFTP client on top of it", "Apache-2.0"),
        Credit("alacritty_terminal", "The terminal emulator out of Alacritty", "Apache-2.0"),
        Credit("libghostty-vt", "The VT engine out of Ghostty, in builds that choose it", "MIT"),
        Credit("boringtun", "WireGuard in userspace, by Cloudflare", "BSD-3-Clause"),
        Credit("smoltcp", "The TCP/IP stack the WireGuard tunnel runs on", "0BSD"),
        Credit("tokio", "The async runtime everything in the core runs on", "MIT"),
        Credit("uniffi", "Generates the Kotlin bindings to the core, by Mozilla", "MPL-2.0"),
        Credit("ring", "Cryptography, by Brian Smith", "ISC and OpenSSL-style"),
    ),
    "Android" to listOf(
        Credit("Jetpack Compose and Material 3", "The user interface toolkit", "Apache-2.0"),
        Credit("kotlinx.coroutines", "The concurrency the app is written in", "Apache-2.0"),
        Credit("JNA", "How Kotlin reaches the Rust core, taken under the Apache half of its dual license", "Apache-2.0"),
        Credit("usb-serial-for-android", "USB serial adapters as terminals", "MIT"),
        Credit("libtailscale", "Tailscale embedded, in builds that carry it", "BSD-3-Clause"),
    ),
    "Fonts and colors" to listOf(
        Credit("JetBrains Mono", "The default terminal font", "OFL-1.1"),
        Credit("Fira Code", "A terminal font with ligatures", "OFL-1.1"),
        Credit("Hack", "A terminal font, with glyphs from Bitstream Vera and DejaVu", "MIT and Bitstream Vera"),
        Credit("Nerd Fonts symbols", "The prompt and file icons", "MIT"),
        Credit("iTerm2-Color-Schemes", "The catalog of color schemes, by Mario Badolato and contributors", "MIT"),
    ),
)

@Composable
fun LicensesScreen(nav: NavController) {
    SettingsSection(nav, "Licenses", subtitle = stringResource(R.string.licensesscreen_what_this_app_is_made_of)) {
        CREDITS.forEach { (heading, credits) ->
            Group(heading) {
                credits.forEachIndexed { i, c ->
                    if (i > 0) RowDivider()
                    GroupRow(
                        title = c.name,
                        subtitle = c.what,
                        trailing = { Text(c.license, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
        }
    }
}
