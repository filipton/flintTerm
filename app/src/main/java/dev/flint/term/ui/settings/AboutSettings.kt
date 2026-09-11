package dev.flint.term.ui.settings

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.navigation.NavController
import dev.flint.term.BuildConfig
import dev.flint.term.ui.Group
import dev.flint.term.ui.GroupRow
import dev.flint.term.ui.RowDivider
import dev.flint.term.ui.Routes
import java.io.File

/**
 * What this is and what it is built out of.
 *
 * Most of a bug report is answered here: which build, which commit, which
 * terminal engine, which SSH library at which version, and whether the phone
 * has hardware to keep keys in. A tap on the top row copies all of it.
 */
@Composable
fun AboutSettings(nav: NavController) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val facts = remember { BuildFacts.collect(context) }

    SettingsSection(nav, "About") {
        Group {
            GroupRow(
                title = "flintTerm ${BuildConfig.VERSION_NAME}",
                subtitle = facts.build,
                icon = Icons.Rounded.Info, iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { clipboard.setText(AnnotatedString(facts.report())) },
                trailing = { Text("Copy", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
            )
        }

        Group("Under the hood") {
            GroupRow(
                title = "Terminal",
                subtitle = facts.terminal,
                icon = Icons.Rounded.Terminal, iconTint = MaterialTheme.colorScheme.secondary,
            )
            RowDivider()
            GroupRow(
                title = "SSH and SFTP",
                subtitle = facts.ssh,
                icon = Icons.Rounded.Key, iconTint = MaterialTheme.colorScheme.primary,
            )
            RowDivider()
            GroupRow(
                title = "Mosh, WireGuard and Tailscale",
                subtitle = facts.transports,
                icon = Icons.Rounded.VpnLock, iconTint = MaterialTheme.colorScheme.tertiary,
            )
            RowDivider()
            GroupRow(
                title = "Keys and passwords",
                subtitle = facts.keystore,
                icon = Icons.Rounded.Lock, iconTint = MaterialTheme.colorScheme.error,
            )
            RowDivider()
            GroupRow(
                title = "Bridge",
                subtitle = facts.bridge,
                icon = Icons.Rounded.Hub, iconTint = MaterialTheme.colorScheme.secondary,
            )
        }

        Group("Open source") {
            GroupRow(
                title = "Licenses",
                subtitle = "The libraries this app is made of, and their terms",
                icon = Icons.Rounded.Article, iconTint = MaterialTheme.colorScheme.primary,
                onClick = { nav.navigate(Routes.LICENSES) },
            )
        }
    }
}

/** The facts About shows, gathered once; none of them change while the app runs. */
class BuildFacts private constructor(
    val build: String,
    val terminal: String,
    val ssh: String,
    val transports: String,
    val keystore: String,
    val bridge: String,
) {
    fun report(): String = listOf(
        "flintTerm ${BuildConfig.VERSION_NAME} ($build)",
        "Terminal: $terminal",
        "SSH: $ssh",
        "Transports: $transports",
        "Keys: $keystore",
        "Bridge: $bridge",
    ).joinToString("\n")

    companion object {
        fun collect(context: android.content.Context): BuildFacts {
            val versions = BuildConfig.CORE_VERSIONS.split(';')
                .mapNotNull { it.split('=').takeIf { p -> p.size == 2 && p[1].isNotBlank() }?.let { p -> p[0] to p[1] } }
                .toMap()
            fun v(crate: String) = versions[crate]?.let { " $it" }.orEmpty()

            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown ABI"
            val kind = if (BuildConfig.DEBUG) "debug" else "release"
            val sha = BuildConfig.GIT_SHA.ifBlank { "no commit" }
            val build = "$kind $sha  ·  $abi  ·  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

            // Which engine the core was compiled with is the core's to say.
            val engine = CoreFacts.terminalBackend()
            val terminal = when (engine) {
                "libghostty-vt" -> "libghostty-vt, the VT engine out of Ghostty"
                else -> "alacritty_terminal${v("alacritty_terminal")}, the emulator out of Alacritty"
            } + "  ·  xterm-256color, kitty keyboard protocol, kitty and sixel images"

            val ssh = "russh${v("russh")} with ring${v("ring")}  ·  russh-sftp${v("russh-sftp")}  ·  OpenSSH keys, certificates, FIDO2 security keys"

            val hasTailscale = File(context.applicationInfo.nativeLibraryDir, "libtailscale.so").exists()
            val transports = "Mosh, written for this app  ·  WireGuard in userspace with boringtun${v("boringtun")} and smoltcp${v("smoltcp")}  ·  " +
                if (hasTailscale) "Tailscale embedded" else "Tailscale not in this build"

            val strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            val keystore = "Encrypted with a key the Android Keystore holds  ·  " +
                if (strongBox) "StrongBox available for keys generated on this phone" else "no StrongBox on this phone, so keys stay in the trusted environment"

            val bridge = "Rust core reached through uniffi${v("uniffi")} on tokio${v("tokio")}  ·  Kotlin and Jetpack Compose on top"
            return BuildFacts(build, terminal, ssh, transports, keystore, bridge)
        }
    }
}

/**
 * What only the Rust core knows about itself. Kept behind one name so the
 * About screen does not change when the binding it calls does.
 */
object CoreFacts {
    fun terminalBackend(): String = runCatching { dev.flint.term.core.terminalBackend() }.getOrDefault("alacritty")
}
