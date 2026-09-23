package dev.flint.term.session

import dev.flint.term.core.AuthPrompter
import dev.flint.term.core.Backend
import dev.flint.term.core.HostKey
import dev.flint.term.core.HostKeyVerifier
import dev.flint.term.core.Options
import dev.flint.term.core.PaletteConfig
import dev.flint.term.core.PromptKind
import dev.flint.term.core.Session
import dev.flint.term.core.SessionListener
import dev.flint.term.core.SessionState
import dev.flint.term.data.Host
import dev.flint.term.data.PortForward
import dev.flint.term.data.RecordingFormat
import dev.flint.term.terminal.FrameBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** What the UI must decide before a connection can proceed. */
data class HostKeyPrompt(
    val key: HostKey,
    /** Non-null when a different key was previously stored for this host. */
    val previousFingerprint: String?,
    val decision: CompletableDeferred<Boolean>,
)

data class ActiveForward(val forward: PortForward, val id: ULong, val boundPort: Int, val error: String? = null)

/**
 * Kotlin-side owner of one core [Session]: turns callbacks into flows and keeps
 * the bits the UI needs (title, state, forwards). Lives in [SessionManager],
 * not in any Activity, so it survives configuration changes.
 */
class TerminalSession(
    val id: String = UUID.randomUUID().toString(),
    val host: Host?,
    label: String,
    backend: Backend,
    initialCols: Int,
    initialRows: Int,
    scrollback: Int,
    palette: PaletteConfig,
    options: Options,
    private val verifyHostKey: (HostKey) -> Boolean,
    /** Answers what the server asks at login; [NO_PROMPTS] for sessions nothing can ask. */
    prompter: AuthPrompter = NO_PROMPTS,
) {
    /**
     * The name this session was opened under, which a rename never loses: it is
     * what an emptied name field goes back to. Taken from the host's
     * [Host.displayName] for an SSH session, so renaming a session says nothing
     * about the host it dialled and the host list is left alone.
     */
    val defaultLabel: String = label

    private val _name = MutableStateFlow(label)

    /**
     * What this session is called, as it stands.
     *
     * A flow because the name can change while the tab strip is on screen, and
     * the strip has to follow it; [label] stays for the many callers — the
     * notification, the transfer list, the intent API — that only read it once.
     */
    val name: StateFlow<String> = _name
    val label: String get() = _name.value

    /** Whether this session is wearing a name somebody gave it. */
    val renamed: Boolean get() = label != defaultLabel

    /**
     * Call this session [to], or [defaultLabel] again when that is blank.
     *
     * The name lives for as long as the session does and no longer: it is the
     * four `hostssh` tabs it exists to tell apart, not a setting.
     */
    fun rename(to: String) {
        _name.value = renamedTo(to, defaultLabel)
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.Connecting)
    val state: StateFlow<SessionState> = _state
    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title
    private val _bell = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val bell: SharedFlow<Unit> = _bell
    private val _clipboard = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val clipboard: SharedFlow<String> = _clipboard
    private val _forwards = MutableStateFlow<List<ActiveForward>>(emptyList())
    val forwards: StateFlow<List<ActiveForward>> = _forwards
    /** The recording in progress, so the terminal can offer to stop it. */
    private val _recording = MutableStateFlow<SessionRecording?>(null)
    val recording: StateFlow<SessionRecording?> = _recording
    /** Connection log lines, oldest first (jump hops, wake commands, retries). */
    /** One step of the connection: what it is doing, and how it ended. */
    data class Step(val key: String, val text: String, val status: dev.flint.term.core.StepStatus)

    private val _progress = MutableStateFlow<List<Step>>(emptyList())
    val progress: StateFlow<List<Step>> = _progress
    /** (pattern, line) for output that matched one of the host's notify patterns. */
    private val _patterns = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    val patterns: SharedFlow<Pair<String, String>> = _patterns
    /** (title, body) a program asked to be shown, with an escape sequence. */
    private val _notifications = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    val notifications: SharedFlow<Pair<String, String>> = _notifications
    /** Where the shell says it is, and the exit status when a command ended. */
    private val _marks = MutableSharedFlow<Pair<PromptKind, Int?>>(extraBufferCapacity = 16)
    val marks: SharedFlow<Pair<PromptKind, Int?>> = _marks
    private val _marksPrompts = MutableStateFlow(false)

    /**
     * Whether this shell marks its prompts, as far as it has shown.
     *
     * A shell either draws its prompt with OSC 133 or it does not, so one mark
     * settles it for the life of the session. Everything that navigates by the
     * marks hangs off this: a shell that never sends them must not leave a
     * control on the screen that could only ever do nothing.
     */
    val marksPrompts: StateFlow<Boolean> = _marksPrompts
    /**
     * The directory the shell is in, when it reports one (OSC 7).
     *
     * Null for the whole life of a session whose shell was never asked to send
     * it, which is most of them: everything reading this has to work without it.
     */
    private val _cwd = MutableStateFlow<String?>(null)
    val cwd: StateFlow<String?> = _cwd
    /**
     * This session's home directory, once something has actually asked.
     *
     * Only used to write `~` in front of a directory instead of the same
     * characters every time. It is deliberately not guessed from the first
     * directory a shell reports: a shell that only starts reporting after
     * somebody has moved would make that guess wrong, and `~` in front of a
     * path that is not under the home directory is a lie rather than a
     * shortening. Until [noteHome] hears from the file browser, which asks the
     * server outright, the path is written out in full.
     */
    private val _home = MutableStateFlow<String?>(null)
    val home: StateFlow<String?> = _home
    /** What the server printed before login, if it printed anything. */
    private val _banner = MutableStateFlow<String?>(null)
    val banner: StateFlow<String?> = _banner
    /** The user closed/disconnected this session on purpose (no auto-reconnect). */
    @Volatile var closedByUser: Boolean = false
    /** Consecutive automatic reconnects; reset on a successful connection. */
    @Volatile var reconnectAttempts: Int = 0

    /**
     * The views showing this session; called from a core thread when the screen
     * has moved.
     *
     * A list rather than a slot because a session really can be on screen twice
     * at once: the floating window shows the same session as the screen behind
     * it. With one slot between them the second view to attach took it, and the
     * first stopped hearing about frames and sat there frozen — which is the
     * floating window that stops updating, and it depended on which view the
     * framework happened to attach last.
     */
    private val damageListeners = CopyOnWriteArrayList<() -> Unit>()

    fun addDamageListener(listener: () -> Unit) {
        damageListeners.addIfAbsent(listener)
    }

    fun removeDamageListener(listener: () -> Unit) {
        damageListeners.remove(listener)
    }

    /** Whether a view is drawing this session right now. */
    val onScreen: Boolean get() = damageListeners.isNotEmpty()

    val core: Session = Session(
        backend,
        initialCols.toUShort(),
        initialRows.toUShort(),
        scrollback.toUInt(),
        object : SessionListener {
            override fun onCommandFinished(elapsedMillis: ULong, exitStatus: Int?) {
                CoreThreads.keepAttached()
                _commandFinished.tryEmit(CommandFinished(elapsedMillis.toLong(), exitStatus))
            }

            override fun onDamage() {
                CoreThreads.keepAttached()
                damageListeners.forEach { it() }
            }

            override fun onState(state: SessionState) {
                CoreThreads.keepAttached()
                _state.value = state
            }

            override fun onTitle(title: String?) {
                CoreThreads.keepAttached()
                _title.value = title
            }

            override fun onBell() {
                CoreThreads.keepAttached()
                _bell.tryEmit(Unit)
            }

            override fun onClipboard(text: String) {
                CoreThreads.keepAttached()
                _clipboard.tryEmit(text)
            }

            override fun onProgress(key: String, message: String, status: dev.flint.term.core.StepStatus) {
                CoreThreads.keepAttached()
                // Steps sharing a key are the same thing progressing, so the row
                // is replaced where it stands instead of a new one piling on.
                _progress.update { steps ->
                    val step = Step(key, message, status)
                    val at = steps.indexOfFirst { it.key == key }
                    if (at >= 0) steps.toMutableList().also { it[at] = step } else (steps + step).takeLast(40)
                }
            }

            override fun onPattern(pattern: String, line: String) {
                CoreThreads.keepAttached()
                _patterns.tryEmit(pattern to line)
            }

            override fun onNotify(title: String, body: String) {
                CoreThreads.keepAttached()
                _notifications.tryEmit(title to body)
            }

            override fun onPromptMark(kind: PromptKind, exit: Int?) {
                CoreThreads.keepAttached()
                // Handed to the watcher here rather than through the flow: it
                // reads the marks against the output around them, and a
                // collector on another thread cannot promise that order.
                _marksPrompts.value = true
                _marks.tryEmit(kind to exit)
            }

            override fun onCwd(path: String) {
                CoreThreads.keepAttached()
                _cwd.value = path
            }

            override fun onBanner(text: String) {
                CoreThreads.keepAttached()
                // Kept rather than emitted: it is shown for as long as the
                // connection sheet is open, and again from the menu afterwards.
                _banner.value = text.trimEnd().ifBlank { null }
            }
        },
        object : HostKeyVerifier {
            override fun verify(key: HostKey): Boolean = verifyHostKey(key)
        },
        prompter,
        options,
    )

    init {
        core.setPalette(palette)
    }

    @Volatile
    var destroyed: Boolean = false
        private set

    val isConnected: Boolean get() = state.value is SessionState.Connected
    val isFinished: Boolean get() = state.value is SessionState.Disconnected

    fun start() = core.start()

    fun setPalette(p: PaletteConfig) = core.setPalette(p)

    /** What the server itself says this account's home directory is; see [home]. */
    fun noteHome(path: String) {
        if (path.startsWith("/")) _home.value = path.trimEnd('/').ifEmpty { "/" }
    }

    /** Longer SSH keepalives while nobody is looking; see the core's `setBackground`. */
    fun setBackground(background: Boolean) {
        if (!destroyed) runCatching { core.setBackground(background) }
    }

    /** Follow a switch that changed while this session was already up. */
    fun setOptions(o: Options) {
        if (!destroyed) runCatching { core.setOptions(o) }
    }

    /** Only a Mosh session ever predicts, so the renderer can skip the rest. */
    val usesPrediction: Boolean get() = host?.mosh == true

    /**
     * Cells predictive echo is drawing ahead of the server. Read once per frame
     * by the renderer; empty for anything but a Mosh session on a slow link.
     */
    val predictions: List<dev.flint.term.core.PredictedCell>
        get() = if (destroyed || !usesPrediction) emptyList() else runCatching { core.predictions() }.getOrDefault(emptyList())

    fun setPrediction(mode: dev.flint.term.core.PredictionMode) {
        if (!destroyed) runCatching { core.setPrediction(mode) }
    }

    /** Blocking; call from IO. */
    fun startForward(f: PortForward): ActiveForward {
        val result = try {
            val info = when (f.type) {
                dev.flint.term.data.ForwardType.LOCAL ->
                    core.addLocalForward(f.bindHost, f.bindPort.toUShort(), f.targetHost, f.targetPort.toUShort())
                dev.flint.term.data.ForwardType.REMOTE ->
                    core.addRemoteForward(f.bindHost, f.bindPort.toUShort(), f.targetHost, f.targetPort.toUShort())
                dev.flint.term.data.ForwardType.DYNAMIC ->
                    core.addDynamicForward(f.bindHost, f.bindPort.toUShort())
            }
            ActiveForward(f, info.id, info.port.toInt())
        } catch (e: Exception) {
            ActiveForward(f, 0u, 0, e.message ?: "failed")
        }
        _forwards.value = _forwards.value.filterNot { it.forward.id == f.id } + result
        return result
    }

    fun stopForward(f: ActiveForward) {
        if (f.id != 0uL) runCatching { core.removeForward(f.id) }
        _forwards.value = _forwards.value.filterNot { it.forward.id == f.forward.id }
    }

    // ---- the output stream --------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _commandFinished = MutableSharedFlow<CommandFinished>(extraBufferCapacity = 4)
    val commandFinished: SharedFlow<CommandFinished> = _commandFinished

    /**
     * Start telling this session's watchers when a slow command ends.
     *
     * The watching itself happens in the core, next to the bytes: the stream
     * does not come back over the bridge for it.
     */
    fun watchCommands(minMillis: Long) {
        if (!destroyed) runCatching { core.watchCommands(minMillis.toULong()) }
    }

    // ---- recording ---------------------------------------------------------

    /**
     * Start writing this session to [dir], and return the recording (or the one
     * already running).
     *
     * The core records the terminal's own byte stream, next to where it
     * arrives, so a cast replays exactly what the session looked like — colors,
     * cursor moves, the lot — and the text log is that stream with the escape
     * sequences taken out. Nothing crosses the bridge per chunk; this side only
     * names the files.
     */
    @Synchronized
    fun startRecording(dir: File, format: RecordingFormat, cols: Int, rows: Int): SessionRecording? {
        _recording.value?.let { return it }
        if (destroyed) return null
        val rec = SessionRecording(dir, host?.displayName ?: label, format)
        val started = runCatching {
            core.startRecording(rec.textFile?.path, rec.castFile?.path, cols.toUShort(), rows.toUShort(), rec.startedAt.toULong())
        }
        if (started.isFailure) return null
        _recording.value = rec
        return rec
    }

    /** Stops the recording and returns the files it wrote; empty when none was running. */
    @Synchronized
    fun stopRecording(): List<File> {
        val rec = _recording.value ?: return emptyList()
        _recording.value = null
        return runCatching { core.stopRecording().map(::File) }.getOrDefault(rec.files)
    }

    fun close() {
        closedByUser = true
        runCatching { core.disconnect() }
    }

    /**
     * How the core says the grid changed, when it can: an eventfd that the
     * main looper watches, drained and fanned out to the views here.
     *
     * Otherwise [SessionListener.onDamage] says it, from a core thread, over
     * JNA, which reads and writes back a status structure by reflection and
     * attaches the thread to the VM, once for every frame drawn. Only the views
     * listen, and all they do is ask for a frame, which is main-thread work.
     */
    private val damageFd: android.os.ParcelFileDescriptor? = runCatching {
        core.damageFd().takeIf { it >= 0 }?.let { android.os.ParcelFileDescriptor.adoptFd(it) }
    }.getOrNull()
    private val damageDrain = ByteArray(8)
    private val damageWake = android.os.MessageQueue.OnFileDescriptorEventListener { fd, _ ->
        runCatching { android.system.Os.read(fd, damageDrain, 0, 8) }
        damageListeners.forEach { it() }
        android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT
    }

    init {
        damageFd?.let {
            runCatching {
                android.os.Looper.getMainLooper().queue.addOnFileDescriptorEventListener(
                    it.fileDescriptor,
                    android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT,
                    damageWake,
                )
            }
        }
    }

    fun destroy() {
        destroyed = true
        // Before the core goes, so the last of the stream still reaches the file.
        stopRecording()
        close()
        damageListeners.clear()
        scope.cancel()
        damageFd?.let {
            runCatching { android.os.Looper.getMainLooper().queue.removeOnFileDescriptorEventListener(it.fileDescriptor) }
            runCatching { it.close() }
        }
        runCatching { core.destroy() }
        // After the core: this reference is what keeps the session alive for
        // a key that is on its way across as this runs, on another thread.
        synchronized(keyLock) {
            if (keyHandle != 0L) FrameBridge.releaseSession(keyHandle)
            keyHandle = 0L
        }
    }

    /**
     * A strong reference for [FrameBridge.sendKey] and [FrameBridge.sendBytes],
     * taken on the first key.
     *
     * Keys go the plain-JNI way because typing is constant, and through uniffi
     * each one built its record by reflection on both sides. The lock is only
     * ever contended by [destroy], which can run on another thread.
     */
    private val keyLock = Any()
    private var keyHandle = 0L

    fun sendKey(press: dev.flint.term.core.KeyPress) {
        synchronized(keyLock) {
            if (destroyed) return
            val handle = keyHandleLocked()
            if (handle != 0L) {
                FrameBridge.sendKey(handle, press)
                return
            }
        }
        runCatching { core.sendKey(press) }
    }

    /**
     * Typed text, packed into [utf8] by whoever typed it; the same text as
     * [text], which only goes across when there is no handle to send by.
     */
    fun sendText(text: String, utf8: dev.flint.term.terminal.Utf8Buffer) {
        synchronized(keyLock) {
            if (destroyed) return
            val handle = keyHandleLocked()
            if (handle != 0L) {
                if (utf8.length > 0) FrameBridge.sendBytes(handle, utf8.address, utf8.length)
                return
            }
        }
        runCatching { core.sendText(text) }
    }

    private fun keyHandleLocked(): Long {
        if (keyHandle == 0L) keyHandle = runCatching { core.frameHandle().toLong() }.getOrDefault(0L)
        return keyHandle
    }

}

/**
 * The name a rename leaves a session with: what was typed, or [fallback] when
 * nothing was. Clearing the field is how the dialog says "back to the default",
 * so a name of spaces has to mean the same thing as no name at all.
 */
fun renamedTo(typed: String, fallback: String): String = typed.trim().ifBlank { fallback }

/** Helper for blocking host-key prompts from a core thread. */
fun awaitDecision(prompt: HostKeyPrompt): Boolean = runBlocking { prompt.decision.await() }
