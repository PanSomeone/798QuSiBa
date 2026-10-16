package com.killhuishenghuo.ui.login

import com.killhuishenghuo.data.repository.CaptchaChallenge
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which of the three sentences the row under the SMS form is allowed to say.
 *
 * This exists because the row used to branch on two facts while the state had
 * three. `canResendSms` is false both while the clock runs and after it has run
 * out with the fresh captcha unread, so the end of the wait rendered
 * `0 秒后可重新发送` under no button — a sentence that is false (the wait is over)
 * and a state a user cannot leave without guessing that the picture above them
 * has changed. Reported from a phone as "重发验证码没有成功刷新".
 */
class ResendStateTest {

    private fun challenge(key: String = "KEY1") = CaptchaChallenge(key = key, jpegBytes = byteArrayOf(1, 2, 3))

    private fun state(
        phone: String = "13800138000",
        captcha: CaptchaChallenge? = challenge(),
        captchaText: String = "AB12",
        seconds: Int = 0,
        busy: Boolean = false,
    ) = AuthUiState(
        phoneInput = phone,
        captcha = captcha,
        captchaText = captchaText,
        resendSeconds = seconds,
        isBusy = busy,
    )

    @Test
    fun `a full form with the clock run out offers the button`() {
        assertEquals(ResendState.READY, state(seconds = 0).resendState)
    }

    @Test
    fun `while the clock runs it counts down, whatever else is filled in`() {
        assertEquals(ResendState.COUNTING_DOWN, state(seconds = 30).resendState)
        assertEquals(ResendState.COUNTING_DOWN, state(seconds = 30, captchaText = "").resendState)
        assertEquals(ResendState.COUNTING_DOWN, state(seconds = 1, captcha = null, captchaText = "").resendState)
    }

    @Test
    fun `the end of the cooldown with the spent digits cleared is not a countdown`() {
        // The regression, exactly: the ViewModel fetches a fresh captcha at the
        // end of the cooldown and clears the digits that belonged to the old one.
        assertEquals(ResendState.NEEDS_INPUT, state(seconds = 0, captchaText = "").resendState)
    }

    @Test
    fun `reading the fresh captcha turns it back into a button`() {
        assertEquals(ResendState.NEEDS_INPUT, state(seconds = 0, captchaText = "").resendState)
        assertEquals(ResendState.READY, state(seconds = 0, captchaText = "CD34").resendState)
    }

    @Test
    fun `a captcha that never arrived is an input problem, not a finished wait`() {
        assertEquals(ResendState.NEEDS_INPUT, state(seconds = 0, captcha = null, captchaText = "").resendState)
    }

    @Test
    fun `a phone edited back down to nothing is an input problem too`() {
        assertEquals(ResendState.NEEDS_INPUT, state(phone = "138", seconds = 0).resendState)
    }

    @Test
    fun `a request in flight is not an invitation to send a second one`() {
        // Every send costs the account a real SMS, so the state that means "you
        // may send" must not be reachable while the first send is unanswered.
        assertEquals(ResendState.NEEDS_INPUT, state(seconds = 0, busy = true).resendState)
    }
}
