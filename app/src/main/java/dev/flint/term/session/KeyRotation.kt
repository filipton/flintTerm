package dev.flint.term.session

import dev.flint.term.data.Account
import dev.flint.term.data.AuthType
import dev.flint.term.data.Host
import dev.flint.term.data.HostGroup
import dev.flint.term.data.Identity
import dev.flint.term.data.Store
import dev.flint.term.data.withAccount
import dev.flint.term.data.withGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Replacing a key, as one operation rather than four per server.
 *
 * By hand it is: make a key, copy it to every machine, check each one still
 * lets you in, then go round again deleting the old line — which is why nobody
 * rotates keys. The order here is the part that matters: nothing is taken away
 * from a host until the new key has actually opened it, so a rotation that goes
 * wrong halfway leaves every machine reachable exactly as it was.
 */
object KeyRotation {
    /** A host that logs in with the key, and what handed the key to it. */
    data class User(val host: Host, val through: Account?, val viaGroup: HostGroup?) {
        /** Why this host uses the key, for the row under its name. */
        val reason: String
            get() = when {
                through == null -> host.target
                viaGroup != null -> "${through.label} · from the ${viaGroup.label} group"
                else -> "${through.label} account"
            }
    }

    enum class Stage { WAITING, INSTALLING, VERIFYING, SWITCHED, REMOVING, DONE, FAILED }

    /** Where one host has got to. [detail] is shown as-is, so it says what the server said. */
    data class Progress(val hostId: String, val stage: Stage, val detail: String = "")

    /**
     * The hosts that log in with [identityId], each with the account or group
     * that gave it to them.
     *
     * A host rarely names its own key any more: it names an account, and the
     * account may itself have come from a group. All three have to be looked at
     * the way a connection looks at them, or the rotation would miss servers
     * that quietly depend on this key.
     */
    fun users(
        identityId: String,
        hosts: List<Host>,
        accounts: List<Account>,
        groups: List<HostGroup>,
    ): List<User> = hosts.mapNotNull { raw ->
        val group = groups.firstOrNull { it.id == raw.groupId }
        val inherited = raw.withGroup(group)
        val account = accounts.firstOrNull { it.id == inherited.accountId }
        val effective = inherited.withAccount(account)
        if (effective.authType != AuthType.KEY || effective.identityId != identityId) return@mapNotNull null
        val from = account?.takeIf { it.identityId == identityId }
        User(raw, from, group?.takeIf { from != null && raw.accountId == null })
    }

    /**
     * Take [publicKey] out of a server's `authorized_keys`.
     *
     * The file is rewritten beside itself and moved into place, never truncated
     * where it stands: a connection dropped mid-write would otherwise leave a
     * half-written file, and losing `authorized_keys` on a machine you reach
     * only by key is the one mistake with no way back. A key that is not there
     * and a file that does not exist are both fine — they are the state this
     * was asked to produce.
     */
    fun removeScript(publicKey: String, path: String = "~/.ssh/authorized_keys"): String {
        val key = publicKey.trim().replace("'", "'\\''")
        return "umask 077; P=$path; " +
            "if [ ! -f \$P ]; then echo 'no authorized_keys'; exit 0; fi; " +
            "if ! grep -qxF '$key' \$P; then echo 'not there'; exit 0; fi; " +
            "T=\$P.androidterm.\$\$; grep -vxF '$key' \$P > \$T; s=\$?; " +
            // grep says 1 when it printed nothing, which is what happens when
            // the old key was the only line — an empty file is the right answer
            // there, not a failure.
            "if [ \$s -le 1 ]; then chmod 600 \$T && mv \$T \$P && echo removed; " +
            "else rm -f \$T; echo 'could not rewrite authorized_keys'; exit 1; fi"
    }

    /**
     * Install [new] everywhere [old] is used, prove it, then switch over.
     *
     * Blocking work is kept off the caller's thread; [report] is called as each
     * host moves, on whatever thread got there, so the screen can follow along.
     */
    suspend fun rotate(
        sessions: SessionManager,
        store: Store,
        old: Identity,
        new: Identity,
        targets: List<Host>,
        removeOld: Boolean,
        report: (Progress) -> Unit,
    ): Set<String> = withContext(Dispatchers.IO) {
        val verified = mutableSetOf<String>()
        for (host in targets) {
            report(Progress(host.id, Stage.INSTALLING))
            val installed = KeyDeploy.install(sessions, host, new, null)
            if (installed.isFailure) {
                report(Progress(host.id, Stage.FAILED, installed.exceptionOrNull()?.message ?: "could not install the new key"))
                continue
            }
            // Proof, not optimism: log in again with nothing but the new key.
            report(Progress(host.id, Stage.VERIFYING))
            // Nothing but the new key: a password left on the host would let
            // the connection in even if the key was never installed, and prove
            // nothing at all.
            val onlyNew = host.copy(
                authType = AuthType.KEY, identityId = new.id, accountId = null,
                password = "", username = sessions.loginName(host),
            )
            val proof = sessions.runCommand(onlyNew, "echo androidterm-rotation-ok")
            if (proof.isFailure || proof.getOrNull()?.contains("androidterm-rotation-ok") != true) {
                report(Progress(host.id, Stage.FAILED, proof.exceptionOrNull()?.message ?: "the new key did not get in"))
                continue
            }
            verified += host.id
            report(Progress(host.id, Stage.SWITCHED))
        }

        // Switching happens once, after the whole picture is known. An account
        // is shared, so moving it to the new key would move every host that uses
        // it — including any that has not been proven yet, which would lock that
        // one out. So a shared login only moves when all of its hosts are ready.
        withContext(Dispatchers.Main) { switchOver(store, old, new, targets, verified) }

        if (removeOld) {
            for (host in targets.filter { it.id in verified }) {
                report(Progress(host.id, Stage.REMOVING))
                // Re-read: the host now names the new key, and this connection
                // has to be the one that proves it.
                val current = store.host(host.id) ?: host
                val removed = sessions.runCommand(current, removeScript(old.publicKey))
                report(
                    if (removed.isSuccess) Progress(host.id, Stage.DONE, removed.getOrNull()?.trim().orEmpty())
                    else Progress(host.id, Stage.DONE, "still has the old key: ${removed.exceptionOrNull()?.message}"),
                )
            }
        } else {
            for (host in targets.filter { it.id in verified }) report(Progress(host.id, Stage.DONE))
        }
        verified
    }

    /**
     * Point everything that can safely move at [new].
     *
     * Returns nothing on purpose: what did and did not move is readable from
     * the store afterwards, and the screen already knows which hosts failed.
     */
    private fun switchOver(store: Store, old: Identity, new: Identity, targets: List<Host>, verified: Set<String>) {
        for (host in targets) {
            if (host.id !in verified) continue
            val current = store.host(host.id) ?: continue
            if (current.identityId == old.id) store.upsertHost(current.copy(identityId = new.id))
        }
        val stillOld = targets.filterNot { it.id in verified }.map { it.id }.toSet()
        for (account in store.accounts.value) {
            if (account.identityId != old.id) continue
            val itsHosts = users(old.id, store.hosts.value, store.accounts.value, store.groups.value)
                .filter { it.through?.id == account.id }
                .map { it.host.id }
            if (itsHosts.isNotEmpty() && itsHosts.none { it in stillOld }) {
                store.upsertAccount(account.copy(identityId = new.id))
            }
        }
    }
}
