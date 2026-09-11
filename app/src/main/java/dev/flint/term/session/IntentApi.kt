package dev.flint.term.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import dev.flint.term.App
import dev.flint.term.core.KeyCode
import dev.flint.term.core.KeyEventKind
import dev.flint.term.core.KeyPress
import dev.flint.term.data.AutomationCall
import dev.flint.term.data.Host
import dev.flint.term.data.Snippet
import dev.flint.term.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The actions other apps can drive this client with, and the reading of them.
 *
 * Everything here is a plain function over saved data so that the parts that
 * decide — which host was meant, whether the call is even usable — can be
 * tested without a device. [AutomationReceiver] does the rest.
 */
object IntentApi {
    const val ACTION_CONNECT = "dev.flint.term.action.CONNECT"
    const val ACTION_RUN = "dev.flint.term.action.RUN"
    const val ACTION_SNIPPET = "dev.flint.term.action.SNIPPET"
    const val ACTION_DISCONNECT = "dev.flint.term.action.DISCONNECT"

    const val EXTRA_HOST = "host"
    const val EXTRA_COMMAND = "command"
    const val EXTRA_SNIPPET = "snippet"

    /** Result extras, for callers that would rather read a bundle than a string. */
    const val EXTRA_OUTPUT = "output"
    const val EXTRA_STATUS = "status"

    /** Said when the switch in Settings is off, which is how it ships. */
    const val OFF = "Automation is off. Turn it on in flintTerm under Settings → Automation."

    /**
     * Whether [caller] may drive the app.
     *
     * A caller Android will not name cannot be told apart from any other, so
     * there the switch in Settings is the whole gate — that is every caller
     * below Android 14, and still some above it, `adb` among them. Where the
     * name is known it has to have been allowed: an app nobody has heard of
     * should not get a shell on their servers because it guessed an action
     * name. Settings says which of the two is in force.
     */
    fun trusted(caller: String, allowed: List<String>): Boolean =
        caller.isBlank() || caller in allowed

    /** Why a call was turned away, naming the app so it can be allowed. */
    fun untrusted(caller: String): String =
        "flintTerm has not been told to trust $caller. Allow it under Settings → Automation, where this call is now listed."

    /** What a caller asked for, once the intent has been read. */
    sealed interface Request {
        data class Connect(val host: String) : Request
        data class Run(val host: String, val command: String) : Request
        data class Type(val snippet: String, val host: String?) : Request
        data class Close(val host: String?) : Request

        /** Nothing to do, and the sentence that says why. */
        data class Refused(val reason: String) : Request
    }

    /**
     * The intent as a request, or a [Request.Refused] carrying what is missing.
     *
     * A missing extra is answered here rather than half-way through the work, so
     * a caller with a typo in an extra name learns it from the result instead of
     * from a session that never appears.
     */
    fun request(action: String?, host: String?, command: String?, snippet: String?): Request {
        val where = host?.trim()?.ifEmpty { null }
        return when (action) {
            ACTION_CONNECT -> where?.let { Request.Connect(it) }
                ?: Request.Refused("CONNECT needs a host extra: an id, a label or user@hostname")
            ACTION_RUN -> {
                val what = command?.trim()?.ifEmpty { null }
                when {
                    where == null -> Request.Refused("RUN needs a host extra: an id, a label or user@hostname")
                    what == null -> Request.Refused("RUN needs a command extra")
                    else -> Request.Run(where, what)
                }
            }
            ACTION_SNIPPET -> snippet?.trim()?.ifEmpty { null }?.let { Request.Type(it, where) }
                ?: Request.Refused("SNIPPET needs a snippet extra: its name or its id")
            ACTION_DISCONNECT -> Request.Close(where)
            else -> Request.Refused("“$action” is not one of CONNECT, RUN, SNIPPET or DISCONNECT")
        }
    }

    /** One line for the record of calls, saying what was asked rather than what came of it. */
    fun summarize(request: Request): String = when (request) {
        is Request.Connect -> "Connect to ${request.host}"
        is Request.Run -> "Run ${request.command} on ${request.host}"
        is Request.Type -> "Type ${request.snippet}" + (request.host?.let { " on $it" }.orEmpty())
        is Request.Close -> "Disconnect " + (request.host ?: "everything")
        is Request.Refused -> "Unusable call"
    }

    /**
     * The hosts [query] can stand for: none, exactly one, or the several it is
     * short for.
     *
     * Read from the narrowest form to the widest, because an id can only have
     * been meant one way while a bare hostname is the one two saved hosts are
     * most likely to share — and a rung that finds anything is the answer, so a
     * label typed exactly is never beaten by a hostname that happens to match.
     */
    fun resolveHost(hosts: List<Host>, query: String): List<Host> {
        val want = query.trim()
        if (want.isEmpty()) return emptyList()
        val rungs = listOf<(Host) -> Boolean>(
            { it.id == want },
            { it.label.isNotBlank() && it.label.equals(want, ignoreCase = true) },
            { it.target.equals(want, ignoreCase = true) },
            { it.username.isNotBlank() && "${it.username}@${it.hostname}".equals(want, ignoreCase = true) },
            { it.hostname.isNotBlank() && it.hostname.equals(want, ignoreCase = true) },
        )
        return rungs.firstNotNullOfOrNull { rung -> hosts.filter(rung).ifEmpty { null } } ?: emptyList()
    }

    /** The same ladder for snippets: the id, then the name as it is written. */
    fun resolveSnippet(snippets: List<Snippet>, query: String): List<Snippet> {
        val want = query.trim()
        if (want.isEmpty()) return emptyList()
        val rungs = listOf<(Snippet) -> Boolean>(
            { it.id == want },
            { it.name.isNotBlank() && it.name.equals(want, ignoreCase = true) },
        )
        return rungs.firstNotNullOfOrNull { rung -> snippets.filter(rung).ifEmpty { null } } ?: emptyList()
    }

    /**
     * Why a lookup did not land on one thing, or null when it did. Names the
     * candidates when there are several: the caller can only fix an ambiguity it
     * can see.
     */
    fun <T> problem(kind: String, query: String, found: List<T>, name: (T) -> String): String? = when {
        found.isEmpty() -> "No $kind matches “$query”"
        found.size == 1 -> null
        else -> "“$query” matches ${found.size} ${kind}s: " + found.joinToString(", ", transform = name)
    }

    /** What a command did: its exit status, and everything it printed. */
    data class Outcome(val status: Int, val output: String)

    /**
     * A [SessionManager.runCommand] result read as a shell would read it.
     *
     * The core turns a non-zero exit into an error carrying both the status and
     * the output, since for everything else in the app that is a failure. An
     * automation caller wants them apart again — a `grep` that finds nothing is
     * an answer, not a breakdown — so the status is taken back out of the
     * message. A failure that was not the command's own (no route, refused
     * login) has no status of its own and counts as 1.
     */
    fun outcome(result: Result<String>): Outcome = result.fold(
        { Outcome(0, it) },
        { error ->
            val message = error.message.orEmpty()
            val exited = EXITED.find(message)
            if (exited != null) {
                Outcome(exited.groupValues[1].toIntOrNull() ?: 1, message.removeRange(exited.range))
            } else {
                Outcome(1, message)
            }
        },
    )

    private val EXITED = Regex("^command exited with (\\d+): ?")
}

/**
 * The remote control: Tasker, Automate, a shortcut app or `adb shell am` can
 * connect to a host, run a command on it, type a snippet into its session or
 * close it, and read the answer off the ordered broadcast.
 *
 * Three things stand between a stranger and the keys this app holds. The switch
 * in Settings is off until somebody turns it on, and a call arriving while it is
 * off is answered saying so rather than ignored; an app that has not been
 * allowed is turned away by name, and appears under that switch where one tap
 * allows it; and every call is written into a short record shown there too.
 */
class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? App ?: return
        val request = IntentApi.request(
            intent.action,
            intent.getStringExtra(IntentApi.EXTRA_HOST),
            intent.getStringExtra(IntentApi.EXTRA_COMMAND),
            intent.getStringExtra(IntentApi.EXTRA_SNIPPET),
        )
        // Only from Android 14 on does the platform say who sent a broadcast.
        // Below that the record has to leave the column blank rather than guess.
        val caller = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) sentFromPackage.orEmpty() else ""
        val pending = goAsync()
        scope.launch {
            val settings = app.store.settings.value
            val answer = if (!settings.automation) {
                Answer(1, IntentApi.OFF)
            } else if (!IntentApi.trusted(caller, settings.automationAllowed)) {
                Answer(1, IntentApi.untrusted(caller))
            } else {
                // Started on the scope rather than inside the wait, so that
                // running out of patience only ends the answer: a login that is
                // slow to come up is still a login, and the caller only ever
                // asked for a reply within a broadcast's lifetime.
                val work = scope.async(Dispatchers.IO) { runCatching { handle(context, app, request) } }
                withTimeoutOrNull(WORK_TIMEOUT_MS) { work.await() }
                    ?.getOrElse { Answer(1, it.message ?: "the call failed") }
                    ?: Answer(1, "Still running after ${WORK_TIMEOUT_MS / 1000}s. flintTerm has not given up on it")
            }
            record(app, caller, request, answer)
            pending.setResult(
                answer.status,
                answer.text,
                Bundle().apply {
                    putString(IntentApi.EXTRA_OUTPUT, answer.text)
                    putInt(IntentApi.EXTRA_STATUS, answer.status)
                },
            )
            pending.finish()
        }
    }

    /** The result code and the result string a caller gets back. */
    private data class Answer(val status: Int, val text: String)

    private fun handle(context: Context, app: App, request: IntentApi.Request): Answer = when (request) {
        is IntentApi.Request.Refused -> Answer(1, request.reason)
        is IntentApi.Request.Connect -> connect(context, app, request.host)
        is IntentApi.Request.Run -> run(app, request.host, request.command)
        is IntentApi.Request.Type -> type(app, request.snippet, request.host)
        is IntentApi.Request.Close -> close(app, request.host)
    }

    /**
     * Open the host and come to the front.
     *
     * The activity does the connecting, exactly as a tap on a home-screen
     * shortcut would, so there is one path into a session rather than two.
     * Android may refuse to bring an app in the background forward, and says so
     * in the result instead of leaving the caller wondering.
     */
    private fun connect(context: Context, app: App, query: String): Answer = withHost(app, query) { host ->
        val live = app.sessions.sessions.value.firstOrNull { it.host?.id == host.id && !it.isFinished }
        val intent = if (live != null) {
            Intent(context, MainActivity::class.java)
                .setAction(Shortcuts.ACTION_OPEN_SESSION)
                .putExtra(Shortcuts.EXTRA_SESSION_ID, live.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } else {
            Shortcuts.connectIntent(context, host)
        }
        runCatching { context.startActivity(intent) }.fold(
            { Answer(0, if (live != null) "Opened the session on ${host.displayName}" else "Connecting to ${host.displayName}") },
            { Answer(1, "Could not bring flintTerm forward: ${it.message}") },
        )
    }

    private fun run(app: App, query: String, command: String): Answer = withHost(app, query) { host ->
        if (host.isTelnet || host.hostname.isBlank()) {
            return@withHost Answer(1, "${host.displayName} has no command channel. RUN needs an SSH host")
        }
        val outcome = IntentApi.outcome(app.sessions.runCommand(host, command))
        Answer(outcome.status, outcome.output)
    }

    /**
     * Type a snippet into a session that is already up.
     *
     * Nothing is connected for it: a snippet is a keystroke, and typing one into
     * a machine the person did not open would be a surprise rather than a
     * convenience.
     */
    private fun type(app: App, query: String, hostQuery: String?): Answer {
        val live = app.sessions.sessions.value.filterNot { it.isFinished }
        val session = if (hostQuery != null) {
            val hosts = IntentApi.resolveHost(app.store.hosts.value, hostQuery)
            IntentApi.problem("host", hostQuery, hosts) { it.displayName }?.let { return Answer(1, it) }
            live.firstOrNull { it.host?.id == hosts.first().id }
                ?: return Answer(1, "No session is open on ${hosts.first().displayName}")
        } else {
            live.singleOrNull() ?: return Answer(
                1,
                if (live.isEmpty()) "No session is open" else "${live.size} sessions are open. Say which one with the host extra",
            )
        }
        val found = IntentApi.resolveSnippet(app.store.snippets.value, query)
        IntentApi.problem("snippet", query, found) { it.name.ifBlank { it.command } }?.let { return Answer(1, it) }
        val snippet = found.first()
        // A placeholder is a question, and a broadcast has nobody standing by to
        // answer it; typing the {{braces}} verbatim would be worse than refusing.
        if (snippet.placeholders.isNotEmpty()) {
            return Answer(1, "That snippet asks for ${snippet.placeholders.joinToString(", ")}, which only the keyboard can fill in")
        }
        return runCatching {
            session.core.sendText(snippet.command)
            if (snippet.run) session.core.sendKey(KeyPress(KeyCode.Enter, false, false, false, KeyEventKind.PRESS))
        }.fold(
            { Answer(0, "Typed it into ${session.label}") },
            { Answer(1, "Could not type into ${session.label}: ${it.message}") },
        )
    }

    private fun close(app: App, query: String?): Answer {
        val live = app.sessions.sessions.value.filterNot { it.isFinished }
        if (query == null) {
            live.forEach { app.sessions.remove(it.id) }
            return Answer(0, if (live.isEmpty()) "Nothing was open" else "Closed ${count(live.size)}")
        }
        return withHost(app, query) { host ->
            val mine = live.filter { it.host?.id == host.id }
            mine.forEach { app.sessions.remove(it.id) }
            Answer(0, if (mine.isEmpty()) "Nothing was open on ${host.displayName}" else "Closed ${count(mine.size)} on ${host.displayName}")
        }
    }

    private fun count(n: Int): String = if (n == 1) "1 session" else "$n sessions"

    private inline fun withHost(app: App, query: String, body: (Host) -> Answer): Answer {
        val found = IntentApi.resolveHost(app.store.hosts.value, query)
        IntentApi.problem("host", query, found) { it.displayName }?.let { return Answer(1, it) }
        return body(found.first())
    }

    private fun record(app: App, caller: String, request: IntentApi.Request, answer: Answer) {
        val call = AutomationCall(
            caller = caller,
            what = IntentApi.summarize(request),
            result = answer.text.trim().lineSequence().first().take(120),
        )
        app.store.updateSettings { it.copy(automationLog = (listOf(call) + it.automationLog).take(LOG_LENGTH)) }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Long enough for a login and a command on a slow link, short enough
         * that Android does not decide the receiver has hung.
         */
        const val WORK_TIMEOUT_MS = 45_000L

        /** Enough to see what has been happening without turning Settings into a log viewer. */
        const val LOG_LENGTH = 10
    }
}
