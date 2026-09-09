package dev.flint.term.data

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OpenSSH's hashed `known_hosts` host field — `|1|<base64 salt>|<base64 hash>`,
 * where the hash is `HMAC-SHA1(key = salt, message = the host field a plain
 * entry would have carried)`.
 *
 * The name cannot be read back out; that is the point of the format. The one
 * question this can answer is whether a name we already have is the name an
 * entry was made from, which is enough to verify a server key.
 *
 * This code decides whether a host key is trusted, so it is deliberately
 * quiet: a field that is truncated, wrongly sized or not base64 at all is
 * simply not this host, and no failure in the HMAC path escapes as an
 * exception into the middle of a connection.
 */
object KnownHostMatch {
    /** The only hash type OpenSSH has ever written, and the only one it reads. */
    private const val MAGIC = "|1|"

    /** SHA-1's digest length, which OpenSSH demands of the salt as well as the hash. */
    private const val DIGEST_BYTES = 20

    private const val DEFAULT_PORT = 22

    /**
     * The string OpenSSH hashes: the bare name on the default port, `[name]:port`
     * on any other, lowercased throughout. `ssh` folds a destination to lowercase
     * long before `known_hosts` is consulted, and `ssh-keygen -H` lowercases as it
     * rewrites the file, so what a salt covers is always the lowercase form.
     */
    fun hostField(host: String, port: Int): String {
        val name = host.lowercase()
        return if (port == DEFAULT_PORT || port == 0) name else "[$name]:$port"
    }

    /**
     * The two base64 halves of a `|1|salt|hash` field, or null if the field is
     * not one or does not carry a SHA-1-sized salt and hash.
     */
    fun parseField(field: String): Pair<String, String>? {
        if (!field.startsWith(MAGIC)) return null
        val parts = field.substring(MAGIC.length).split('|')
        if (parts.size != 2) return null
        val (salt, hash) = parts
        if (decode(salt)?.size != DIGEST_BYTES || decode(hash)?.size != DIGEST_BYTES) return null
        return salt to hash
    }

    /**
     * The entry that stands for [host] on [port]: the one saved under that name
     * if there is one, and otherwise the first hashed entry that proves to be it.
     * A name we have is always the better answer, so the hashes are walked only
     * when nothing was saved under it.
     */
    fun find(entries: List<KnownHost>, host: String, port: Int): KnownHost? =
        entries.firstOrNull { !it.isHashed && it.host == host && it.port == port }
            ?: entries.firstOrNull { matches(it, host, port) }

    /** Whether this hashed entry is the record of [host] on [port]. A plain entry never matches here. */
    fun matches(entry: KnownHost, host: String, port: Int): Boolean =
        entry.isHashed && matches(entry.hashSalt, entry.hashedHost, host, port)

    fun matches(salt: String, hashedHost: String, host: String, port: Int): Boolean {
        if (host.isBlank()) return false
        val key = decode(salt)?.takeIf { it.size == DIGEST_BYTES } ?: return false
        val expected = decode(hashedHost)?.takeIf { it.size == DIGEST_BYTES } ?: return false
        val got = hmacSha1(key, hostField(host, port)) ?: return false
        return got.contentEquals(expected)
    }

    private fun hmacSha1(key: ByteArray, message: String): ByteArray? = runCatching {
        Mac.getInstance("HmacSHA1").run {
            init(SecretKeySpec(key, "HmacSHA1"))
            doFinal(message.toByteArray(Charsets.UTF_8))
        }
    }.getOrNull()

    private fun decode(base64: String): ByteArray? = runCatching { Base64.getDecoder().decode(base64) }.getOrNull()
}
