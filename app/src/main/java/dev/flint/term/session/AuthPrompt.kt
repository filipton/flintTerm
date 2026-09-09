package dev.flint.term.session

import dev.flint.term.core.AuthPrompter
import dev.flint.term.core.Prompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** One login question the server asked, and the answer the UI owes it. */
data class AuthPrompt(
    /** The server's name for the challenge; usually empty. */
    val name: String,
    /** What the server says about it — often where a URL or a hint lives. */
    val instruction: String,
    val fields: List<Prompt>,
    /** Completed with one answer per field, or null when the person walked away. */
    val answers: CompletableDeferred<List<String>?>,
) {
    fun answer(values: List<String>) {
        answers.complete(values)
    }

    fun cancel() {
        answers.complete(null)
    }
}

/**
 * The person, in front of whatever the server asked to let them in.
 *
 * Keyboard-interactive is a conversation the app cannot have on anyone's
 * behalf: a verification code is on another device, and a password the server
 * has decided to expire has to be replaced by someone who knows what to replace
 * it with. So the question is put on screen and the connection waits.
 *
 * The request arrives on a blocking thread deep inside a connection, so the
 * answer is waited for rather than polled — and nobody at the keyboard is not
 * an answer of "yes" but an abandoned login.
 */
class AuthPrompts(private val waitMillis: Long = WAIT_MS) {
    private val _pending = MutableStateFlow<AuthPrompt?>(null)
    val pending: StateFlow<AuthPrompt?> = _pending

    /**
     * Ask, and block until it is answered, dismissed or given up on.
     *
     * One question at a time: two connections that both need a code would
     * otherwise stack their dialogs, and the one underneath would be answered
     * blind.
     */
    fun ask(name: String, instruction: String, fields: List<Prompt>): List<String>? = synchronized(this) {
        val prompt = AuthPrompt(name, instruction, fields, CompletableDeferred())
        _pending.value = prompt
        try {
            runBlocking { withTimeoutOrNull(waitMillis) { prompt.answers.await() } }
        } finally {
            _pending.value = null
        }
    }

    /** Ready to hand to the core, which knows nothing of dialogs. */
    fun prompter(): AuthPrompter = object : AuthPrompter {
        override fun ask(name: String, instruction: String, prompts: List<Prompt>): List<String>? =
            this@AuthPrompts.ask(name, instruction, prompts)
    }

    companion object {
        /**
         * Long enough to unlock another phone and read a code off it. The core
         * gives up on the same schedule, so a dialog never outlives the
         * connection that is waiting behind it.
         */
        const val WAIT_MS = 120_000L
    }
}

/** A session that can never be asked anything: a serial line, a recording. */
val NO_PROMPTS: AuthPrompter = object : AuthPrompter {
    override fun ask(name: String, instruction: String, prompts: List<Prompt>): List<String>? = null
}
