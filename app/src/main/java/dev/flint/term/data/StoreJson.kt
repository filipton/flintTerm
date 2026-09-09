package dev.flint.term.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the store holds, as plain values.
 *
 * The store itself is a bundle of flows tied to a file on disk; this is the
 * same content with no home, which is what a backup is made from and what a
 * restore hands back to be merged.
 */
data class Snapshot(
    val hosts: List<Host> = emptyList(),
    val groups: List<HostGroup> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val identities: List<Identity> = emptyList(),
    val tunnels: List<Tunnel> = emptyList(),
    val proxies: List<SavedProxy> = emptyList(),
    val tailscaleProfiles: List<TailscaleProfile> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val knownHosts: List<KnownHost> = emptyList(),
    val history: Map<String, List<String>> = emptyMap(),
    val historyCounts: Map<String, Map<String, Int>> = emptyMap(),
    val settings: Settings = Settings(),
)

/**
 * The JSON form of a [Snapshot], shared by the store file and the backup file.
 *
 * The only difference between the two is what happens to a secret on its way
 * through: the store file wraps each in the device keystore, the backup leaves
 * them as they are and seals the whole document instead. That is `secret`,
 * applied to every password, private key, passphrase, tunnel config and auth
 * key, and to nothing else.
 */
object StoreJson {
    fun write(snap: Snapshot, secret: (String) -> String): JSONObject {
        val root = JSONObject()
        root.put("version", 1)
        root.put("hosts", JSONArray().apply {
            snap.hosts.forEach { h ->
                put(JSONObject().apply {
                    put("id", h.id); put("label", h.label); put("hostname", h.hostname); put("port", h.port)
                    put("username", h.username); put("authType", h.authType.name)
                    put("password", secret(h.password)); put("identityId", h.identityId ?: JSONObject.NULL)
                    put("accountId", h.accountId ?: JSONObject.NULL)
                    put("color", h.color); put("lastConnected", h.lastConnected)
                    put("icon", h.icon.name); put("groupId", h.groupId ?: JSONObject.NULL)
                    put("jumpHostId", h.jumpHostId ?: JSONObject.NULL); put("preConnectCommand", h.preConnectCommand)
                    put("waitForHostSeconds", h.waitForHostSeconds); put("startupCommand", h.startupCommand)
                    put("startupSnippetIds", JSONArray(h.startupSnippetIds)); put("detectedOs", h.detectedOs)
                    put("historyImportedAt", h.historyImportedAt)
                    h.keyboardProtocol?.let { put("keyboardProtocol", it) }
                    h.fixtermsCtrlKeys?.let { put("fixtermsCtrlKeys", it) }
                    h.terminalImages?.let { put("terminalImages", it) }
                    h.tmuxControls?.let { put("tmuxControls", it) }
                    put("tmuxPrefix", h.tmuxPrefix)
                    put("fontSizeSp", h.fontSizeSp.toDouble())
                    put("env", JSONArray().apply {
                        h.env.forEach { e -> put(JSONObject().apply { put("name", e.name); put("value", e.value) }) }
                    })
                    put("tunnelId", h.tunnelId ?: JSONObject.NULL); put("tunnelMode", h.tunnelMode.name)
                    put("tailscaleId", h.tailscaleId ?: JSONObject.NULL); put("showInFiles", h.showInFiles)
                    put("addresses", JSONArray().apply {
                        h.addresses.forEach { a ->
                            put(JSONObject().apply {
                                put("id", a.id); put("label", a.label); put("hostname", a.hostname); put("port", a.port)
                                put("tunnelId", a.tunnelId ?: JSONObject.NULL); put("tailscaleId", a.tailscaleId ?: JSONObject.NULL)
                                put("tunnelMode", a.tunnelMode.name)
                            })
                        }
                    })
                    put("persistent", h.persistent); put("tmuxSession", h.tmuxSession); put("tmuxResumeLast", h.tmuxResumeLast); put("forwardAgent", h.forwardAgent); put("askBeforeAgentSigning", h.askBeforeAgentSigning ?: JSONObject.NULL); put("theme", h.theme ?: JSONObject.NULL)
                    put("protocol", h.protocol.name); put("mosh", h.mosh); put("recordSessions", h.recordSessions)
                    put("notifyPatterns", JSONArray(h.notifyPatterns))
                    put("wol", JSONObject().apply {
                        put("enabled", h.wol.enabled); put("mac", h.wol.mac); put("broadcast", h.wol.broadcast); put("port", h.wol.port)
                        put("sendFrom", h.wol.sendFrom.name); put("autoWake", h.wol.autoWake)
                    })
                    put("knock", JSONObject().apply {
                        put("enabled", h.knock.enabled); put("sequence", h.knock.sequence)
                        put("delayMs", h.knock.delayMs); put("pauseMs", h.knock.pauseMs)
                    })
                    put("proxyId", h.proxyId ?: JSONObject.NULL)
                    put("forwards", JSONArray().apply {
                        h.forwards.forEach { f ->
                            put(JSONObject().apply {
                                put("id", f.id); put("type", f.type.name); put("bindHost", f.bindHost); put("bindPort", f.bindPort)
                                put("targetHost", f.targetHost); put("targetPort", f.targetPort); put("autoStart", f.autoStart)
                            })
                        }
                    })
                })
            }
        })
        root.put("groups", JSONArray().apply {
            snap.groups.forEach { g ->
                put(JSONObject().apply {
                    put("id", g.id); put("name", g.name); put("created", g.created)
                    put("accountId", g.accountId ?: JSONObject.NULL); put("jumpHostId", g.jumpHostId ?: JSONObject.NULL)
                    put("tunnelId", g.tunnelId ?: JSONObject.NULL); put("tailscaleId", g.tailscaleId ?: JSONObject.NULL)
                    put("proxyId", g.proxyId ?: JSONObject.NULL); put("theme", g.theme ?: JSONObject.NULL)
                    put("color", g.color)
                })
            }
        })
        root.put("accounts", JSONArray().apply {
            snap.accounts.forEach { a ->
                put(JSONObject().apply {
                    put("id", a.id); put("name", a.name); put("username", a.username)
                    put("authType", a.authType.name); put("password", secret(a.password))
                    put("identityId", a.identityId ?: JSONObject.NULL); put("created", a.created)
                })
            }
        })
        root.put("identities", JSONArray().apply {
            snap.identities.forEach { i ->
                put(JSONObject().apply {
                    put("id", i.id); put("name", i.name); put("privateKey", secret(i.privateKey))
                    put("publicKey", i.publicKey); put("fingerprint", i.fingerprint)
                    put("passphrase", secret(i.passphrase)); put("created", i.created)
                    put("hardware", i.hardware); put("backing", i.backing)
                    put("certificate", i.certificate); put("requireAuth", i.requireAuth)
                    // Not encrypted, and deliberately: none of it is secret, and
                    // a credential id that cannot be read back is a key lost.
                    put("securityKey", i.securityKey); put("skAlgorithm", i.skAlgorithm)
                    put("skApplication", i.skApplication); put("skPublicKey", i.skPublicKey)
                    put("skCredentialId", i.skCredentialId); put("skTransport", i.skTransport)
                })
            }
        })
        root.put("tunnels", JSONArray().apply {
            snap.tunnels.forEach { t ->
                put(JSONObject().apply { put("id", t.id); put("name", t.name); put("config", secret(t.config)); put("created", t.created) })
            }
        })
        root.put("history", JSONObject().apply {
            snap.history.forEach { (hostId, commands) -> put(hostId, JSONArray(commands)) }
        })
        root.put("historyCounts", JSONObject().apply {
            snap.historyCounts.forEach { (hostId, counts) ->
                put(hostId, JSONObject().apply { counts.forEach { (command, n) -> put(command, n) } })
            }
        })
        root.put("proxies", JSONArray().apply {
            snap.proxies.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("name", p.name); put("type", p.type.name)
                    put("host", p.host); put("port", p.port); put("username", p.username)
                    put("password", secret(p.password)); put("created", p.created)
                })
            }
        })
        root.put("tailscaleProfiles", JSONArray().apply {
            snap.tailscaleProfiles.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("name", p.name); put("hostname", p.hostname)
                    put("authKey", secret(p.authKey)); put("controlUrl", p.controlUrl)
                    put("joined", p.joined); put("created", p.created)
                })
            }
        })
        root.put("snippets", JSONArray().apply {
            snap.snippets.forEach { sn ->
                put(JSONObject().apply {
                    put("id", sn.id); put("name", sn.name); put("command", sn.command); put("run", sn.run)
                    put("hostIds", JSONArray(sn.hostIds)); put("created", sn.created)
                })
            }
        })
        root.put("knownHosts", JSONArray().apply {
            snap.knownHosts.forEach { k ->
                put(JSONObject().apply {
                    put("host", k.host); put("port", k.port); put("keyType", k.keyType); put("keyBase64", k.keyBase64); put("fingerprint", k.fingerprint)
                    if (k.isHashed) { put("hashSalt", k.hashSalt); put("hashedHost", k.hashedHost) }
                })
            }
        })
        val s = snap.settings
        root.put("settings", JSONObject().apply {
            put("fontSizeSp", s.fontSizeSp.toDouble()); put("theme", s.theme); put("scrollback", s.scrollback); put("fontFamily", s.fontFamily); put("ligatures", s.ligatures); put("predictiveEcho", s.predictiveEcho.name); put("dataSaver", s.dataSaver.name)
            put("vibrateOnBell", s.vibrateOnBell); put("keepScreenOn", s.keepScreenOn); put("keepaliveSeconds", s.keepaliveSeconds)
            put("appTheme", s.appTheme.name); put("dynamicColor", s.dynamicColor); put("showHiddenFiles", s.showHiddenFiles)
            s.extraKeysRow1?.let { put("extraKeysRow1", JSONArray(it)) }; s.extraKeysRow2?.let { put("extraKeysRow2", JSONArray(it)) }
            put("hideExtraKeysWithHardwareKeyboard", s.hideExtraKeysWithHardwareKeyboard)
            put("volumeDownAction", s.volumeDownAction); put("volumeUpAction", s.volumeUpAction)
            put("volumeDownMode", s.volumeDownMode.name); put("volumeUpMode", s.volumeUpMode.name)
            put("appLock", s.appLock); put("appLockGraceSeconds", s.appLockGraceSeconds); put("notifyOnBell", s.notifyOnBell)
            put("notifyOnCommandFinish", s.notifyOnCommandFinish)
            put("importShellHistory", s.importShellHistory); put("swipeBetweenSessions", s.swipeBetweenSessions)
            put("completeFromHistory", s.completeFromHistory); put("tabAcceptsSuggestion", s.tabAcceptsSuggestion)
            put("showSessionTabs", s.showSessionTabs)
            put("doubleTapSendsTab", s.doubleTapSendsTab)
            put("recordingFormat", s.recordingFormat.name)
            put("vpnResolver", s.vpnResolver)
            put("terminalUploadDir", s.terminalUploadDir)
            put("confirmAgentSignatures", s.confirmAgentSignatures)
            put("automation", s.automation)
            put("automationAllowed", JSONArray().apply { s.automationAllowed.forEach { put(it) } })
            put("automationLog", JSONArray().apply {
                s.automationLog.forEach { c ->
                    put(JSONObject().apply { put("at", c.at); put("caller", c.caller); put("what", c.what); put("result", c.result) })
                }
            })
            put("keyboardProtocol", s.keyboardProtocol); put("fixtermsCtrlKeys", s.fixtermsCtrlKeys); put("rawControlKeys", s.rawControlKeys); put("extraKeysRows", s.extraKeysRows); put("builtInKeyboard", s.builtInKeyboard); put("clipboardSuggestion", s.clipboardSuggestion); put("groupArrowKeys", s.groupArrowKeys); put("doubleTapLocksModifier", s.doubleTapLocksModifier)
            put("twoFingerDragArrows", s.twoFingerDragArrows); put("capsLockAs", s.capsLockAs.name)
            put("composeRemembersState", s.composeRemembersState)
            put("tmuxControls", s.tmuxControls); put("ctrlLongPressOpensChords", s.ctrlLongPressOpensChords)
            put("chords", JSONArray().apply {
                s.chords.forEach { c ->
                    put(JSONObject().apply { put("id", c.id); put("label", c.label); put("keys", c.keys); put("tab", c.tab) })
                }
            })
            put("showAuthBanners", s.showAuthBanners); put("discoverNearbyHosts", s.discoverNearbyHosts)
            put("restoreSessions", s.restoreSessions); put("notifyFromEscapes", s.notifyFromEscapes)
            put("pipOnLeave", s.pipOnLeave)
            put("editor", s.editor.name); put("editorBackup", s.editorBackup)
            put("nerdGlyphs", s.nerdGlyphs); put("boldIsBright", s.boldIsBright)
            put("cursorStyle", s.cursorStyle.name); put("cursorBlink", s.cursorBlink)
            put("highlightEnabled", s.highlightEnabled)
            put("highlightRules", JSONArray().apply {
                s.highlightRules.forEach { r ->
                    put(JSONObject().apply {
                        put("id", r.id); put("pattern", r.pattern); put("color", r.color)
                        put("wholeLine", r.wholeLine); put("enabled", r.enabled)
                    })
                }
            })
            put("terminalImages", s.terminalImages.name)
            put("customSchemes", JSONArray().apply { s.customSchemes.forEach { put(scheme(it)) } })
            put("shortcuts", JSONArray().apply {
                s.shortcuts.forEach { k ->
                    put(JSONObject().apply {
                        put("action", k.action); put("keyCode", k.keyCode)
                        put("ctrl", k.ctrl); put("shift", k.shift); put("alt", k.alt)
                    })
                }
            })
        })
        return root
    }


    fun read(root: JSONObject, secret: (String) -> String): Snapshot {
        val hosts = mutableListOf<Host>()
        root.optJSONArray("hosts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val forwards = mutableListOf<PortForward>()
                o.optJSONArray("forwards")?.let { fa ->
                    for (j in 0 until fa.length()) {
                        val f = fa.getJSONObject(j)
                        forwards += PortForward(
                            id = f.optString("id"),
                            type = runCatching { ForwardType.valueOf(f.optString("type")) }.getOrDefault(ForwardType.LOCAL),
                            bindHost = f.optString("bindHost", "127.0.0.1"),
                            bindPort = f.optInt("bindPort"),
                            targetHost = f.optString("targetHost"),
                            targetPort = f.optInt("targetPort"),
                            autoStart = f.optBoolean("autoStart", true),
                        )
                    }
                }
                hosts += Host(
                    id = o.getString("id"),
                    label = o.optString("label"),
                    hostname = o.optString("hostname"),
                    port = o.optInt("port", 22),
                    username = o.optString("username"),
                    authType = runCatching { AuthType.valueOf(o.optString("authType")) }.getOrDefault(AuthType.PASSWORD),
                    password = secret(o.optString("password")),
                    identityId = o.optId("identityId"),
                    accountId = o.optId("accountId"),
                    forwards = forwards,
                    color = o.optInt("color"),
                    icon = runCatching { HostIcon.valueOf(o.optString("icon")) }.getOrDefault(HostIcon.SERVER),
                    groupId = o.optId("groupId"),
                    lastConnected = o.optLong("lastConnected"),
                    jumpHostId = o.optId("jumpHostId"),
                    preConnectCommand = o.optString("preConnectCommand"),
                    waitForHostSeconds = o.optInt("waitForHostSeconds", 90),
                    startupCommand = o.optString("startupCommand"),
                    startupSnippetIds = o.optJSONArray("startupSnippetIds")?.let { a ->
                        (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
                    } ?: emptyList(),
                    // A store written by hand (or by an older build) may have
                    // anything in here, so entries without a name are dropped
                    // rather than asked for as "" on the next connection.
                    env = o.optJSONArray("env")?.let { a ->
                        (0 until a.length()).mapNotNull { i ->
                            val e = a.optJSONObject(i) ?: return@mapNotNull null
                            val name = e.optString("name").trim()
                            if (name.isEmpty()) null else EnvEntry(name, e.optString("value"))
                        }
                    } ?: emptyList(),
                    detectedOs = o.optString("detectedOs"),
                    historyImportedAt = o.optLong("historyImportedAt"),
                    keyboardProtocol = if (o.has("keyboardProtocol")) o.optBoolean("keyboardProtocol") else null,
                    fixtermsCtrlKeys = if (o.has("fixtermsCtrlKeys")) o.optBoolean("fixtermsCtrlKeys") else null,
                    terminalImages = if (o.has("terminalImages")) o.optBoolean("terminalImages") else null,
                    tmuxControls = if (o.has("tmuxControls")) o.optBoolean("tmuxControls") else null,
                    tmuxPrefix = o.optString("tmuxPrefix").ifBlank { "C-b" },
                    fontSizeSp = o.optDouble("fontSizeSp", 0.0).toFloat(),
                    tunnelId = o.optId("tunnelId"),
                    // "viaTailscale" is the pre-profiles form: a single embedded node,
                    // so the host belongs to whichever profile the migration made.
                    // The elvis covers the pre-profiles form, where a host only
                    // recorded "viaTailscale" and there was one node.
                    tailscaleId = o.optId("tailscaleId") ?: Store.LEGACY_TAILSCALE_ID.takeIf { o.optBoolean("viaTailscale") },
                    showInFiles = o.optBoolean("showInFiles"),
                    addresses = o.optJSONArray("addresses")?.let { arr ->
                        (0 until arr.length()).map { i ->
                            val a = arr.getJSONObject(i)
                            HostAddress(
                                id = a.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                                label = a.optString("label"),
                                hostname = a.optString("hostname"),
                                port = a.optInt("port"),
                                tunnelId = a.optId("tunnelId"),
                                tailscaleId = a.optId("tailscaleId"),
                                tunnelMode = runCatching { TunnelMode.valueOf(a.optString("tunnelMode")) }
                                    .getOrDefault(TunnelMode.WHEN_NEEDED),
                            )
                        }
                    } ?: emptyList(),
                    tunnelMode = runCatching { TunnelMode.valueOf(o.optString("tunnelMode")) }.getOrDefault(TunnelMode.WHEN_NEEDED),
                    persistent = o.optBoolean("persistent"),
                    forwardAgent = o.optBoolean("forwardAgent"),
                    askBeforeAgentSigning = if (o.isNull("askBeforeAgentSigning")) null else o.optBoolean("askBeforeAgentSigning"),
                    theme = o.optId("theme"),
                    protocol = runCatching { Protocol.valueOf(o.optString("protocol")) }.getOrDefault(Protocol.SSH),
                    mosh = o.optBoolean("mosh"),
                    recordSessions = o.optBoolean("recordSessions"),
                    tmuxSession = o.optString("tmuxSession", "main").ifBlank { "main" },
                    tmuxResumeLast = o.optBoolean("tmuxResumeLast", true),
                    notifyPatterns = o.optJSONArray("notifyPatterns")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    wol = o.optJSONObject("wol")?.let { w ->
                        WolSettings(
                            enabled = w.optBoolean("enabled"), mac = w.optString("mac"), broadcast = w.optString("broadcast", "255.255.255.255"),
                            port = w.optInt("port", 9), sendFrom = runCatching { WolSource.valueOf(w.optString("sendFrom")) }.getOrDefault(WolSource.AUTO),
                            autoWake = w.optBoolean("autoWake", true),
                        )
                    } ?: WolSettings(),
                    knock = o.optJSONObject("knock")?.let { k ->
                        KnockSettings(
                            enabled = k.optBoolean("enabled"), sequence = k.optString("sequence"),
                            delayMs = k.optInt("delayMs", 200), pauseMs = k.optInt("pauseMs", 400),
                        )
                    } ?: KnockSettings(),
                    proxyId = o.optId("proxyId"),
                    // Read the pre-saved-proxies form so it can be turned into a
                    // saved proxy below; it is never written back.
                    proxy = o.optJSONObject("proxy")?.let { p ->
                        ProxySettings(
                            type = runCatching { ProxyType.valueOf(p.optString("type")) }.getOrDefault(ProxyType.NONE),
                            host = p.optString("host"), port = p.optInt("port", 1080),
                            username = p.optString("username"), password = secret(p.optString("password")),
                        )
                    } ?: ProxySettings(),
                )
            }
        }
        val groups = mutableListOf<HostGroup>()
        root.optJSONArray("groups")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                groups += HostGroup(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    accountId = o.optId("accountId"),
                    jumpHostId = o.optId("jumpHostId"),
                    tunnelId = o.optId("tunnelId"),
                    tailscaleId = o.optId("tailscaleId"),
                    proxyId = o.optId("proxyId"),
                    theme = o.optId("theme"),
                    color = o.optInt("color"),
                    created = o.optLong("created"),
                )
            }
        }
        // Before groups were objects, a host carried the name of its group as
        // plain text. One group per distinct name, and the hosts join it.
        if (root.optJSONArray("groups") == null) {
            root.optJSONArray("hosts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val name = arr.getJSONObject(i).optString("group").trim()
                    if (name.isEmpty() || i >= hosts.size) continue
                    val g = groups.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?: HostGroup(name = name).also { groups += it }
                    hosts[i] = hosts[i].copy(groupId = g.id)
                }
            }
        }
        val accounts = mutableListOf<Account>()
        root.optJSONArray("accounts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                accounts += Account(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    username = o.optString("username"),
                    authType = runCatching { AuthType.valueOf(o.optString("authType")) }.getOrDefault(AuthType.KEY),
                    password = secret(o.optString("password")),
                    identityId = o.optId("identityId"),
                    created = o.optLong("created"),
                )
            }
        }
        val identities = mutableListOf<Identity>()
        root.optJSONArray("identities")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                identities += Identity(
                    hardware = o.optBoolean("hardware"),
                    backing = o.optString("backing"),
                    certificate = o.optString("certificate"),
                    requireAuth = o.optBoolean("requireAuth"),
                    securityKey = o.optBoolean("securityKey"),
                    skAlgorithm = o.optString("skAlgorithm"),
                    skApplication = o.optString("skApplication"),
                    skPublicKey = o.optString("skPublicKey"),
                    skCredentialId = o.optString("skCredentialId"),
                    skTransport = o.optString("skTransport"),
                    id = o.getString("id"),
                    name = o.optString("name"),
                    privateKey = secret(o.optString("privateKey")),
                    publicKey = o.optString("publicKey"),
                    fingerprint = o.optString("fingerprint"),
                    passphrase = secret(o.optString("passphrase")),
                    created = o.optLong("created"),
                )
            }
        }
        val tunnels = mutableListOf<Tunnel>()
        root.optJSONArray("tunnels")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                tunnels += Tunnel(id = o.getString("id"), name = o.optString("name"), config = secret(o.optString("config")), created = o.optLong("created"))
            }
        }
        val history = mutableMapOf<String, List<String>>()
        root.optJSONObject("history")?.let { o ->
            for (hostId in o.keys()) {
                val arr = o.optJSONArray(hostId) ?: continue
                history[hostId] = (0 until arr.length()).map { arr.getString(it) }.takeLast(CommandHistory.LIMIT)
            }
        }
        val historyCounts = mutableMapOf<String, Map<String, Int>>()
        root.optJSONObject("historyCounts")?.let { o ->
            for (hostId in o.keys()) {
                val per = o.optJSONObject(hostId) ?: continue
                val counts = mutableMapOf<String, Int>()
                for (command in per.keys()) counts[command] = per.optInt(command, 1)
                historyCounts[hostId] = counts
            }
        }
        val proxies = mutableListOf<SavedProxy>()
        root.optJSONArray("proxies")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                proxies += SavedProxy(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    type = runCatching { ProxyType.valueOf(o.optString("type")) }.getOrDefault(ProxyType.SOCKS5),
                    host = o.optString("host"),
                    port = o.optInt("port", 1080),
                    username = o.optString("username"),
                    password = secret(o.optString("password")),
                    created = o.optLong("created"),
                )
            }
        }
        // A proxy used to be typed into each host. Lift those into saved proxies,
        // one per distinct address, so the same corporate SOCKS5 does not become
        // five identical entries.
        if (root.optJSONArray("proxies") == null) {
            for ((index, h) in hosts.withIndex()) {
                if (!h.proxy.enabled) continue
                val same = proxies.firstOrNull {
                    it.type == h.proxy.type && it.host == h.proxy.host && it.port == h.proxy.port && it.username == h.proxy.username
                }
                val proxy = same ?: SavedProxy(
                    name = "${h.proxy.host}:${h.proxy.port}",
                    type = h.proxy.type,
                    host = h.proxy.host,
                    port = h.proxy.port,
                    username = h.proxy.username,
                    password = h.proxy.password,
                ).also { proxies += it }
                hosts[index] = h.copy(proxyId = proxy.id)
            }
        }
        val profiles = mutableListOf<TailscaleProfile>()
        root.optJSONArray("tailscaleProfiles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                profiles += TailscaleProfile(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    hostname = o.optString("hostname"),
                    authKey = secret(o.optString("authKey")),
                    controlUrl = o.optString("controlUrl"),
                    joined = o.optBoolean("joined"),
                    created = o.optLong("created"),
                )
            }
        }
        // Before profiles there was one embedded node, configured in settings.
        // Turn it into the first profile so its identity, key and hosts survive.
        if (root.optJSONArray("tailscaleProfiles") == null) {
            root.optJSONObject("settings")?.let { o ->
                val key = secret(o.optString("tailscaleAuthKey"))
                val used = o.optBoolean("tailscaleEnabled") || o.optBoolean("tailscaleJoined") ||
                    key.isNotBlank() || o.optString("tailscaleHostname").isNotBlank() || o.optString("tailscaleControlUrl").isNotBlank()
                if (used) {
                    profiles += TailscaleProfile(
                        id = Store.LEGACY_TAILSCALE_ID,
                        name = "Tailscale",
                        hostname = o.optString("tailscaleHostname"),
                        authKey = key,
                        controlUrl = o.optString("tailscaleControlUrl"),
                        joined = o.optBoolean("tailscaleJoined"),
                    )
                }
            }
        }
        val snippets = mutableListOf<Snippet>()
        root.optJSONArray("snippets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                snippets += Snippet(
                    id = o.getString("id"), name = o.optString("name"), command = o.optString("command"), run = o.optBoolean("run", true),
                    hostIds = o.optJSONArray("hostIds")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    created = o.optLong("created"),
                )
            }
        }
        val known = mutableListOf<KnownHost>()
        root.optJSONArray("knownHosts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                // A file written before hashed entries existed has neither field, and reads back plain.
                known += KnownHost(
                    o.getString("host"), o.getInt("port"), o.getString("keyType"), o.getString("keyBase64"), o.optString("fingerprint"),
                    hashSalt = o.optString("hashSalt"), hashedHost = o.optString("hashedHost"),
                )
            }
        }
        val settings = root.optJSONObject("settings")?.let { o ->
            Settings(
                fontSizeSp = o.optDouble("fontSizeSp", 13.0).toFloat(),
                fontFamily = o.optString("fontFamily").ifBlank { "jetbrains" },
                ligatures = o.optBoolean("ligatures"),
                predictiveEcho = runCatching { PredictiveEcho.valueOf(o.optString("predictiveEcho")) }.getOrDefault(PredictiveEcho.ADAPTIVE),
                dataSaver = runCatching { DataSaver.valueOf(o.optString("dataSaver")) }.getOrDefault(DataSaver.METERED),
                // Any id, including the enum names older builds wrote; an unknown one
                // resolves to the default at lookup time rather than being rewritten here.
                theme = o.optString("theme").ifBlank { Schemes.DEFAULT_ID },
                customSchemes = o.optJSONArray("customSchemes")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::scheme) }
                }.orEmpty(),
                scrollback = o.optInt("scrollback", 10_000),
                vibrateOnBell = o.optBoolean("vibrateOnBell", true),
                keepScreenOn = o.optBoolean("keepScreenOn", true),
                keepaliveSeconds = o.optInt("keepaliveSeconds", 30),
                appTheme = runCatching { AppTheme.valueOf(o.optString("appTheme")) }.getOrDefault(AppTheme.DARK),
                dynamicColor = o.optBoolean("dynamicColor", false),
                showHiddenFiles = o.optBoolean("showHiddenFiles", true),
                extraKeysRow1 = o.optJSONArray("extraKeysRow1")?.let { a -> (0 until a.length()).map { a.getString(it) } },
                extraKeysRow2 = o.optJSONArray("extraKeysRow2")?.let { a -> (0 until a.length()).map { a.getString(it) } },
                hideExtraKeysWithHardwareKeyboard = o.optBoolean("hideExtraKeysWithHardwareKeyboard", true),
                // Older builds had a single switch; map it onto the new per-key actions.
                volumeDownAction = o.optString("volumeDownAction").ifBlank { if (o.optBoolean("volumeKeysAsModifiers", false)) "CTRL" else "NONE" },
                volumeUpAction = o.optString("volumeUpAction").ifBlank { if (o.optBoolean("volumeKeysAsModifiers", false)) "ALT" else "NONE" },
                volumeDownMode = runCatching { VolumeModifierMode.valueOf(o.optString("volumeDownMode").ifBlank { o.optString("volumeModifierMode") }) }.getOrDefault(VolumeModifierMode.STICKY),
                volumeUpMode = runCatching { VolumeModifierMode.valueOf(o.optString("volumeUpMode").ifBlank { o.optString("volumeModifierMode") }) }.getOrDefault(VolumeModifierMode.STICKY),
                appLock = o.optBoolean("appLock", false),
                appLockGraceSeconds = o.optInt("appLockGraceSeconds", 60),
                notifyOnBell = o.optBoolean("notifyOnBell", true),
                notifyOnCommandFinish = o.optBoolean("notifyOnCommandFinish", true),
                importShellHistory = o.optBoolean("importShellHistory", true),
                swipeBetweenSessions = o.optBoolean("swipeBetweenSessions", true),
                showSessionTabs = o.optBoolean("showSessionTabs", true),
                completeFromHistory = o.optBoolean("completeFromHistory", true),
                tabAcceptsSuggestion = o.optBoolean("tabAcceptsSuggestion", true),
                doubleTapSendsTab = o.optBoolean("doubleTapSendsTab", true),
                recordingFormat = runCatching { RecordingFormat.valueOf(o.optString("recordingFormat")) }.getOrDefault(RecordingFormat.TEXT),
                vpnResolver = o.optString("vpnResolver").ifBlank { "1.1.1.1" },
                terminalUploadDir = o.optString("terminalUploadDir").ifBlank { "/tmp" },
                confirmAgentSignatures = o.optBoolean("confirmAgentSignatures", true),
                automation = o.optBoolean("automation", false),
                automationAllowed = o.optJSONArray("automationAllowed")
                    ?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                automationLog = o.optJSONArray("automationLog")?.let { a ->
                    (0 until a.length()).map { i ->
                        val c = a.getJSONObject(i)
                        AutomationCall(c.optLong("at"), c.optString("caller"), c.optString("what"), c.optString("result"))
                    }
                } ?: emptyList(),
                keyboardProtocol = o.optBoolean("keyboardProtocol", true),
                fixtermsCtrlKeys = o.optBoolean("fixtermsCtrlKeys", true),
                rawControlKeys = o.optBoolean("rawControlKeys", true),
                extraKeysRows = o.optInt("extraKeysRows", 2),
                builtInKeyboard = o.optBoolean("builtInKeyboard", false),
                clipboardSuggestion = o.optBoolean("clipboardSuggestion", true),
                groupArrowKeys = o.optBoolean("groupArrowKeys", true),
                doubleTapLocksModifier = o.optBoolean("doubleTapLocksModifier", true),
                twoFingerDragArrows = o.optBoolean("twoFingerDragArrows", true),
                capsLockAs = runCatching { CapsLockAction.valueOf(o.optString("capsLockAs")) }.getOrDefault(CapsLockAction.NONE),
                composeRemembersState = o.optBoolean("composeRemembersState", true),
                tmuxControls = o.optBoolean("tmuxControls", true),
                ctrlLongPressOpensChords = o.optBoolean("ctrlLongPressOpensChords", true),
                chords = o.optJSONArray("chords")?.let { a ->
                    (0 until a.length()).map { i ->
                        val c = a.getJSONObject(i)
                        Chord(c.optString("id"), c.optString("label"), c.optString("keys"), c.optString("tab").ifBlank { "tmux" })
                    }
                }.orEmpty(),
                showAuthBanners = o.optBoolean("showAuthBanners", true),
                discoverNearbyHosts = o.optBoolean("discoverNearbyHosts", true),
                restoreSessions = o.optBoolean("restoreSessions", true),
                notifyFromEscapes = o.optBoolean("notifyFromEscapes", true),
                pipOnLeave = o.optBoolean("pipOnLeave", false),
                editor = runCatching { EditorChoice.valueOf(o.optString("editor")) }.getOrDefault(EditorChoice.BUILT_IN),
                editorBackup = o.optBoolean("editorBackup", false),
                nerdGlyphs = o.optBoolean("nerdGlyphs", true),
                boldIsBright = o.optBoolean("boldIsBright", false),
                cursorStyle = runCatching { CursorStyle.valueOf(o.optString("cursorStyle")) }.getOrDefault(CursorStyle.BLOCK),
                cursorBlink = o.optBoolean("cursorBlink", false),
                highlightEnabled = o.optBoolean("highlightEnabled", false),
                highlightRules = o.optJSONArray("highlightRules")?.let { a ->
                    (0 until a.length()).map { i ->
                        val r = a.getJSONObject(i)
                        HighlightRule(
                            r.optString("id"), r.optString("pattern"), r.optInt("color"),
                            r.optBoolean("wholeLine"), r.optBoolean("enabled", true),
                        )
                    }
                }.orEmpty(),
                terminalImages = runCatching { TerminalImages.valueOf(o.optString("terminalImages")) }.getOrDefault(TerminalImages.BOTH),
                shortcuts = o.optJSONArray("shortcuts")?.let { a ->
                    (0 until a.length()).map { i ->
                        val k = a.getJSONObject(i)
                        KeyBinding(
                            k.optString("action"), k.optInt("keyCode"),
                            k.optBoolean("ctrl", true), k.optBoolean("shift"), k.optBoolean("alt"),
                        )
                    }
                }.orEmpty(),
            )
        } ?: Settings()
        return Snapshot(
            hosts = hosts, groups = groups, accounts = accounts, identities = identities, tunnels = tunnels,
            proxies = proxies, tailscaleProfiles = profiles, snippets = snippets, knownHosts = known,
            history = history, historyCounts = historyCounts, settings = settings,
        )
    }


    /**
     * An optional id field.
     *
     * `optString` renders an explicit JSON null as the *string* "null" rather
     * than returning nothing, and a store written while that value was in hand
     * has "null" in it for real — so both spellings are read back as absent.
     */
    private fun JSONObject.optId(name: String): String? =
        if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }

    /** A custom scheme, colors as six hex digits so the file can be read by eye. */
    private fun scheme(s: TermScheme): JSONObject = JSONObject().apply {
        put("id", s.id); put("name", s.name)
        put("ansi", JSONArray().apply { s.ansi.forEach { put(TermScheme.hex(it)) } })
        put("foreground", TermScheme.hex(s.foreground)); put("background", TermScheme.hex(s.background))
        put("cursor", TermScheme.hex(s.cursor)); put("selection", TermScheme.hex(s.selection))
    }

    /** Null for an entry that would not draw: sixteen colors, a foreground and a background or nothing. */
    private fun scheme(o: JSONObject): TermScheme? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val ansi = o.optJSONArray("ansi")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).toUIntOrNull(16) } } ?: return null
        if (ansi.size != 16) return null
        val fg = o.optString("foreground").toUIntOrNull(16) ?: return null
        val bg = o.optString("background").toUIntOrNull(16) ?: return null
        return TermScheme(
            id = id, name = o.optString("name").ifBlank { "Custom scheme" }, ansi = ansi, foreground = fg, background = bg,
            cursor = o.optString("cursor").toUIntOrNull(16) ?: fg,
            selection = o.optString("selection").toUIntOrNull(16) ?: TermScheme.mix(bg, fg, 0.25),
            custom = true,
        )
    }

}
