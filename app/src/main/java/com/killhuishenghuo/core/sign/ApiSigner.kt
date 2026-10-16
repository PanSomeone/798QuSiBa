package com.killhuishenghuo.core.sign

import java.security.MessageDigest
import java.util.UUID

/**
 * Reimplementation of the "Silkworm"-style request signing used by 惠生活798.
 *
 * Every request must carry three headers:
 *
 *  - `X-Garen` = current epoch milliseconds, as a decimal string.
 *  - `X-Nami`  = the device identity:
 *      * logged out -> first 17 chars of a dash-stripped UUID
 *      * logged in  -> a 16-char random string with the `silk_id` inserted into it
 *    See [generateNami] / [generateNamiForLoggedIn].
 *  - `X-Ashe`  = `MD5( MD5((servername + "." + methodname).lowercase()) + X-Garen + X-Nami )`
 *    See [generateAshe].
 *
 * The middle MD5 hashes the *lowercased* `servername.methodname` pair; the outer
 * MD5 hashes that hex digest concatenated with the two other header values, in
 * the order Garen-then-Nami. Hex output is lowercase in both steps.
 *
 * All functions here are pure and deterministic apart from [generateGaren] and
 * the random part of [generateNamiForLoggedIn], which is what makes them testable.
 */
object ApiSigner {

    /** Length of the logged-out device identity, after dashes are removed. */
    const val NAMI_LOGGED_OUT_LENGTH: Int = 17

    /** Length of the random string a logged-in identity is built from. */
    const val NAMI_LOGGED_IN_SOURCE_LENGTH: Int = 16

    /** Alphabet used for the random half of a logged-in identity. */
    private const val ALPHABET: String = "0123456789abcdefghijklmnopqrstuvwxyz"

    /** `X-Garen`: the current time in epoch milliseconds. */
    fun generateGaren(clock: () -> Long = System::currentTimeMillis): String =
        clock().toString()

    /**
     * Logged-out `X-Nami`: a fresh UUID with its dashes removed, truncated to
     * [NAMI_LOGGED_OUT_LENGTH] characters.
     */
    fun generateNami(uuid: String = UUID.randomUUID().toString()): String =
        uuid.replace("-", "").take(NAMI_LOGGED_OUT_LENGTH)

    /**
     * Logged-in `X-Nami`: [NAMI_LOGGED_IN_SOURCE_LENGTH] random characters with
     * `silkId` spliced in.
     *
     * TODO-3 (unverified): the exact splice *offset* is unknown — the upstream
     * client only tells us "insert the silk_id into a 16-char random string".
     * [insertIndex] is therefore a parameter: change it once a captured request
     * pins the real position. Length is `16 + silkId.length` either way.
     */
    fun generateNamiForLoggedIn(
        silkId: String,
        insertIndex: Int = 8,
        random: String = randomString(NAMI_LOGGED_IN_SOURCE_LENGTH),
    ): String {
        require(silkId.isNotEmpty()) { "silkId must not be empty" }
        val at = insertIndex.coerceIn(0, random.length)
        return random.substring(0, at) + silkId + random.substring(at)
    }

    /**
     * `X-Ashe` for a request to [serverName] / [methodName] carrying [garen] and
     * [nami]. Both signature steps use lowercase hex MD5.
     */
    fun generateAshe(
        serverName: String,
        methodName: String,
        garen: String,
        nami: String,
    ): String {
        val seed = md5("$serverName.$methodName".lowercase())
        return md5(seed + garen + nami)
    }

    /** Lowercase hex MD5 of [input]'s UTF-8 bytes. */
    fun md5(input: String): String = md5(input.toByteArray(Charsets.UTF_8))

    /** Lowercase hex MD5 of [bytes]. */
    fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4])
            out.append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    /** [length] characters drawn uniformly from [ALPHABET]. */
    fun randomString(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(ALPHABET[kotlin.random.Random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
