package com.killhuishenghuo.data.repository

import com.killhuishenghuo.data.model.Account
import com.killhuishenghuo.data.model.Outcome

/**
 * Signing in, and proving a token works.
 *
 * Two ways in, in this order of reliability:
 *
 *  1. [adoptToken] — paste a token the official app already holds. This is the
 *     only path that needs nothing this client cannot reproduce, and it is why
 *     the login screen offers it first.
 *  2. [sendSmsCode] then [login] — the gateway's own SMS flow. It needs a
 *     captcha and a `cid` whose real value is a build secret of the official
 *     app (see the README's 待补充清单), so it can fail for reasons that have
 *     nothing to do with the account.
 */
interface AuthRepository {

    /** `captcha/`. Returns JPEG bytes for the code the user has to read. */
    suspend fun loadCaptcha(): Outcome<CaptchaChallenge>

    /** `acc/login/code`. Sends the SMS. */
    suspend fun sendSmsCode(
        phone: String,
        captchaText: String,
        captchaKey: String,
    ): Outcome<Unit>

    /** `acc/login`. Persists the token on success. */
    suspend fun login(phone: String, smsCode: String): Outcome<Account>

    /**
     * Stores [token] and then *verifies* it against `ui/app/master`.
     *
     * The verification is the point: pasting a stale token would otherwise
     * look like a successful login followed by an empty device list, which is
     * indistinguishable from an account with no devices bound.
     */
    suspend fun adoptToken(token: String): Outcome<Account>

    /** Clears the persisted session. */
    fun logout()
}

/**
 * A captcha and the key it was fetched under.
 *
 * [key] must be sent back with the SMS request — the server matches the two, so
 * the pair is treated as one value rather than two loose strings.
 */
data class CaptchaChallenge(
    val key: String,
    val jpegBytes: ByteArray,
) {
    // Data-class equality over a ByteArray needs these spelled out; without
    // them two different captchas would compare equal and a Compose
    // recomposition keyed on this object would not fire.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptchaChallenge) return false
        return key == other.key && jpegBytes.contentEquals(other.jpegBytes)
    }

    override fun hashCode(): Int = 31 * key.hashCode() + jpegBytes.contentHashCode()
}
