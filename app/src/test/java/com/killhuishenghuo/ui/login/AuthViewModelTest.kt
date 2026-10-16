package com.killhuishenghuo.ui.login

import com.killhuishenghuo.data.model.Account
import com.killhuishenghuo.data.model.AppError
import com.killhuishenghuo.data.model.Outcome
import com.killhuishenghuo.data.repository.AuthRepository
import com.killhuishenghuo.data.repository.CaptchaChallenge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sign-in screen's ViewModel.
 *
 * Most of what is asserted here is the *gating*: which button is enabled, and
 * what the ViewModel refuses to send. Every request the SMS path makes costs the
 * account a real SMS from the gateway, and a token submission that runs on a
 * half-typed token both wastes a round trip and can sign the user into the wrong
 * account, so the guards are the feature.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val repository = FakeAuthRepository()

    /**
     * The scheduler behind the test dispatcher.
     *
     * Held because `advanceUntilIdle` is the wrong tool for the resend cooldown:
     * it drains the clock to the end of *all* pending work, so it runs the full
     * thirty seconds and the countdown is already at zero by the time the test
     * looks. `runCurrent` runs what is runnable without moving the clock, which
     * is what observing "thirty seconds have just started" requires.
     */
    private lateinit var scheduler: TestCoroutineScheduler

    @Before
    fun setUp() {
        val dispatcher = UnconfinedTestDispatcher()
        scheduler = dispatcher.scheduler
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --------------------------------------------------------------- captcha

    @Test
    fun `init fetches a captcha so the SMS tab has a picture ready`() = runTest {
        val viewModel = AuthViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.captcha)
        assertEquals("KEY1", state.captcha?.key)
        assertFalse(state.isLoadingCaptcha)
        assertEquals(1, repository.loadCaptchaCalls)
    }

    @Test
    fun `a captcha fetch that fails says so instead of spinning`() = runTest {
        repository.loadCaptchaAnswer = { Outcome.Failure(AppError.Offline) }

        val viewModel = AuthViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.captcha)
        assertFalse(state.isLoadingCaptcha)
        assertEquals(AppError.Offline.toChinese(), state.errorMessage)
    }

    // ----------------------------------------------------------------- input

    @Test
    fun `onPhoneChange keeps digits only and stops at eleven characters`() = runTest {
        val viewModel = AuthViewModel(repository)
        advanceUntilIdle()

        viewModel.onPhoneChange("138abc0000xyz111")
        // Letters and spaces are dropped as they are typed; the gateway rejects
        // anything that is not a bare number.
        assertEquals("1380000111", viewModel.uiState.value.phoneInput)

        viewModel.onPhoneChange("138123456789")
        // Long enough, and the twelfth digit is discarded rather than sent.
        assertEquals("13812345678", viewModel.uiState.value.phoneInput)
    }

    @Test
    fun `canSendSms needs a phone, a captcha and the code on it`() = runTest {
        val viewModel = AuthViewModel(repository)
        advanceUntilIdle()

        assertFalse("nothing typed yet", viewModel.uiState.value.canSendSms)

        viewModel.onPhoneChange("1380013800")
        assertFalse("ten digits is not a phone number", viewModel.uiState.value.canSendSms)

        viewModel.onPhoneChange("13800138000")
        assertFalse("a picture with nothing read off it is not a captcha", viewModel.uiState.value.canSendSms)

        viewModel.onCaptchaTextChange("  AB12  ")
        assertEquals("AB12", viewModel.uiState.value.captchaText)
        assertTrue(viewModel.uiState.value.canSendSms)
    }

    @Test
    fun `canSubmitToken starts at exactly sixteen trimmed characters`() = runTest {
        val viewModel = AuthViewModel(repository)
        advanceUntilIdle()

        viewModel.onTokenChange("123456789012345")
        assertEquals(15, viewModel.uiState.value.tokenInput.length)
        assertFalse(viewModel.uiState.value.canSubmitToken)

        viewModel.onTokenChange("1234567890123456")
        assertEquals(AuthUiState.MIN_TOKEN_LENGTH, viewModel.uiState.value.tokenInput.length)
        assertTrue(viewModel.uiState.value.canSubmitToken)

        // Whitespace does not count towards the length: a stray newline from the
        // clipboard must not turn a 15-character paste into a valid one.
        viewModel.onTokenChange("123456789012345     ")
        assertFalse(viewModel.uiState.value.canSubmitToken)
    }

    @Test
    fun `switching mode keeps what the other tab had entered`() = runTest {
        val viewModel = AuthViewModel(repository)
        advanceUntilIdle()
        viewModel.onTokenChange("1234567890123456")
        viewModel.onPhoneChange("13800138000")
        viewModel.onCaptchaTextChange("AB12")

        viewModel.selectMode(LoginMode.SMS)

        val sms = viewModel.uiState.value
        assertEquals(LoginMode.SMS, sms.mode)
        // Switching tabs must not throw away a captcha that is still valid on
        // the server, nor the token the user just pasted.
        assertEquals("1234567890123456", sms.tokenInput)
        assertEquals("13800138000", sms.phoneInput)
        assertEquals("AB12", sms.captchaText)

        viewModel.selectMode(LoginMode.TOKEN)
        assertEquals(LoginMode.TOKEN, viewModel.uiState.value.mode)
        assertEquals("13800138000", viewModel.uiState.value.phoneInput)
    }

    // ----------------------------------------------------------- token flow

    @Test
    fun `submitToken signs in and clears the pasted token`() = runTest {
        val viewModel = AuthViewModel(repository)
        advanceUntilIdle()
        viewModel.onTokenChange("1234567890123456")

        viewModel.submitToken()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isSignedIn)
        assertFalse(state.isBusy)
        assertEquals("", state.tokenInput)
        assertEquals(listOf("1234567890123456"), repository.adoptTokenCalls)
    }

    @Test
    fun `submitToken failure shows the reason and keeps the input for a retry`() = runTest {
        repository.adoptTokenAnswer = { Outcome.Failure(AppError.Unauthorized) }
        val viewModel = AuthViewModel(repository)
        advanceUntilIdle()
        viewModel.onTokenChange("1234567890123456")

        viewModel.submitToken()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSignedIn)
        assertFalse(state.isBusy)
        assertEquals(AppError.Unauthorized.toChinese(), state.errorMessage)
        assertEquals("the user has to be able to fix a typo", "1234567890123456", state.tokenInput)
    }

    @Test
    fun `a submission in flight cannot be submitted twice`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.adoptTokenGate = gate
        val viewModel = AuthViewModel(repository)
        advanceUntilIdle()
        viewModel.onTokenChange("1234567890123456")

        viewModel.submitToken()
        assertTrue(viewModel.uiState.value.isBusy)
        assertFalse("a busy form is not a submittable one", viewModel.uiState.value.canSubmitToken)

        viewModel.submitToken()
        assertEquals(1, repository.adoptTokenCalls.size)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isBusy)
        assertTrue(viewModel.uiState.value.isSignedIn)
    }

    @Test
    fun `dismissError clears the banner`() = runTest {
        repository.adoptTokenAnswer = { Outcome.Failure(AppError.Timeout) }
        val viewModel = AuthViewModel(repository)
        advanceUntilIdle()
        viewModel.onTokenChange("1234567890123456")
        viewModel.submitToken()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)

        viewModel.dismissError()

        assertNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isSignedIn)
    }

    // ------------------------------------------------------------- sms flow

    @Test
    fun `a refused captcha is replaced, a transport failure is not`() = runTest {
        val viewModel = AuthViewModel(repository)
        advanceUntilIdle()
        viewModel.onPhoneChange("13800138000")
        viewModel.onCaptchaTextChange("AB12")

        // The gateway rejected the request, which for this endpoint means the
        // captcha was wrong or already used. The picture is single-use, so the
        // old one must not be offered again.
        repository.smsAnswer = { Outcome.Failure(AppError.Server(500, null)) }
        viewModel.sendSmsCode()
        advanceUntilIdle()

        assertEquals(2, repository.loadCaptchaCalls)
        assertEquals("KEY2", viewModel.uiState.value.captcha?.key)
        // The refusal must survive the refresh that it caused. `refreshCaptcha`
        // used to clear `errorMessage` unconditionally, so this sentence was
        // wiped before anyone could read it and the user got a fresh picture
        // with no reason why the old one was rejected. That was a real defect;
        // the refresh now takes the reason as an argument.
        assertEquals("服务器拒绝了（错误码 500）", viewModel.uiState.value.errorMessage)

        // Same flow, but the request never reached the server: refreshing the
        // captcha would throw away a perfectly good one and cost another fetch.
        viewModel.onCaptchaTextChange("AB12")
        repository.smsAnswer = { Outcome.Failure(AppError.Offline) }
        viewModel.sendSmsCode()
        advanceUntilIdle()

        assertEquals(2, repository.loadCaptchaCalls)
        assertEquals(AppError.Offline.toChinese(), viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `sendSmsCode refuses to send without a captcha code`() = runTest {
        val viewModel = AuthViewModel(repository)
        advanceUntilIdle()
        viewModel.onPhoneChange("13800138000")

        viewModel.sendSmsCode()
        advanceUntilIdle()

        // Each call costs the account a real SMS, so an unreadable request must
        // not reach the gateway at all.
        assertTrue(repository.smsRequests.isEmpty())
        assertFalse(viewModel.uiState.value.isBusy)
        assertFalse(viewModel.uiState.value.isSmsSent)
    }

    @Test
    fun `the sms flow signs in once the code has been sent`() = runTest {
        val viewModel = AuthViewModel(repository)
        advanceUntilIdle()
        viewModel.onPhoneChange("13800138000")
        viewModel.onCaptchaTextChange("AB12")
        viewModel.sendSmsCode()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSmsSent)
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals(
            listOf(Triple("13800138000", "AB12", "KEY1")),
            repository.smsRequests,
        )
        assertFalse("no code typed yet", viewModel.uiState.value.canSubmitSms)

        viewModel.onSmsCodeChange("12ab34")
        assertEquals("1234", viewModel.uiState.value.smsCodeInput)
        assertTrue(viewModel.uiState.value.canSubmitSms)

        viewModel.submitSmsCode()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isSignedIn)
        assertEquals("", state.smsCodeInput)
        assertEquals(listOf("13800138000" to "1234"), repository.loginRequests)
    }

    // -------------------------------------------------------------- resending

    @Test
    fun `the cooldown starts at thirty and refuses a resend while it runs`() = runTest {
        val viewModel = AuthViewModel(repository)
        advanceUntilIdle()
        viewModel.onPhoneChange("13800138000")
        viewModel.onCaptchaTextChange("AB12")
        viewModel.sendSmsCode()
        scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.isSmsSent)
        assertEquals(RESEND_COOLDOWN_SECONDS, state.resendSeconds)
        assertFalse("the first code is still fresh", state.canResendSms)

        // A user who did not get the first SMS will tap anyway. It has to do
        // nothing, because every send costs the account a real message.
        viewModel.resendSmsCode()
        advanceUntilIdle()
        assertEquals(1, repository.smsRequests.size)
    }

    @Test
    fun `the cooldown runs out after thirty seconds and offers a fresh captcha`() = runTest {
        val viewModel = AuthViewModel(repository)
        advanceUntilIdle()
        viewModel.onPhoneChange("13800138000")
        viewModel.onCaptchaTextChange("AB12")
        viewModel.sendSmsCode()
        scheduler.runCurrent()

        // One tick short: still counting, and still refusing.
        advanceTimeBy(RESEND_COOLDOWN_SECONDS * 1_000L - 1)
        assertEquals(1, viewModel.uiState.value.resendSeconds)
        assertFalse(viewModel.uiState.value.canResendSms)

        advanceUntilIdle()

        val ready = viewModel.uiState.value
        assertEquals(0, ready.resendSeconds)
        // The picture that was on screen has already been spent by the send that
        // succeeded, so a resend against it would be rejected for a reason the
        // user cannot see. Fetching a new one is the whole point of the refresh.
        assertEquals("KEY2", ready.captcha?.key)
        assertEquals("", ready.captchaText)
        // Not yet: a resend still needs the code for that new picture.
        assertFalse(ready.canResendSms)
        // And the row has to say *that*. This is the bug that came back from a
        // phone: the wait was over, but the row drew the same countdown it draws
        // while waiting, so it read "0 秒后可重新发送" and no button ever appeared.
        assertEquals(ResendState.NEEDS_INPUT, ready.resendState)

        viewModel.onCaptchaTextChange("CD34")
        assertTrue(viewModel.uiState.value.canResendSms)
        assertEquals(ResendState.READY, viewModel.uiState.value.resendState)
        viewModel.resendSmsCode()
        advanceUntilIdle()

        assertEquals(
            listOf(Triple("13800138000", "AB12", "KEY1"), Triple("13800138000", "CD34", "KEY2")),
            repository.smsRequests,
        )
    }
    // ------------------------------------------------------------------ fakes

    private class FakeAuthRepository : AuthRepository {

        var loadCaptchaAnswer: () -> Outcome<CaptchaChallenge> = {
            Outcome.Success(CaptchaChallenge(key = "KEY$loadCaptchaCalls", jpegBytes = JPEG))
        }
        var adoptTokenAnswer: (String) -> Outcome<Account> = { Outcome.Success(Account(id = "u1")) }
        var smsAnswer: () -> Outcome<Unit> = { Outcome.Success(Unit) }
        var loginAnswer: () -> Outcome<Account> = { Outcome.Success(Account(id = "u1")) }

        /** Freezes [adoptToken] so the busy state can be observed. */
        var adoptTokenGate: CompletableDeferred<Unit>? = null

        var loadCaptchaCalls = 0
            private set
        val adoptTokenCalls = mutableListOf<String>()
        val smsRequests = mutableListOf<Triple<String, String, String>>()
        val loginRequests = mutableListOf<Pair<String, String>>()

        override suspend fun loadCaptcha(): Outcome<CaptchaChallenge> {
            loadCaptchaCalls++
            return loadCaptchaAnswer()
        }

        override suspend fun sendSmsCode(
            phone: String,
            captchaText: String,
            captchaKey: String,
        ): Outcome<Unit> {
            smsRequests += Triple(phone, captchaText, captchaKey)
            return smsAnswer()
        }

        override suspend fun login(phone: String, smsCode: String): Outcome<Account> {
            loginRequests += phone to smsCode
            return loginAnswer()
        }

        override suspend fun adoptToken(token: String): Outcome<Account> {
            adoptTokenCalls += token
            adoptTokenGate?.await()
            return adoptTokenAnswer(token)
        }

        override fun logout() = Unit
    }

    private companion object {
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    }
}
