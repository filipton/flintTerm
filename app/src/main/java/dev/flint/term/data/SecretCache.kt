package dev.flint.term.data

/**
 * The store file's secrets, sealed once and then remembered.
 *
 * Every write of the store used to seal every password, key and passphrase
 * again, and each seal is a round trip into the keystore — often secure
 * hardware — for a value that had not changed. The file is rewritten on a
 * command run or a settings change, so that was dozens of keystore calls to
 * record one line of history.
 *
 * Handing back the ciphertext a secret already had writes exactly the bytes
 * the file held before, so nothing about the sealing is weaker: the value is
 * still one encryption under the keystore key with its own IV. The ciphertexts
 * are kept per occurrence rather than one per value, so two hosts sharing a
 * password still get two different ciphertexts and the file does not say that
 * they match.
 */
internal class SecretCache(
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String,
) {
    /** Ciphertexts known for each plain value, in the order they were last used. */
    private var sealed = HashMap<String, List<String>>()

    /** Opens a stored secret and remembers what it was sealed as. */
    @Synchronized
    fun open(stored: String): String {
        val plain = decrypt(stored)
        // A value stored in the clear by an old version, or one that no longer
        // opens, has no ciphertext worth keeping: the next write seals it anew.
        if (plain.isNotEmpty() && plain != stored) sealed[plain] = sealed[plain].orEmpty() + stored
        return plain
    }

    /**
     * Runs one write of the file, with [block] given the function that seals
     * each secret. What the write did not use is forgotten afterwards, so a
     * changed or deleted secret does not linger here.
     */
    @Synchronized
    fun <R> write(block: ((String) -> String) -> R): R {
        val used = HashMap<String, MutableList<String>>()
        val result = block { plain ->
            if (plain.isEmpty()) return@block ""
            val mine = used.getOrPut(plain) { ArrayList(1) }
            val cipher = sealed[plain]?.getOrNull(mine.size) ?: encrypt(plain)
            mine += cipher
            cipher
        }
        sealed = HashMap(used)
        return result
    }
}
