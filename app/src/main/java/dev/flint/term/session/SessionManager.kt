package dev.flint.term.session

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.flint.term.App
import dev.flint.term.core.AuthMethod
import dev.flint.term.core.Backend
import dev.flint.term.core.Endpoint
import dev.flint.term.core.EnvVar
import dev.flint.term.core.ExternalConfig
import dev.flint.term.core.HostKey
import dev.flint.term.core.JumpHop
import dev.flint.term.core.LocalShellConfig
import dev.flint.term.core.MoshConfig
import dev.flint.term.core.ProxyConfig
import dev.flint.term.core.ProxyKind
import dev.flint.term.core.SessionState
import dev.flint.term.core.SshConfig
import dev.flint.term.core.TelnetConfig
import dev.flint.term.data.AuthType
import dev.flint.term.data.CommandHistory
import dev.flint.term.data.Host
import dev.flint.term.data.KnownHost
import dev.flint.term.data.ProxySettings
import dev.flint.term.data.ProxyType
import dev.flint.term.data.Store
import dev.flint.term.data.WolSource
import dev.flint.term.data.requestable
import dev.flint.term.security.SecurityKeyIdentity
import dev.flint.term.terminal.GridMemory
import dev.flint.term.terminal.Palettes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SessionManager(
    private val context: Context,
    private val store: Store,
    /** Connectivity, for reconnect backoff and data saving. */
    private val network: NetworkMonitor,
) {
    /** Set by the app once the Tailscale manager exists; used to bring the node up on demand. */
    var tailscale: TailscaleManager? = null
    /** Set by the app; used to stop WireGuard tunnels once no session needs them. */
    lateinit var tunnels: TunnelManager
    /** Set by the app; keeps the "what was open" list on disk in step with this one. */
    var openSessions: OpenSessions? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions

    /** (old session id, new session id) when a session was replaced by an automatic reconnect. */
    private val _replacements = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    val replacements: SharedFlow<Pair<String, String>> = _replacements

    /** A pending host-key question for the UI to answer. */
    private val _hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    val hostKeyPrompt: StateFlow<HostKeyPrompt?> = _hostKeyPrompt

    /**
     * A pending login question, from whichever connection is asking it.
     *
     * Kept here rather than on the session because a headless connect — a
     * snippet, a broadcast, the widget — can walk into a verification code with
     * no terminal on screen to put the question on.
     */
    private val prompts = AuthPrompts()
    val authPrompt: StateFlow<AuthPrompt?> = prompts.pending

    init {
        // A switch flipped in settings has to reach the terminals that are
        // already on screen. Reconnecting to pick up a preference would be a
        // strange thing to ask of anybody, and the core can take the change
        // while a session is up.
        scope.launch {
            store.settings.collect { settings ->
                _sessions.value.forEach { it.setOptions(settings.coreOptions(it.host)) }
            }
        }
    }

    fun get(id: String): TerminalSession? = _sessions.value.firstOrNull { it.id == id }

    private fun authFor(host: Host): List<AuthMethod> {
        val auth = mutableListOf<AuthMethod>()
        when (host.authType) {
            AuthType.PASSWORD -> auth += AuthMethod.Password(host.password)
            AuthType.KEY -> store.identity(host.identityId)?.let { id ->
                // Neither a keystore key nor a security key can be read, so the
                // core is given something that signs rather than something that
                // is a key. Any of the three may carry a certificate, which then
                // goes on the wire in place of the public key.
                if (id.securityKey) {
                    auth += AuthMethod.SecurityKey(
                        SecurityKeyIdentity.credentialOf(id),
                        SecurityKeyIdentity.token(context, id),
                        id.name.ifBlank { "security-key" },
                        id.certificate,
                    )
                } else if (id.hardware) {
                    auth += AuthMethod.Keystore(
                        dev.flint.term.data.HardwareKeys.signer(id.id, id.name.ifBlank { "flintterm" }),
                        id.certificate,
                    )
                } else {
                    auth += AuthMethod.Key(id.privateKey, id.passphrase.ifEmpty { null }, id.certificate)
                }
            }
            AuthType.NONE -> {}
        }
        if (host.authType == AuthType.KEY && host.password.isNotEmpty()) auth += AuthMethod.Password(host.password)
        return auth
    }

    /** The username a host actually logs in with, its account included. */
    fun loginName(host: Host): String = store.effective(host).username

    private fun proxyFor(p: ProxySettings): ProxyConfig? = if (!p.enabled) null else ProxyConfig(
        kind = if (p.type == ProxyType.HTTP) ProxyKind.HTTP else ProxyKind.SOCKS5,
        host = p.host.trim(),
        port = p.port.toUShort(),
        username = p.username.ifBlank { null },
        password = p.password.ifEmpty { null },
    )

    /** True when we should be easing off on chatter — keepalives and the like. */
    fun savingData(): Boolean = when (store.settings.value.dataSaver) {
        dev.flint.term.data.DataSaver.OFF -> false
        dev.flint.term.data.DataSaver.ALWAYS -> true
        dev.flint.term.data.DataSaver.METERED -> network.metered
    }

    /**
     * True when a file transfer should wait rather than run now.
     *
     * This asks about the *actual* connection, not the mode: "Always" means be
     * frugal with chatter on any link, but a transfer can only ever wait for a
     * cheaper connection to appear. Keying it off the mode would leave transfers
     * queued for ever on Wi-Fi, since there would be nothing better to wait for.
     */
    fun holdTransfers(): Boolean =
        store.settings.value.dataSaver != dev.flint.term.data.DataSaver.OFF && network.metered

    /**
     * Keepalives exist to notice a dead link and to hold NAT open. On a metered
     * connection they are also a steady trickle of data and radio wake-ups, so
     * they are spaced out rather than switched off — Mosh and tmux already cover
     * the case where the link drops.
     */
    private fun keepaliveFor(settings: dev.flint.term.data.Settings): Int =
        if (savingData()) (settings.keepaliveSeconds * 3).coerceAtMost(300) else settings.keepaliveSeconds

    /** What to call a host's VPN in the connection steps. */
    private fun vpnName(tunnelId: String?, tailscaleId: String?): String = when {
        tailscaleId != null -> store.tailscaleProfile(tailscaleId)?.name?.takeIf { it.isNotBlank() } ?: "Tailscale"
        tunnelId != null -> store.tunnel(tunnelId)?.name?.takeIf { it.isNotBlank() } ?: "tunnel"
        else -> ""
    }

    private class Prepared(
        val backend: Backend,
        val wakeFromPhone: Boolean,
        val wakeReason: String?,
        val tunnelNote: String?,
        /** Profile whose Tailscale node the first hop is dialled through; it may still be starting. */
        val tailscaleId: String? = null,
        /** Sent from the phone before anything is dialled; null when this host does not knock. */
        val knock: KnockPlan? = null,
    )

    /** Everything the core needs to reach [host]: jump chain, tunnel, proxy, wake-on-LAN. */
    private fun prepare(raw: Host, forceWake: Boolean, allowWake: Boolean = true, allowMosh: Boolean = true): Prepared {
        val settings = store.settings.value
        // A host and every hop it goes through may borrow its login from a
        // saved account, so the whole chain is resolved before anything is read
        // off it.
        val host = store.effective(raw)
        val chain = store.jumpChain(host).map { store.effective(it) }
        val knock = PortKnock.plan(host)
        val wake = allowWake && host.wol.enabled && (host.wol.autoWake || forceWake) && Wol.isValidMac(host.wol.mac)
        val decision = if (wake) Wol.decide(host, chain.isNotEmpty()) else null
        // WOL from the jump host: prepend the script to the pre-connect command run there.
        val wakeViaJump = decision?.source == WolSource.JUMP_HOST
        val wakeFromPhone = decision?.source == WolSource.PHONE
        // Tunnel only when needed: a private address that sits on one of the phone's networks is
        // dialled directly; other private addresses go through the tunnel; names and public
        // addresses try a short direct connection first (the core falls back to the tunnel).
        val entry = chain.firstOrNull() ?: host
        var tunnelId = entry.tunnelId
        var tunnelFallback = false
        var tunnelNote: String? = null
        if (tunnelId != null) {
            val tunnelName = store.tunnel(tunnelId)?.name ?: "tunnel"
            val ip = entry.hostname.trim()
            when {
                entry.tunnelMode == dev.flint.term.data.TunnelMode.ALWAYS -> tunnelNote = "Using $tunnelName"
                Wol.isPrivateIpv4(ip) -> {
                    val lan = Wol.localNetworkContaining(ip, ip)
                    if (lan != null) {
                        tunnelId = null
                        tunnelNote = "${entry.displayName} is on your current network ($lan), connecting directly"
                    } else {
                        tunnelNote = "${entry.displayName} is not on your current network, using $tunnelName"
                    }
                }
                else -> {
                    tunnelFallback = true
                    tunnelNote = "$tunnelName is used only if ${entry.displayName} is not reachable directly"
                }
            }
        }
        val jumps = chain.map { j ->
            JumpHop(
                host = j.hostname.trim(),
                port = j.port.toUShort(),
                username = j.username.trim(),
                auth = authFor(j),
                preCommand = null,
                waitForNextSecs = 0u,
                proxy = proxyFor(store.proxy(j.proxyId)?.settings() ?: j.proxy),
                tunnelId = null,
                tailscaleId = null,
                forwardAgent = j.forwardAgent,
            )
        }.toMutableList()
        // The pre-connect command / wait belong to the host being reached *through* a hop,
        // so attach them to the hop that precedes it in the chain.
        val reached = chain.drop(1) + host
        for (i in jumps.indices) {
            val next = reached[i]
            val pre = buildList {
                if (next.id == host.id && wakeViaJump) add(Wol.remoteScript(host.wol, Wol.wakeTarget(host)))
                if (next.preConnectCommand.isNotBlank()) add(next.preConnectCommand)
            }.joinToString("\n")
            jumps[i] = jumps[i].copy(
                preCommand = pre.ifBlank { null },
                waitForNextSecs = next.waitForHostSeconds.coerceIn(0, 3600).toUInt(),
                tunnelId = if (i == 0) tunnelId else null,
                tailscaleId = if (i == 0) chain.first().tailscaleId else null,
            )
        }
        // Telnet has no auth, no jump chain and no agent — just the socket (through the
        // tunnel or Tailscale when the host is set up for one).
        if (host.isTelnet) {
            val telnet = Backend.Telnet(
                TelnetConfig(
                    host = host.hostname.trim(),
                    port = host.port.toUShort(),
                    connectTimeoutSecs = 20u,
                    waitForHostSecs = if (wake) host.waitForHostSeconds.coerceIn(0, 3600).toUInt() else 0u,
                    tunnelId = tunnelId,
                    tailscaleId = host.tailscaleId,
                ),
            )
            host.tailscaleId?.let { tailscale?.ensureUp(it) }
            return Prepared(telnet, wakeFromPhone, decision?.reason, tunnelNote, tailscaleId = host.tailscaleId, knock = knock)
        }
        val sshConfig = SshConfig(
                host = host.hostname.trim(),
                port = host.port.toUShort(),
                username = host.username.trim(),
                auth = authFor(host),
                keepaliveSecs = keepaliveFor(settings).toUInt(),
                connectTimeoutSecs = 20u,
                proxy = if (chain.isEmpty()) proxyFor(store.proxy(host.proxyId)?.settings() ?: host.proxy) else null,
                jumps = jumps,
                tunnelId = if (chain.isEmpty()) tunnelId else null,
                tunnelFallback = tunnelFallback,
                // A direct connection to a machine we just woke up needs the same patience as a jump.
                waitForHostSecs = if (chain.isEmpty() && wake) host.waitForHostSeconds.coerceIn(0, 3600).toUInt() else 0u,
                tailscaleId = host.tailscaleId.takeIf { chain.isEmpty() },
                vpnName = vpnName(host.tunnelId, host.tailscaleId),
                // Extra addresses only make sense on a direct connection: with a
                // jump chain the route is the chain, not the address.
                alternates = if (chain.isEmpty()) {
                    host.addresses.filter { it.hostname.isNotBlank() }.map { a ->
                        Endpoint(
                            label = a.label.trim(),
                            host = a.hostname.trim(),
                            port = (a.port.takeIf { it > 0 } ?: host.port).toUShort(),
                            tunnelId = a.tunnelId,
                            tailscaleId = a.tailscaleId,
                            vpnName = vpnName(a.tunnelId, a.tailscaleId),
                        )
                    }
                } else {
                    emptyList()
                },
                forwardAgent = host.forwardAgent,
                // Every stored key is offered; signing happens on the phone, keys never leave it.
                // Only keys this app can actually sign with are offered to the
                // agent; a keystore key would need the platform on every request
                // and a security key a touch, which is a separate piece of work.
                agentKeys = if (host.forwardAgent || chain.any { it.forwardAgent }) {
                    store.identities.value.filterNot { it.hardware || it.securityKey }.map { AuthMethod.Key(it.privateKey, it.passphrase.ifEmpty { null }, it.certificate) }
                } else {
                    emptyList()
                },
                // Only when something is actually forwarded: without a key on
                // the wire there is nothing to ask about.
                agentApproval = if (dev.flint.term.data.asksBeforeAgentSigning(listOf(host) + chain, store.settings.value.confirmAgentSignatures)) {
                    AgentGate.approvalFor(host.displayName)
                } else {
                    null
                },
                env = host.env.requestable().map { EnvVar(it.name, it.value) },
        )
        // Mosh bootstraps over this same SSH config and then leaves SSH behind.
        val backend = if (host.mosh && chain.isEmpty() && allowMosh) {
            Backend.Mosh(MoshConfig(ssh = sshConfig, locale = "en_US.UTF-8", server = ""))
        } else {
            Backend.Ssh(sshConfig)
        }
        // Only the first hop can be dialled inside a tailnet; later hops ride the SSH chain.
        val tailscaleId = if (chain.isEmpty()) host.tailscaleId else chain.first().tailscaleId
        tailscaleId?.let { tailscale?.ensureUp(it) }
        return Prepared(backend, wakeFromPhone, decision?.reason, tunnelNote, tailscaleId, knock)
    }

    /** Connect (walking the jump chain), run one command, disconnect. Blocking; call from IO. */
    fun runCommand(host: Host, command: String): Result<String> = runCatching {
        // Plain SSH even for a Mosh host: one command needs an exec channel, and
        // going through the Mosh bootstrap would only take the channel away again.
        val prepared = prepare(host, forceWake = false, allowWake = false, allowMosh = false)
        prepared.knock?.let { PortKnock.send(it) }
        val backend = prepared.backend
        val session = TerminalSession(host = host, label = host.displayName, backend = backend, initialCols = 80, initialRows = 24, scrollback = 100, palette = Palettes.forTheme(host.theme, store.settings.value.theme), options = store.settings.value.coreOptions(host), verifyHostKey = ::verifyHostKey, prompter = prompts.prompter())
        try {
            session.core.execOnce(command)
        } finally {
            session.destroy()
        }
    }

    /**
     * Read the host's own shell history and keep it for completions.
     *
     * The best suggestions for a machine are the commands already run on it, and
     * most of those were typed at a desk, not on this phone. Read over the same
     * headless SSH path the file browser uses; a host with neither file is simply
     * left alone.
     */
    suspend fun importShellHistory(host: Host): Result<Int> = runCatching {
        val (session, sftp) = openSftpSession(host)
        try {
            val home = withContext(Dispatchers.IO) { sftp.home() }.trimEnd('/')
            val found = listOf(".zsh_history", ".bash_history", ".local/share/fish/fish_history")
                .mapNotNull { name ->
                    withContext(Dispatchers.IO) {
                        runCatching { sftp.readText("$home/$name", HISTORY_MAX_BYTES) }.getOrNull()
                    }
                }
            val commands = found.flatMap { CommandHistory.parseShellHistory(it) }
            // The repetitions in the file are what says which of these the
            // person actually lives on, so they are counted, not just listed.
            val counts = found.map { CommandHistory.countShellHistory(it) }
                .fold(mutableMapOf<String, Int>()) { acc, m ->
                    m.forEach { (c, n) -> acc[c] = (acc[c] ?: 0) + n }
                    acc
                }
            store.addHistory(host.id, commands, counts)
            commands.size
        } finally {
            runCatching { sftp.shutdown() }
            session.destroy()
        }
    }

    /**
     * An SSH connection made for file access rather than a terminal: it never
     * appears in the session list and answers to nobody's screen.
     *
     * Used by the Files-app provider, which has no UI of its own — so an unknown
     * host key is refused here rather than prompting into the void. Connect the
     * host once in the app to decide about its key, and this path works from then on.
     */
    suspend fun openSftpSession(host: Host): Pair<TerminalSession, dev.flint.term.core.SftpClient> {
        val session = connectHeadless(host)
        return try {
            session to withContext(Dispatchers.IO) { session.core.openSftp() }
        } catch (e: Throwable) {
            session.destroy()
            throw e
        }
    }

    /**
     * An SSH connection with no terminal behind it, connected and ready.
     *
     * The caller owns it and must destroy it. Nothing about it reaches the
     * session list, so a background job — the file provider, the VPN — does not
     * put a tab on the user's screen.
     */
    suspend fun connectHeadless(host: Host): TerminalSession {
        // Plain SSH even for a Mosh host: what these connections do — SFTP, a
        // channel per connection — is SSH, and the Mosh bootstrap would only be
        // a slower way to arrive.
        val prepared = prepare(host, forceWake = false, allowWake = false, allowMosh = false)
        val session = TerminalSession(
            host = host,
            label = host.displayName,
            backend = prepared.backend,
            initialCols = 80,
            initialRows = 24,
            scrollback = 100,
            palette = Palettes.forTheme(host.theme, store.settings.value.theme),
            options = store.settings.value.coreOptions(host),
            verifyHostKey = { key ->
                val known = store.knownHost(key.host, key.port.toInt())
                known != null && known.keyType == key.keyType && known.keyBase64 == key.keyBase64
            },
            // A host key nobody is looking at cannot be trusted for the first
            // time, but a code the person has on them can still be typed: the
            // dialog appears over whichever screen they are on.
            prompter = prompts.prompter(),
        )
        try {
            prepared.tailscaleId?.let { tailscale?.awaitUp(it) }
            prepared.knock?.let { withContext(Dispatchers.IO) { PortKnock.send(it) } }
            session.start()
            val state = withTimeoutOrNull(HEADLESS_CONNECT_TIMEOUT_MS) {
                session.state.first { it !is SessionState.Connecting }
            }
            if (state !is SessionState.Connected) {
                val why = (state as? SessionState.Disconnected)?.error ?: "could not connect"
                throw java.io.IOException("${host.displayName}: $why")
            }
            return session
        } catch (e: Throwable) {
            session.destroy()
            throw e
        }
    }

    fun openSsh(
        host: Host,
        cols: Int = GridMemory.cols.takeIf { it > 0 } ?: 80,
        rows: Int = GridMemory.rows.takeIf { it > 0 } ?: 24,
        /** Extra command typed after login (e.g. a manual wake-up script); runs before the host's own startup command. */
        extraStartup: String? = null,
        /** Send the Wake-on-LAN packet even when auto-wake is off. */
        forceWake: Boolean = false,
    ): TerminalSession {
        val settings = store.settings.value
        val prepared = prepare(host, forceWake)
        val backend = prepared.backend
        val wakeFromPhone = prepared.wakeFromPhone
        val session = TerminalSession(
            host = host,
            label = host.displayName,
            backend = backend,
            initialCols = cols,
            initialRows = rows,
            scrollback = settings.scrollback,
            palette = Palettes.forTheme(host.theme, settings.theme),
            options = settings.coreOptions(host),
            verifyHostKey = ::verifyHostKey,
            prompter = prompts.prompter(),
        )
        prepared.tunnelNote?.let { n -> if (!session.destroyed) runCatching { session.core.note(n) } }
        prepared.wakeReason?.let { reason -> if (!session.destroyed) runCatching { session.core.note("Wake-on-LAN: $reason") } }
        prepared.knock?.let { k -> if (!session.destroyed) runCatching { session.core.note("Knocking ${PortKnock.format(k.steps)} on ${k.hostname}") } }
        if (wakeFromPhone) {
            // Magic packet first, then the connection retries while the box boots.
            scope.launch(Dispatchers.IO) {
                val r = runCatching { Wol.sendFromPhone(host.wol, Wol.candidates(host)) }
                if (!session.destroyed) runCatching {
                    session.core.note(
                        r.fold(
                            { sent -> "Sent Wake-on-LAN to ${Wol.normalizeMac(host.wol.mac)} via ${sent.joinToString(", ")}" },
                            { "Wake-on-LAN failed: ${it.message}" },
                        ),
                    )
                }
            }
        }
        if (host.notifyPatterns.isNotEmpty()) runCatching { session.core.setWatchPatterns(host.notifyPatterns.filter { it.isNotBlank() }) }
        register(session, prepared.tailscaleId, prepared.knock)
        store.touchHost(host.id)
        Shortcuts.updateDynamic(context, store.hosts.value)
        watchForAlerts(session, host)
        // Once connected: auto-start forwards and type the startup command.
        val autoForwards = host.forwards.filter { it.autoStart }
        val tmux = if (host.persistent) Tmux.attachCommand(host.tmuxSession, host.tmuxResumeLast) else null
        val startup = host.startupLines(store.snippets.value, extraStartup, tmux)
        if (autoForwards.isNotEmpty() || startup.isNotEmpty()) {
            scope.launch {
                val connected = withTimeoutOrNull(CONNECT_WINDOW_MS) { session.state.first { it !is SessionState.Connecting } }
                if (connected is SessionState.Connected) {
                    autoForwards.forEach { f -> launch(Dispatchers.IO) { session.startForward(f) } }
                    if (startup.isNotEmpty()) {
                        kotlinx.coroutines.delay(400)
                        if (!session.destroyed) runCatching { session.core.sendText(startup + "\n") }
                    }
                }
            }
        }
        // Telnet consoles have no shell to ask, and no exec channel to ask over.
        if (!host.isTelnet) {
            detectOs(session, host)
            learnHistory(session, host)
        }
        return session
    }

    /**
     * Ask a connected host what it runs, once, and remember the answer.
     *
     * One exec channel on the connection that is already open, so it costs no
     * login and nothing on the terminal's own path. Entirely optional, too: it
     * starts only after the session is up, and any failure — an old sshd, a
     * locked-down shell, a Mosh session that has already left SSH behind —
     * leaves the host with whatever it said last time and is not reported.
     */
    private fun detectOs(session: TerminalSession, host: Host) {
        scope.launch {
            val state = withTimeoutOrNull(CONNECT_WINDOW_MS) { session.state.first { it !is SessionState.Connecting } }
            if (state !is SessionState.Connected || session.destroyed) return@launch
            // The core asks while it still has an SSH connection in hand — the
            // only moment a Mosh session has one — so this just collects it.
            val answer = runCatching { session.core.detectedOs() }.getOrNull()?.trim()
            if (!answer.isNullOrEmpty() && answer != host.detectedOs) store.setDetectedOs(host.id, answer)
        }
    }

    /**
     * Read the host's own shell history in the background, so completions know
     * what has been run on this machine.
     *
     * Nothing to press: the commands you would want suggested are the ones you
     * have already run there, and asking the user to go and fetch them was
     * work for no reason. It costs one small file read on a connection that is
     * already open, at most once a week per host, and a host with neither file
     * is quietly left alone.
     */
    private fun learnHistory(session: TerminalSession, host: Host) {
        if (!store.settings.value.importShellHistory) return
        if (System.currentTimeMillis() - host.historyImportedAt < HISTORY_REFRESH_MS) return
        scope.launch {
            val state = withTimeoutOrNull(CONNECT_WINDOW_MS) { session.state.first { it !is SessionState.Connecting } }
            if (state !is SessionState.Connected || session.destroyed) return@launch
            // Marked before the attempt as well as after: a host whose history
            // cannot be read should not be asked again on every connection.
            store.markHistoryImported(host.id)
            importShellHistory(host)
                .onSuccess { n -> if (n > 0) android.util.Log.i("SessionManager", "learned $n commands from ${host.displayName}") }
                .onFailure { android.util.Log.d("SessionManager", "no shell history from ${host.displayName}: ${it.message}") }
        }
    }

    /**
     * Attach a terminal to a USB serial port. The port must already be open;
     * the returned session owns it and closes it when it ends.
     */
    fun openSerial(
        device: SerialDevice,
        settings: SerialSettings,
        port: com.hoho.android.usbserial.driver.UsbSerialPort,
        cols: Int = GridMemory.cols.takeIf { it > 0 } ?: 80,
        rows: Int = GridMemory.rows.takeIf { it > 0 } ?: 24,
    ): TerminalSession {
        val s = store.settings.value
        val label = "${device.label} · ${settings.summary}"
        val session = TerminalSession(
            host = null,
            label = device.label,
            backend = Backend.External(ExternalConfig(label)),
            initialCols = cols,
            initialRows = rows,
            scrollback = s.scrollback,
            palette = Palettes.forTheme(s.theme),
            options = s.coreOptions(null),
            verifyHostKey = { true },
        )
        val link = SerialConnection(port, session)
        session.core.setExternalSink(link)
        register(session)
        // Only start reading once the session is pumping, or early output is lost.
        link.start()
        return session
    }

    /**
     * A terminal with nothing behind it, for replaying a recording into.
     *
     * The same emulator the live sessions use, so a cast plays back with its
     * colors and its cursor moves rather than as a wall of text. A recording is
     * not a session and is not registered — it should never appear in the tab
     * strip or keep the service alive — but the same terminal is also how a
     * container's log is shown, and that one is a tab you close like any other,
     * so [asTab] puts it in the session list.
     */
    fun openPlayback(label: String, cols: Int, rows: Int, asTab: Boolean = false): TerminalSession {
        val s = store.settings.value
        val session = TerminalSession(
            host = null,
            label = label,
            backend = Backend.External(ExternalConfig(label)),
            initialCols = cols,
            initialRows = rows,
            scrollback = s.scrollback,
            palette = Palettes.forTheme(s.theme),
            options = s.coreOptions(null),
            verifyHostKey = { true },
        )
        // Keystrokes have nowhere to go, and the session ends when the caller
        // says so rather than when a device disappears.
        session.core.setExternalSink(object : dev.flint.term.core.ExternalSink {
            override fun onInput(data: ByteArray) = Unit
            override fun onResize(cols: UShort, rows: UShort) = Unit
            override fun onClose() = Unit
        })
        // register starts it; on its own it has to be started here.
        if (asTab) register(session) else session.start()
        return session
    }

    fun openLocal(cols: Int = GridMemory.cols.takeIf { it > 0 } ?: 80, rows: Int = GridMemory.rows.takeIf { it > 0 } ?: 24): TerminalSession {
        val settings = store.settings.value
        val home = context.filesDir.absolutePath
        val env = listOf(
            EnvVar("HOME", home),
            EnvVar("TERM", "xterm-256color"),
            EnvVar("PATH", "/system/bin:/system/xbin:/product/bin:/apex/com.android.runtime/bin"),
            EnvVar("TMPDIR", context.cacheDir.absolutePath),
            EnvVar("LANG", "en_US.UTF-8"),
            // mksh: keep the prompt plain; $PWD is expanded at prompt time.
            EnvVar("PS1", "\$PWD \$ "),
        )
        val backend = Backend.Local(LocalShellConfig("/system/bin/sh", listOf("-l"), env, home))
        val session = TerminalSession(
            host = null,
            label = "Local shell",
            backend = backend,
            initialCols = cols,
            initialRows = rows,
            scrollback = settings.scrollback,
            palette = Palettes.forTheme(settings.theme),
            options = settings.coreOptions(null),
            verifyHostKey = { true },
        )
        register(session)
        return session
    }

    private fun register(session: TerminalSession, tailscaleId: String? = null, knock: KnockPlan? = null) {
        _sessions.update { it + session }
        recordOpen()
        startWhenRoutable(session, tailscaleId, knock)
        SessionService.refresh(context)
        // A rename has to reach the file too, and nothing else about the
        // session changes when it happens.
        scope.launch { session.name.drop(1).collect { recordOpen() } }
        scope.launch {
            session.state.first { it is SessionState.Disconnected }
            // A session that has ended is no longer something to reopen, which
            // is what keeps a host that failed from being tried again on the
            // next cold start.
            recordOpen()
            SessionService.refresh(context)
            releaseIdleTunnels()
        }
    }

    private fun recordOpen() {
        openSessions?.record(
            _sessions.value.filterNot { it.isFinished }
                .map { s -> LiveSession(s.id, s.host?.id, s.label.takeIf { s.renamed }) },
        )
    }

    /**
     * Start the session, holding it back while the Tailscale node comes up.
     *
     * The node only runs on demand, so a host routed through it is normally
     * starting one at the same moment as the connection — and the core would
     * fail immediately with "tailscale is not connected yet". The wait shows in
     * the connecting overlay like any other step.
     */
    private fun startWhenRoutable(session: TerminalSession, tailscaleId: String?, knock: KnockPlan? = null) {
        val ts = tailscale
        val waitingForTailnet = tailscaleId != null && ts != null && ts.status(tailscaleId).state != "running"
        if (!waitingForTailnet && knock == null) {
            session.start()
            return
        }
        if (waitingForTailnet) {
            val name = store.tailscaleProfile(tailscaleId)?.name?.takeIf { it.isNotBlank() } ?: "Tailscale"
            runCatching { session.core.note("Waiting for $name to come up") }
        }
        scope.launch {
            if (waitingForTailnet) {
                val up = ts!!.awaitUp(tailscaleId!!)
                if (session.destroyed) return@launch
                if (!up) runCatching { session.core.note("Tailscale is not up; trying anyway") }
            }
            // The knock is the last thing before the dial on purpose: it opens
            // the firewall for a moment, and anything waited for after it would
            // spend that moment.
            if (knock != null) {
                val failed = withContext(Dispatchers.IO) { PortKnock.send(knock) }
                if (session.destroyed) return@launch
                if (failed != null) runCatching { session.core.note("Could not knock: $failed") }
            }
            session.start()
        }
    }

    /**
     * Bring down tunnels nothing is using any more.
     *
     * A tunnel or the Tailscale node is started because a session needed it, so
     * it should not outlive the last session that did — leaving a VPN up in the
     * background is exactly what makes one feel heavy. Deliberately delayed: a
     * reconnect or a second session to the same host is common right after one
     * ends, and tearing the tunnel down only to rebuild it would be worse than
     * leaving it up for a moment.
     */
    private fun releaseIdleTunnels() {
        scope.launch {
            kotlinx.coroutines.delay(IDLE_TUNNEL_GRACE_MS)
            val live = _sessions.value.filterNot { it.isFinished }
            val stillNeeded = live.mapNotNull { it.host }.let { hosts ->
                hosts.flatMap { h -> store.jumpChain(h).map { it.tunnelId } + listOf(h.tunnelId) }.filterNotNull().toSet()
            }
            for (t in store.tunnels.value) {
                if (t.id !in stillNeeded && tunnels.stats.value[t.id]?.running == true) {
                    tunnels.stop(t.id)
                }
            }
            val tailnetsInUse = live.mapNotNull { it.host }
                .flatMap { h -> store.jumpChain(h).map { it.tailscaleId } + listOf(h.tailscaleId) }
                .filterNotNull().toSet()
            for (p in store.tailscaleProfiles.value) {
                if (p.id !in tailnetsInUse) tailscale?.downIfIdle(p.id)
            }
        }
    }

    fun remove(id: String) {
        val s = get(id) ?: return
        _sessions.update { list -> list.filterNot { it.id == id } }
        recordOpen()
        dev.flint.term.transfer.ExternalEdit.stopFor(s)
        releaseIdleTunnels()
        // Give any view still attached a frame to let go before the core object dies.
        scope.launch(Dispatchers.IO) { kotlinx.coroutines.delay(300); s.destroy() }
        SessionService.refresh(context)
    }

    fun closeAll() {
        _sessions.value.forEach { it.close() }
    }

    fun applyTheme() {
        val default = store.settings.value.theme
        _sessions.value.forEach { it.setPalette(Palettes.forTheme(it.host?.theme, default)) }
    }

    val activeCount: Int get() = _sessions.value.count { !it.isFinished }

    /** Bell / pattern notifications while the app is in the background, and auto-reconnect for persistent hosts. */
    private fun watchForAlerts(session: TerminalSession, host: Host) {
        scope.launch {
            session.patterns.collect { (pattern, line) ->
                if (!App.inForeground) notify(session, "${host.displayName}: ${line.take(120)}", "matched \"$pattern\"")
            }
        }
        // A build or a backup is exactly when the phone goes into a pocket, so
        // the session says when a slow one is done. Set up per session, since
        // watching costs a pass over every chunk of output.
        if (store.settings.value.notifyOnCommandFinish) {
            session.watchCommands(COMMAND_NOTICE_MS)
            scope.launch {
                session.commandFinished.collect { done ->
                    if (!App.inForeground) {
                        notify(session, "Finished on ${host.displayName}", howItWent(done))
                    }
                }
            }
        }
        scope.launch {
            session.bell.collect {
                if (!App.inForeground && store.settings.value.notifyOnBell) notify(session, "Bell from ${host.displayName}", session.title.value ?: "The terminal rang its bell")
            }
        }
        // Something on the far end asked to be shown, with OSC 9, 99 or 777.
        // Same rule as the bell: a notification for the terminal you are
        // already looking at is noise.
        scope.launch {
            session.notifications.collect { (title, body) ->
                if (!App.inForeground && store.settings.value.notifyFromEscapes) {
                    notify(session, asPlainText(title, 80).ifEmpty { host.displayName }, asPlainText(body, 300).ifEmpty { host.displayName })
                }
            }
        }
        if (host.persistent) {
            scope.launch {
                session.state.collect { st ->
                    when (st) {
                        is SessionState.Connected -> session.reconnectAttempts = 0
                        is SessionState.Disconnected -> {
                            if (session.closedByUser || session.destroyed || st.error == null && st.exitCode == 0) return@collect
                            val attempt = session.reconnectAttempts
                            if (attempt >= 6) return@collect
                            val delayMs = listOf(3L, 5L, 10L, 20L, 30L, 60L)[attempt] * 1000
                            kotlinx.coroutines.delay(delayMs)
                            // On a train the link comes and goes. Retrying into a
                            // dead network just spends battery and burns an attempt,
                            // so wait for connectivity first — and do not count the
                            // wait as an attempt, since it was never tried.
                            if (!network.online) {
                                runCatching { session.core.note("Waiting for a network…") }
                                network.awaitOnline()
                                kotlinx.coroutines.delay(500) // let the new link settle
                            }
                            if (session.closedByUser || session.destroyed || get(session.id) == null) return@collect
                            val fresh = openSsh(host)
                            fresh.reconnectAttempts = attempt + 1
                            runCatching { fresh.core.note("Reconnected automatically (attempt ${attempt + 1})") }
                            _replacements.tryEmit(session.id to fresh.id)
                            remove(session.id)
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    /** "4 minutes", "1 h 12 min" — enough to know whether it was worth the wait. */
    private fun howLong(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 90 -> "$seconds seconds"
            seconds < 3600 -> "${seconds / 60} minutes"
            else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
        }
    }

    /**
     * The one line the notice gets: how long, and how it ended when that is
     * known. Only a shell emitting OSC 133 reports a status; the prompt
     * heuristic can see that a command ended but never how.
     */
    private fun howItWent(done: CommandWatch.Finished): String = when (done.exitStatus) {
        null -> "The command took ${howLong(done.elapsedMillis)}"
        0 -> "Done in ${howLong(done.elapsedMillis)}"
        else -> "Failed with status ${done.exitStatus} after ${howLong(done.elapsedMillis)}"
    }

    /**
     * Text a program on the far end wrote, made fit for a notification.
     *
     * It is whatever that program chose to send, so it is shown as text and as
     * nothing else: control characters — the newlines that would let one line
     * pretend to be several among them — become spaces, and a body long enough
     * to be a document is cut off rather than pushed at the shade.
     */
    private fun asPlainText(text: String, limit: Int): String {
        val flat = buildString(text.length) { for (ch in text) append(if (ch.isISOControl()) ' ' else ch) }
            .replace(Regex("\\s{2,}"), " ").trim()
        return if (flat.length <= limit) flat else flat.take(limit).trimEnd() + "…"
    }

    private fun notify(session: TerminalSession, title: String, text: String) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val open = android.app.PendingIntent.getActivity(
            context, session.id.hashCode(),
            Intent(context, dev.flint.term.ui.MainActivity::class.java).setAction(Shortcuts.ACTION_OPEN_SESSION).putExtra(Shortcuts.EXTRA_SESSION_ID, session.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = android.app.Notification.Builder(context, dev.flint.term.App.CHANNEL_ALERTS)
            .setSmallIcon(dev.flint.term.R.drawable.ic_terminal)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(("alert-" + session.id).hashCode(), n) }
    }

    /** Runs on a core blocking thread. Blocks until the user decides when needed. */
    private fun verifyHostKey(key: HostKey): Boolean {
        val known = store.knownHost(key.host, key.port.toInt())
        if (known != null && known.keyType == key.keyType && known.keyBase64 == key.keyBase64) return true
        val prompt = HostKeyPrompt(key, known?.fingerprint, CompletableDeferred())
        _hostKeyPrompt.value = prompt
        val accepted = try {
            awaitDecision(prompt)
        } finally {
            _hostKeyPrompt.value = null
        }
        if (accepted) {
            store.rememberHostKey(KnownHost(key.host, key.port.toInt(), key.keyType, key.keyBase64, key.fingerprint))
        }
        Log.i("SessionManager", "host key for ${key.host}:${key.port} ${if (accepted) "accepted" else "rejected"}")
        return accepted
    }

    companion object {
        /** How long a tunnel is kept after the last session that needed it. */
        private const val IDLE_TUNNEL_GRACE_MS = 20_000L

        /** A file browser cannot wait for ever for a host that is not answering. */
        private const val HEADLESS_CONNECT_TIMEOUT_MS = 30_000L

        /** Enough history to be useful; a shell file can be enormous. */
        private const val HISTORY_MAX_BYTES: ULong = 524_288UL

        /**
         * How long anything waiting on "the session is up" keeps waiting. Long,
         * because a woken machine plus a host-key prompt is minutes, not seconds.
         */
        private const val CONNECT_WINDOW_MS = 15 * 60_000L

        /**
         * A command has to run at least this long before its ending is news.
         * Anything quicker was over before the phone left the hand.
         */
        private const val COMMAND_NOTICE_MS = 30_000L

        /** How often a host's shell history is worth reading again. */
        private const val HISTORY_REFRESH_MS = 7 * 24 * 60 * 60 * 1000L



        fun startService(context: Context) {
            val intent = Intent(context, SessionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
