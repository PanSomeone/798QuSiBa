package com.killhuishenghuo.ui.login

import com.killhuishenghuo.data.repository.CaptchaChallenge

/**
 * Which way the user is getting in.
 *
 * [SMS] is first and is the default because it is the ordinary path: it needs
 * nothing the user has to obtain from somewhere else. [TOKEN] stays as the
 * escape hatch for the case SMS cannot cover — a `cid` the gateway dislikes, or
 * a session that already exists in the official app.
 */
enum class LoginMode {
    /** The gateway's own SMS flow. Needs a captcha and a `cid`. */
    SMS,

    /** Paste a token the official app already has. Needs nothing this client cannot reproduce. */
    TOKEN,
}

/**
 * State of the sign-in screen.
 *
 * [captchaText] lives here rather than in the composable because it is half of
 * a pair: the server remembers the captcha under [CaptchaChallenge.key] and
 * matches the two, so the typed code and the key behind the picture must not be
 * able to drift apart across a recomposition.
 */
data class AuthUiState(
    val mode: LoginMode = LoginMode.SMS,
    val tokenInput: String = "",
    val phoneInput: String = "",
    val captchaText: String = "",
    val smsCodeInput: String = "",
    val captcha: CaptchaChallenge? = null,
    val isLoadingCaptcha: Boolean = false,
    val isSmsSent: Boolean = false,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    /**
     * Empty while signed out, the account's display name once in.
     *
     * A value rather than a one-shot `signedIn` flag: this state now feeds a
     * settings row that has to say *who* is signed in, and a flag that has to be
     * consumed and reset by the host is exactly the kind of state that ends up
     * showing "signed in" next to a name from the previous account.
     */
    val accountName: String = "",
    /**
     * Seconds left before another code may be requested, 0 when it may be now.
     *
     * A number rather than a `isCoolingDown` flag because the wait is shown to
     * the user. "稍后再试" tells somebody nothing about whether to wait five
     * seconds or give up, and this screen is already the one place in the app
     * where a user has to do arithmetic with a clock.
     */
    val resendSeconds: Int = 0,
) {
    /** Whether the settings row should offer sign-out rather than sign-in. */
    val isSignedIn: Boolean get() = accountName.isNotEmpty()

    /** Token paste is ready when something that looks like a token is present. */
    val canSubmitToken: Boolean get() = !isBusy && tokenInput.trim().length >= MIN_TOKEN_LENGTH

    /** The SMS request needs a phone number, a captcha picture and the code on it. */
    val canSendSms: Boolean
        get() = !isBusy &&
            phoneInput.trim().length >= PHONE_LENGTH &&
            captcha != null &&
            captchaText.isNotBlank()

    /**
     * A resend is the same request as a send, plus the clock.
     *
     * The gateway will happily send a second code to the same number, so the
     * limit is entirely local: it exists to stop a user who did not receive the
     * first code from tapping until the gateway rate-limits the whole account.
     */
    val canResendSms: Boolean get() = canSendSms && resendSeconds == 0

    /** Signing in needs a code to have been sent and typed. */
    val canSubmitSms: Boolean get() = !isBusy && isSmsSent && smsCodeInput.trim().isNotEmpty()

    companion object {
        /** Mainland mobile numbers are 11 digits; the gateway rejects anything shorter. */
        const val PHONE_LENGTH = 11

        /**
         * Short enough to accept any real token, long enough that a stray paste
         * of a phone number or a chat message fails here rather than at the
         * server with an opaque `-99`.
         */
        const val MIN_TOKEN_LENGTH = 16
    }
}

/**
 * How long the resend button stays disabled after a code is sent.
 *
 * Thirty seconds is the number Chinese SMS gateways themselves use, which makes
 * it the one wait a user of this app has already been trained on elsewhere.
 */
const val RESEND_COOLDOWN_SECONDS = 30
