package dev.flint.term.data

import java.util.UUID

enum class AuthType { PASSWORD, KEY, NONE }

enum class ForwardType { LOCAL, REMOTE, DYNAMIC }

data class PortForward(
    val id: String = UUID.randomUUID().toString(),
    val type: ForwardType = ForwardType.LOCAL,
    val bindHost: String = "127.0.0.1",
    val bindPort: Int = 8080,
    val targetHost: String = "localhost",
    val targetPort: Int = 80,
    val autoStart: Boolean = true,
) {
    fun describe(): String = when (type) {
        ForwardType.LOCAL -> "L $bindHost:$bindPort → $targetHost:$targetPort"
        ForwardType.REMOTE -> "R $bindHost:$bindPort ← $targetHost:$targetPort"
        ForwardType.DYNAMIC -> "D $bindHost:$bindPort  (SOCKS proxy)"
    }
}

enum class ProxyType { NONE, SOCKS5, HTTP }

data class ProxySettings(
    val type: ProxyType = ProxyType.NONE,
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    /** Plaintext in memory only; encrypted at rest. */
    val password: String = "",
) {
    val enabled: Boolean get() = type != ProxyType.NONE && host.isNotBlank()
}

/**
 * A proxy the app remembers, so it can be picked rather than retyped.
 *
 * The same corporate SOCKS5 usually fronts every host behind it; keeping one
 * copy means a changed port is changed once.
 */
data class SavedProxy(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: ProxyType = ProxyType.SOCKS5,
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    /** Plaintext in memory only; encrypted at rest. */
    val password: String = "",
    val created: Long = System.currentTimeMillis(),
) {
    val label: String get() = name.ifBlank { "$host:$port" }
    val target: String get() = "${if (type == ProxyType.HTTP) "HTTP" else "SOCKS5"}  ·  $host:$port"
    val usable: Boolean get() = host.isNotBlank() && port in 1..65535
    fun settings(): ProxySettings = ProxySettings(type, host.trim(), port, username.trim(), password)
}

/**
 * A login reused across hosts: who you are on the machine, not which machine.
 *
 * The same `root` and the same key usually open twenty servers, and typing
 * them into twenty hosts means twenty places to edit when the key is replaced.
 * A host that names an account takes its username and its authentication from
 * here; one that does not keeps its own, so nothing has to be moved.
 */
data class Account(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val username: String = "",
    val authType: AuthType = AuthType.KEY,
    /** Plaintext in memory only; encrypted at rest. */
    val password: String = "",
    /** The key it signs with, when [authType] is [AuthType.KEY]. */
    val identityId: String? = null,
    val created: Long = System.currentTimeMillis(),
) {
    val label: String get() = name.ifBlank { username.ifBlank { "Account" } }
}

/**
 * A folder of hosts that also carries what they have in common.
 *
 * Twenty machines in one datacentre usually share a jump host, a VPN, a proxy
 * and a login; typing all four into each of them is where mistakes and stale
 * settings come from. A host's own value always wins, so the group is a
 * default, never an override — nothing already set can be changed from here by
 * surprise.
 */
data class HostGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val accountId: String? = null,
    val jumpHostId: String? = null,
    val tunnelId: String? = null,
    val tailscaleId: String? = null,
    val proxyId: String? = null,
    /** A scheme id (see Schemes) for hosts that have not picked one. */
    val theme: String? = null,
    /** ARGB accent for hosts that have not picked one. */
    val color: Int = 0,
    val created: Long = System.currentTimeMillis(),
) {
    val label: String get() = name.ifBlank { "Unnamed group" }

    /** What this group hands down, for a one-line summary. */
    fun defaultCount(): Int = listOfNotNull(accountId, jumpHostId, tunnelId, tailscaleId, proxyId, theme).size +
        (if (color != 0) 1 else 0)
}

/**
 * [this] with its group's defaults filled into whatever it left unset.
 */
fun Host.withGroup(group: HostGroup?): Host {
    if (group == null || groupId == null || group.id != groupId) return this
    return copy(
        accountId = accountId ?: group.accountId,
        // A group whose jump host is one of its own members must not send that
        // member through itself.
        jumpHostId = jumpHostId ?: group.jumpHostId?.takeIf { it != id },
        tunnelId = tunnelId ?: group.tunnelId,
        tailscaleId = tailscaleId ?: group.tailscaleId,
        proxyId = proxyId ?: group.proxyId,
        theme = theme ?: group.theme,
        color = if (color != 0) color else group.color,
    )
}

/**
 * [this] with [account]'s login in place of its own, or unchanged when it has
 * no account.
 *
 * The host's own username survives an account that does not name one, which is
 * the useful reading of a shared key used by different users on each machine.
 */
fun Host.withAccount(account: Account?): Host {
    if (account == null || accountId == null || account.id != accountId) return this
    return copy(
        username = account.username.trim().ifBlank { username },
        authType = account.authType,
        password = account.password,
        identityId = account.identityId,
    )
}

/**
 * Whether a signature through a forwarded agent has to be asked about, given
 * the machines it would pass through and the setting to fall back on.
 *
 * Every host on the route counts, not only the far end: the agent socket passes
 * through each hop, so a jump host is exactly as able to use it.
 */
fun asksBeforeAgentSigning(route: List<Host>, fallback: Boolean): Boolean =
    route.any { it.forwardAgent && (it.askBeforeAgentSigning ?: fallback) }

/**
 * Whether this host speaks the kitty keyboard protocol, given the app setting
 * to fall back on when it has not said either way.
 */
fun Host.usesKeyboardProtocol(settings: Settings): Boolean = keyboardProtocol ?: settings.keyboardProtocol

/**
 * Which image protocols this host answers to. A host that says no is off
 * whatever the app setting is; a host that says yes still follows the app's
 * choice of protocol, since that is a question about the terminal and not
 * about the machine.
 */
fun Host.imageProtocols(settings: Settings): TerminalImages = when (terminalImages) {
    false -> TerminalImages.OFF
    else -> settings.terminalImages
}

/**
 * Whether the tmux chords and window list are offered for this host.
 *
 * Unset means "when the session is attached to tmux anyway" — the controls are
 * useless without a tmux to drive, and a host that reattaches on every login
 * always has one.
 */
/**
 * The font size this host's sessions are drawn at: its own when it has set
 * one, the app's otherwise.
 */
fun Host?.fontSize(settings: Settings): Float =
    this?.fontSizeSp?.takeIf { it > 0f } ?: settings.fontSizeSp

fun Host.usesTmuxControls(settings: Settings): Boolean =
    settings.tmuxControls && (tmuxControls ?: persistent)

enum class WolSource(val label: String) { AUTO("Auto"), PHONE("This phone"), JUMP_HOST("Jump host") }

/** Wake-on-LAN settings for a host. */
data class WolSettings(
    val enabled: Boolean = false,
    val mac: String = "",
    /** Broadcast address for the magic packet; empty means "work it out at send time". */
    val broadcast: String = "",
    val port: Int = 9,
    val sendFrom: WolSource = WolSource.AUTO,
    /** Send the packet automatically before every connection. */
    val autoWake: Boolean = true,
)

/**
 * A knock sequence sent from the phone just before the connection is dialed,
 * for a host sitting behind `knockd` or something like it.
 *
 * The packets leave this device, so they can only open a firewall the phone can
 * reach directly — a jump host, a tunnel or a proxy carries the connection but
 * not the knock.
 */
data class KnockSettings(
    val enabled: Boolean = false,
    /** As typed: "7000, 8000/udp, 9000". Read by `dev.flint.term.session.PortKnock`. */
    val sequence: String = "",
    /** Between one knock and the next; a firewall wants them apart, but not far apart. */
    val delayMs: Int = 200,
    /** After the last knock, so the rule is in place before the connection arrives. */
    val pauseMs: Int = 400,
)

/** A saved command. `{{name}}` placeholders are asked for before it is typed. */
data class Snippet(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val command: String = "",
    /** Press Enter after typing it. */
    val run: Boolean = true,
    /** Empty = shown for every host. */
    val hostIds: List<String> = emptyList(),
    val created: Long = System.currentTimeMillis(),
) {
    val placeholders: List<String> get() = Regex("\\{\\{\\s*([^}]+?)\\s*\\}\\}").findAll(command).map { it.groupValues[1] }.distinct().toList()
}

/**
 * One environment variable asked for on the session channel.
 *
 * Asked for, not set: sshd only passes on what its `AcceptEnv` lists, which on
 * a stock configuration is `LANG` and `LC_*` and nothing else.
 */
data class EnvEntry(val name: String, val value: String)

/**
 * The entries worth putting on the wire: named, trimmed, and at most one per
 * name.
 *
 * A half-filled row in the host editor and a duplicate name are both easy to
 * arrive at, and neither earns a request — the server would answer the second
 * one exactly as it answered the first. So the later value wins, and the name
 * keeps the place it was first given.
 */
fun List<EnvEntry>.requestable(): List<EnvEntry> =
    map { it.copy(name = it.name.trim()) }
        .filter { it.name.isNotEmpty() }
        .associateBy { it.name }
        .values
        .toList()

/** How a host uses its WireGuard tunnel. */
enum class TunnelMode(val label: String) { WHEN_NEEDED("Only when needed"), ALWAYS("Always") }

/**
 * One Tailscale identity: its own tsnet node, state directory and machine name,
 * so several tailnets (work and home, say) can be used side by side. The node
 * only runs while a host needs it.
 */
data class TailscaleProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** Machine name on the tailnet; blank means one derived from the device. */
    val hostname: String = "",
    /** Optional pre-auth key, encrypted at rest; cleared once it has been used or on leaving. */
    val authKey: String = "",
    /** Control server, for Headscale and friends. */
    val controlUrl: String = "",
    /** The node has joined a tailnet — it spends most of its life stopped, where status says nothing. */
    val joined: Boolean = false,
    val created: Long = System.currentTimeMillis(),
)

/** A userspace WireGuard tunnel (wg-quick config, stored encrypted). */
data class Tunnel(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val config: String = "",
    val created: Long = System.currentTimeMillis(),
)

/**
 * One address a host can be reached at, with the route to use for it.
 *
 * A machine usually has more than one: a LAN address at home, a tailnet name
 * from anywhere else. They are tried in order and the first that answers wins,
 * so the phone does the deciding instead of you.
 */
data class HostAddress(
    val id: String = UUID.randomUUID().toString(),
    /** Shown while connecting ("Home LAN"); the address is used when blank. */
    val label: String = "",
    val hostname: String = "",
    /** 0 means "the host's own port". */
    val port: Int = 0,
    val tunnelId: String? = null,
    val tailscaleId: String? = null,
)

/** Icons a host can show on its card; keys are stored, so keep them stable. */
enum class HostIcon(val label: String) {
    SERVER("Server"), CLOUD("Cloud"), PI("Single-board"), LAPTOP("Laptop"), DESKTOP("Desktop"),
    ROUTER("Router"), DATABASE("Database"), CONTAINER("Container"), NAS("Storage"), HOME("Home"),
}

/** What we speak to a host. Telnet has no auth of its own — the device prompts. */
enum class Protocol { SSH, TELNET }

data class Host(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val hostname: String = "",
    val port: Int = 22,
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    /** Plaintext in memory only; encrypted at rest. */
    val password: String = "",
    val identityId: String? = null,
    /**
     * Log in as a saved [Account] rather than with this host's own username and
     * key. When set, the fields above are left as they were and ignored, so
     * turning it off puts back what was there.
     */
    val accountId: String? = null,
    val forwards: List<PortForward> = emptyList(),
    /** ARGB accent used on the host card. */
    val color: Int = 0,
    val icon: HostIcon = HostIcon.SERVER,
    /** The [HostGroup] this host belongs to; hosts are sectioned by it. */
    val groupId: String? = null,
    val lastConnected: Long = 0,
    /** Connect through this host first (`ssh -J`). May itself have a jump host. */
    val jumpHostId: String? = null,
    /** Run on the jump host before connecting onwards, e.g. a wake-on-LAN script. */
    val preConnectCommand: String = "",
    /** Keep retrying the connection from the jump host for this long. */
    val waitForHostSeconds: Int = 90,
    /**
     * Proxy for the first hop, by id. The old per-host settings are still read
     * from stored data and turned into a saved proxy on load.
     */
    val proxyId: String? = null,
    val proxy: ProxySettings = ProxySettings(),
    /** Typed into the shell right after login. */
    val startupCommand: String = "",
    /** Snippets typed after login, in this order, before [startupCommand]. */
    val startupSnippetIds: List<String> = emptyList(),
    /** Requested on the session channel; the server decides which it accepts. */
    val env: List<EnvEntry> = emptyList(),
    /** Reach this host (or its first jump host) through this WireGuard tunnel. */
    val tunnelId: String? = null,
    val tunnelMode: TunnelMode = TunnelMode.WHEN_NEEDED,
    val wol: WolSettings = WolSettings(),
    /** Knock this sequence from the phone before dialing, for a host behind a knock daemon. */
    val knock: KnockSettings = KnockSettings(),
    /** Dial inside this Tailscale profile's tailnet; the hostname may be a MagicDNS name. */
    val tailscaleId: String? = null,
    /**
     * Offer this host to Android's file picker and the Files app as a storage
     * location. Off by default: every host appearing there would bury the
     * phone's own storage under a list of servers.
     */
    val showInFiles: Boolean = false,
    /**
     * Further addresses to try when the first does not answer, in order. Each
     * carries its own VPN choice, so a LAN address can be dialled directly and
     * a remote one through a tunnel or tailnet.
     */
    val addresses: List<HostAddress> = emptyList(),
    /** Attach to a tmux session on login and reconnect automatically when the link drops. */
    val persistent: Boolean = false,
    val tmuxSession: String = "main",
    /** Come back to whichever tmux session was last attached, not always the named one. */
    val tmuxResumeLast: Boolean = true,
    /** Regexes; a matching output line raises a notification while the app is in the background. */
    val notifyPatterns: List<String> = emptyList(),
    /** `ssh -A`: programs on the host may sign with the phone's keys (they never leave the phone). */
    val forwardAgent: Boolean = false,
    /**
     * Ask before the forwarded agent signs anything for this host; null follows
     * [Settings.confirmAgentSignatures].
     *
     * Per host because it is a question about the machine, not about the key: a
     * box you own and a shared build server deserve different answers, and the
     * one you set for one of them should not follow you to the other.
     */
    val askBeforeAgentSigning: Boolean? = null,
    /** Color scheme id (see Schemes) for this host only; null = the one from Settings. */
    val theme: String? = null,
    val protocol: Protocol = Protocol.SSH,
    /** Run the session over Mosh: start mosh-server over SSH, then speak UDP. */
    val mosh: Boolean = false,
    /**
     * Record every session to this host, without anyone having to remember to.
     * The format is the one chosen in Settings.
     */
    val recordSessions: Boolean = false,
    /**
     * What the host said it runs, read once per connection ("Debian GNU/Linux
     * 12 (bookworm)", "FreeBSD 14.1-RELEASE"). Blank until a session has been
     * up; kept from the last one, since a machine rarely changes what it is.
     */
    val detectedOs: String = "",
    /** When this host's shell history was last read, so it is not read every time. */
    val historyImportedAt: Long = 0,
    /**
     * Speak the kitty keyboard protocol to this host; null follows
     * [Settings.keyboardProtocol].
     *
     * Per host because it is a property of what runs there: a box with a
     * current neovim wants it, and a switch whose firmware terminal predates
     * the protocol is better off never hearing of it.
     */
    val keyboardProtocol: Boolean? = null,
    /** Answer image protocols on this host; null follows [Settings.terminalImages]. */
    val terminalImages: Boolean? = null,
    /** Offer the tmux chords and window list; null means "when this host attaches to tmux". */
    val tmuxControls: Boolean? = null,
    /** The prefix its tmux is configured with, as tmux itself writes it. */
    val tmuxPrefix: String = "C-b",
    /**
     * Font size for this host's sessions; 0 follows the app setting.
     *
     * Per host because the right size is a property of what you look at there:
     * a `btop` on a big server wants small text, a switch console with an
     * 80x24 firmware terminal wants large, and pinching one to suit should not
     * ruin the other.
     */
    val fontSizeSp: Float = 0f,
) {
    val isTelnet: Boolean get() = protocol == Protocol.TELNET
    val defaultPort: Int get() = if (isTelnet) 23 else 22
    val displayName: String
        get() = label.ifBlank { if (username.isBlank() || isTelnet) hostname else "$username@$hostname" }
    val target: String
        get() {
            val who = if (username.isBlank() || isTelnet) hostname else "$username@$hostname"
            return if (port == defaultPort) who else "$who:$port"
        }

    /**
     * What gets typed into the shell after login: [extra] first, then the
     * chosen snippets in the order they were picked, then this host's own
     * [startupCommand], and [tmux] last of all.
     *
     * The order is behavior rather than taste. The tmux attach line runs
     * `exec`, which replaces the shell — so anything placed after it would
     * never be read. Snippets that ask for a value are left out: there is
     * nobody at the keyboard to answer them at login, and typing the
     * `{{placeholder}}` verbatim is worse than not running the snippet.
     */
    fun startupLines(snippets: List<Snippet>, extra: String? = null, tmux: String? = null): String {
        val known = snippets.associateBy { it.id }
        val chosen = startupSnippetIds
            .mapNotNull { known[it] }
            .filter { it.placeholders.isEmpty() }
            .map { it.command.trim() }
            .filter { it.isNotEmpty() }
        val lines = listOfNotNull(extra?.trim()?.ifEmpty { null }) + chosen +
            listOfNotNull(startupCommand.trim().ifEmpty { null }, tmux)
        return lines.joinToString("\n")
    }
}

data class Identity(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val privateKey: String = "",
    val publicKey: String = "",
    val fingerprint: String = "",
    val passphrase: String = "",
    val created: Long = System.currentTimeMillis(),
    /**
     * The private key lives in the device's keystore and this app cannot read
     * it, so [privateKey] is empty and signing goes through the platform. Such a
     * key cannot be exported, backed up or synced — it belongs to this device.
     */
    val hardware: Boolean = false,
    /** Where the keystore put it, for the UI to state plainly. */
    val backing: String = "",
    /**
     * An OpenSSH certificate for this key, as the CA signed it
     * ("ssh-ed25519-cert-v01@openssh.com AAAA…"). Offered in place of the bare
     * public key, which is the whole point: the server trusts the CA and has
     * never heard of this key.
     */
    val certificate: String = "",
    /**
     * Ask for a fingerprint, face or the screen lock before every signature.
     *
     * Only a keystore key can promise this — the platform enforces it, so a
     * signature cannot happen without the person. A file-backed key would only
     * be asking politely.
     */
    val requireAuth: Boolean = false,
    /**
     * The private half is on a FIDO2 security key, not on this phone at all.
     *
     * The five `sk` fields below are the whole of what enrollment left behind;
     * without them the key is gone as surely as if the token had been lost, so
     * they travel together and none of them is optional.
     */
    val securityKey: Boolean = false,
    /** "Ed25519" or "EcdsaP256" — what the token was willing to make. */
    val skAlgorithm: String = "",
    /** The relying party id the credential was made under, "ssh:" by default. */
    val skApplication: String = "",
    /** Base64: 32 bytes for Ed25519, the uncompressed point for ECDSA. */
    val skPublicKey: String = "",
    /** Base64 handle the token needs back before it will sign. */
    val skCredentialId: String = "",
    /** Where it was enrolled, so signing reaches for that transport first. */
    val skTransport: String = "",
)

/**
 * One trusted server key, either under the name it was trusted for or, when it
 * came from a `known_hosts` written with `HashKnownHosts yes`, under OpenSSH's
 * `|1|salt|hash` host field.
 *
 * A hashed entry keeps [host] empty and [port] at zero on purpose: the name is
 * one-way hashed and cannot be recovered, so anything shown for it would be an
 * invention. Only [KnownHostMatch] can say whether such an entry is a given
 * server, and only by being handed a name to test.
 */
data class KnownHost(
    val host: String,
    val port: Int,
    val keyType: String,
    val keyBase64: String,
    val fingerprint: String,
    /** Base64 salt of a hashed entry, the HMAC key; empty for a plain one. */
    val hashSalt: String = "",
    /** Base64 HMAC-SHA1 of the host field, the value [hashSalt] keys. */
    val hashedHost: String = "",
) {
    val isHashed: Boolean get() = hashSalt.isNotEmpty() && hashedHost.isNotEmpty()

    /**
     * What tells one entry from another when they are merged or replaced. Every
     * hashed entry has the same empty name, so it has to be told apart by its
     * hash — which is exactly the host field OpenSSH wrote for it.
     */
    val identity: String get() = if (isHashed) "|1|$hashSalt|$hashedHost" else "$host:$port"

    /** Whether both entries stand for the same server, so that one replaces the other. */
    fun sameHostAs(other: KnownHost): Boolean = identity == other.identity

    companion object {
        /** Shown in place of a name that was hashed away. It must not pretend to know one. */
        const val HASHED_LABEL = "A host whose name is hashed"
    }
}

enum class VolumeModifierMode(val label: String, val help: String) {
    STICKY("Sticky", "Press once, it applies to the next key"),
    HOLD("Hold", "Active only while the button is held down"),
    TOGGLE("Toggle", "Press to lock, press again to release"),
}

enum class AppTheme(val label: String) { SYSTEM("System"), DARK("Dark"), LIGHT("Light") }

/** How hard to work at not spending mobile data. */
enum class DataSaver(val label: String, val help: String) {
    METERED("On mobile data", "Hold transfers and ease off keepalives when the connection is metered"),
    ALWAYS("Always", "Ease off keepalives on any connection; transfers still only wait on mobile data"),
    OFF("Off", "Behave the same on any connection"),
}

/** When Mosh should draw a keystroke before the server confirms it. */
enum class PredictiveEcho(val label: String, val help: String) {
    ADAPTIVE("When the link is slow", "Only once the round trip is long enough to notice"),
    ALWAYS("Always", "Even on a fast link"),
    NEVER("Never", "Wait for the server every time"),
}

/**
 * What a recorded session is written as.
 *
 * The plain log is for reading and grepping afterwards; the cast keeps the
 * timing, so `asciinema play` (or asciinema.org) replays the session as it
 * happened. Wanting both is common enough to be a choice of its own.
 */
enum class RecordingFormat(val label: String, val help: String) {
    TEXT("Text", "One .log file, escape sequences stripped, readable anywhere"),
    CAST("asciinema", "One .cast file for asciinema play, with the original timing"),
    BOTH("Both", "A .log to read and a .cast to replay");

    val text: Boolean get() = this != CAST
    val cast: Boolean get() = this != TEXT
}

/**
 * One call another app made over the automation intents.
 *
 * Kept so the answer to "what has been driving my sessions?" is in the app
 * rather than in somebody's memory of what they wired up months ago.
 */
data class AutomationCall(
    val at: Long = System.currentTimeMillis(),
    /** The app that sent it, or blank when Android would not say which. */
    val caller: String = "",
    /** What was asked for: "Run uptime on web1". */
    val what: String = "",
    /** What it answered — the same line the caller was handed back. */
    val result: String = "",
)

/** What the Caps Lock key does on a hardware keyboard instead of locking caps. */
enum class CapsLockAction(val label: String) { NONE("Caps Lock"), ESC("Escape"), CTRL("Control") }

/** The shape the cursor is drawn as, unless the program asks for another (DECSCUSR). */
enum class CursorStyle(val label: String) { BLOCK("Block"), UNDERLINE("Underline"), BAR("Bar") }

/** Which inline-image protocols a session answers to. */
enum class TerminalImages(val label: String) {
    OFF("Off"), KITTY("Kitty graphics"), SIXEL("Sixel"), BOTH("Both");

    val kitty: Boolean get() = this == KITTY || this == BOTH
    val sixel: Boolean get() = this == SIXEL || this == BOTH
}

/** What opens when a remote text file is tapped. */
enum class EditorChoice(val label: String) { BUILT_IN("Built-in editor"), EXTERNAL("Another app") }

/**
 * A key sequence on the chords sheet: a label to tap and the keys it sends.
 *
 * [keys] is written the way tmux and Emacs write them ("C-b c", "M-x",
 * "S-Tab", "Esc Esc") because that is how the documentation of every program
 * these chords drive already spells them — a chord copied out of a man page
 * should work without translation.
 */
data class Chord(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val keys: String = "",
    /** Which tab of the sheet it sits on: "tmux", "ctrl" or "agent". */
    val tab: String = "tmux",
)

/**
 * One hardware-keyboard chord bound to something the app does.
 *
 * Stored as the key's Android code plus the modifiers, rather than as text,
 * because the app has to answer the question "is this chord mine?" on every
 * key event, and parsing a string there would be work done a hundred times a
 * second for an answer that never changes.
 */
data class KeyBinding(
    /** A `KeyShortcuts.Shortcut` name; an unknown one is ignored on load. */
    val action: String = "",
    val keyCode: Int = 0,
    val ctrl: Boolean = true,
    val shift: Boolean = false,
    val alt: Boolean = false,
)

/**
 * A pattern that recolors what it matches in the terminal.
 *
 * Applied to the rows on screen as they are drawn, never to the bytes: the
 * scrollback keeps what the server actually sent, so turning a rule off puts
 * the original colors straight back.
 */
data class HighlightRule(
    val id: String = UUID.randomUUID().toString(),
    val pattern: String = "",
    /** ARGB; 0 means "leave the color alone" and only [wholeLine] applies. */
    val color: Int = 0,
    /** Color the whole row rather than the match. */
    val wholeLine: Boolean = false,
    val enabled: Boolean = true,
)

data class Settings(
    val fontSizeSp: Float = 13f,
    /** A TermFonts family id ("jetbrains", "fira", "hack" or "file:<name>"). */
    val fontFamily: String = "jetbrains",
    /** Programming ligatures (=> -> !=) for fonts that have them. */
    val ligatures: Boolean = false,
    /** Predictive local echo for Mosh sessions. */
    val predictiveEcho: PredictiveEcho = PredictiveEcho.ADAPTIVE,
    /** Data saving: fewer keepalives, transfers held for Wi-Fi. */
    val dataSaver: DataSaver = DataSaver.METERED,
    /** The color scheme id sessions run in unless their host or group says otherwise. */
    val theme: String = Schemes.DEFAULT_ID,
    /** Schemes the user imported; their ids are valid anywhere a scheme id goes. */
    val customSchemes: List<TermScheme> = emptyList(),
    val scrollback: Int = 10_000,
    val vibrateOnBell: Boolean = true,
    val keepScreenOn: Boolean = true,
    val keepaliveSeconds: Int = 30,
    val appTheme: AppTheme = AppTheme.DARK,
    /** Use the wallpaper-derived Material You palette instead of the built-in one. */
    val dynamicColor: Boolean = false,
    val showHiddenFiles: Boolean = true,
    /** Two rows of extra-key tokens (see ExtraKeys.catalog); null = defaults. */
    val extraKeysRow1: List<String>? = null,
    val extraKeysRow2: List<String>? = null,
    val hideExtraKeysWithHardwareKeyboard: Boolean = true,
    /** What the volume keys do in the terminal: an extra-keys token ("CTRL", "ESC", "SNIPPETS", literal text…) or "NONE". */
    val volumeDownAction: String = "NONE",
    val volumeUpAction: String = "NONE",
    /** How a modifier bound to each volume key behaves. */
    val volumeDownMode: VolumeModifierMode = VolumeModifierMode.STICKY,
    val volumeUpMode: VolumeModifierMode = VolumeModifierMode.STICKY,
    val appLock: Boolean = false,
    /** Re-lock after this many seconds in the background (0 = every time). */
    val appLockGraceSeconds: Int = 60,
    val notifyOnBell: Boolean = true,
    /**
     * Tell me when a long command finishes while the app is in the background.
     *
     * "Long" is the point: a notification for every `ls` would be noise, so
     * only a command that ran long enough for somebody to have walked away
     * counts (see [dev.flint.term.session.CommandWatch]).
     */
    val notifyOnCommandFinish: Boolean = true,
    /**
     * Read a host's own shell history on connect, so what you have run there
     * before can complete what you type. It is one small file read over the
     * existing connection, once a week per host.
     */
    val importShellHistory: Boolean = true,
    /** Swiping sideways in the terminal moves to the next session. */
    val swipeBetweenSessions: Boolean = true,
    /**
     * The strip of session names under the terminal's top bar. Off gives the
     * rows back to the terminal; the sessions are still in the ⋮ menu.
     */
    val showSessionTabs: Boolean = true,
    /**
     * Offer the rest of a command from this host's history as you type: dim
     * text after the cursor, and the other matches in a list once you pause.
     */
    val completeFromHistory: Boolean = true,
    /** Tab takes the suggestion when one is showing, instead of reaching the shell. */
    val tabAcceptsSuggestion: Boolean = true,
    /** Two taps on the terminal send Tab, as Termius does. */
    val doubleTapSendsTab: Boolean = true,
    /** What a session recording is written as, whoever asked for it. */
    val recordingFormat: RecordingFormat = RecordingFormat.TEXT,
    /**
     * Resolver used while a host is acting as a VPN. Asked over TCP *from the
     * server*, so an internal resolver here is what makes internal names work.
     */
    val vpnResolver: String = "1.1.1.1",
    /**
     * Where a file dropped on the terminal, or picked with "Insert a file",
     * lands on the host.
     *
     * Somewhere temporary by default, because inserting a file is nearly always
     * about naming it in the next command rather than keeping it — and a home
     * directory silting up with screenshots is its own kind of mess.
     */
    val terminalUploadDir: String = "/tmp",
    /**
     * Ask before a host with agent forwarding on signs anything with a key kept
     * here — `ssh-add -c`, in other words.
     *
     * On by default because the alternative is the thing agent forwarding is
     * warned about: while you are connected, whoever controls that machine can
     * sign as you and walk on to every other host the key opens. A host can
     * override this either way.
     */
    val confirmAgentSignatures: Boolean = true,
    /**
     * Let other apps connect, run commands and close sessions over the
     * broadcast intents in `dev.flint.term.session.IntentApi`.
     *
     * Off until somebody turns it on: this is a remote control on a client that
     * holds keys.
     */
    val automation: Boolean = false,
    /**
     * The packages allowed to use it. An app that calls while it is not on this
     * list is turned away and put in [automationLog], where it can be allowed
     * with one tap — so the first call from anything is a question rather than
     * a command.
     */
    val automationAllowed: List<String> = emptyList(),
    /** The last few automation calls, newest first. Shown under the switch. */
    val automationLog: List<AutomationCall> = emptyList(),
    /**
     * Speak the kitty keyboard protocol when a program asks for it, and
     * xterm's modifyOtherKeys when it asks for that.
     *
     * On, because a program only gets it by asking: nothing changes for a
     * shell that never enables it, and everything changes for the editors and
     * agents that do — Ctrl+[ stops being Escape, Shift+Enter arrives, and
     * Ctrl+Shift+letter is finally distinguishable.
     */
    val keyboardProtocol: Boolean = true,
    /**
     * Whether Ctrl+letter writes the control byte itself rather than going out
     * as a key event, from the key bar and from the menu alike.
     *
     * On, because Ctrl+C is the only interrupt a phone has. Under the keyboard
     * protocol it would leave as `CSI 99;5u`, which is correct and which a
     * program that has stopped listening will never read. The letters whose
     * control code is another key are left alone either way, so Ctrl+[ and
     * Escape stay distinguishable. Off is strictly protocol-faithful.
     */
    val rawControlKeys: Boolean = true,
    /** Tapping a modifier cap twice quickly locks it, instead of clearing it. */
    val doubleTapLocksModifier: Boolean = true,
    /** Dragging two fingers on the terminal sends arrow keys. */
    val twoFingerDragArrows: Boolean = true,
    val capsLockAs: CapsLockAction = CapsLockAction.NONE,
    /** Keep the compose line open when leaving and coming back to a session. */
    val composeRemembersState: Boolean = true,
    /** The tmux chords sheet, the window list and the swipe that changes window. */
    val tmuxControls: Boolean = true,
    /** Holding the Ctrl cap opens the chords sheet (a tap is the modifier). */
    val ctrlLongPressOpensChords: Boolean = true,
    /** The chords sheet, in the order it shows them; empty = the built-in set. */
    val chords: List<Chord> = emptyList(),
    /** Show what the server prints before login — its banner. */
    val showAuthBanners: Boolean = true,
    /** Look for SSH servers announcing themselves on the local network. */
    val discoverNearbyHosts: Boolean = true,
    /** Reopen the sessions that were open when the app was last killed. */
    val restoreSessions: Boolean = true,
    /** Let a program raise a notification with an escape sequence (OSC 9, 99, 777). */
    val notifyFromEscapes: Boolean = true,
    /**
     * Float the terminal automatically when leaving the app, rather than only
     * when asked to.
     *
     * Off, because it turns every trip to another app into a window that has
     * to be dismissed.
     */
    val pipOnLeave: Boolean = false,
    val editor: EditorChoice = EditorChoice.BUILT_IN,
    /** Keep a `.bak` beside a file the built-in editor saves. */
    val editorBackup: Boolean = false,
    /** Draw private-use codepoints with the bundled Nerd Font symbols. */
    val nerdGlyphs: Boolean = true,
    /** Draw bold text in the bright half of the palette, as older terminals did. */
    val boldIsBright: Boolean = false,
    val cursorStyle: CursorStyle = CursorStyle.BLOCK,
    val cursorBlink: Boolean = false,
    val highlightEnabled: Boolean = false,
    val highlightRules: List<HighlightRule> = emptyList(),
    /** Which inline-image protocols sessions answer to. */
    val terminalImages: TerminalImages = TerminalImages.BOTH,
    /**
     * Hardware-keyboard chords the app answers instead of the shell; empty
     * means the built-in set.
     *
     * Editable because which chords are free depends on what you run: a chord
     * this app claims is one the remote will never see, and only the person at
     * the keyboard knows whether that trade is worth making.
     */
    val shortcuts: List<KeyBinding> = emptyList(),
)
