package dev.flint.term.session

import dev.flint.term.data.AuthType
import dev.flint.term.data.Host
import dev.flint.term.data.Identity

/** `ssh-copy-id` without the desktop: append a public key to the remote authorized_keys. */
object KeyDeploy {
    /** Idempotent, permission-safe append. Prints "installed" or "already present". */
    fun script(publicKey: String, path: String = "~/.ssh/authorized_keys"): String {
        val key = publicKey.trim().replace("'", "'\\''")
        val dir = path.substringBeforeLast('/')
        return "umask 077; mkdir -p $dir && chmod 700 $dir && touch $path && chmod 600 $path && " +
            "if grep -qxF '$key' $path; then echo 'already present'; else printf '%s\\n' '$key' >> $path && echo installed; fi"
    }

    /**
     * Connect to [host] (with [password] if given, otherwise the host's saved
     * credentials) and install [identity]'s public key. Blocking; call from IO.
     */
    fun install(sessions: SessionManager, host: Host, identity: Identity, password: String?): Result<String> {
        // A typed password has to win, so the shared account that would
        // otherwise supply the login is set aside for this one connection.
        val login = if (!password.isNullOrEmpty()) {
            host.copy(authType = AuthType.PASSWORD, password = password, accountId = null, username = sessions.loginName(host))
        } else {
            host
        }
        return sessions.runCommand(login, script(identity.publicKey)).map { it.trim().ifBlank { "installed" } }
    }
}
