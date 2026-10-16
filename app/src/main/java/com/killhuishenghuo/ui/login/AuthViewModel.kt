package com.killhuishenghuo.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.killhuishenghuo.data.model.Account
import com.killhuishenghuo.core.log.AppLog
import com.killhuishenghuo.data.model.AppError
import com.killhuishenghuo.data.model.Outcome
import com.killhuishenghuo.data.repository.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives sign-in, from inside the settings screen.
 *
 * The two modes are kept in one state object on purpose: switching tabs must not
 * silently discard a captcha that is still valid on the server, and a failure in
 * one mode should be readable while looking at the other.
 *
 * [initialAccountName] seeds the row's identity. It is passed in rather than
 * read from the store on every recomposition so that the one source of truth for
 * "who is signed in right now" is this state object — a screen that asked the
 * store directly would keep showing the old name after a sign-out for as long as
 * nothing else triggered a recomposition.
 */
class AuthViewModel(
    private val repository: AuthRepository,
    initialAccountName: String = "",
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState(accountName = initialAccountName))
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    /**
     * Held so a second send cancels the first one's countdown.
     *
     * Two live countdowns would each decrement the same number, so the second
     * code's 30 seconds would elapse in 15.
     */
    private var countdownJob: Job? = null

    init {
        // A captcha is only needed by the SMS path, but fetching it up front
        // means switching to that tab shows a picture instead of a spinner.
        // Skip it when already signed in: the common case is opening settings to
        // read something, and that should not cost a request to the gateway.
        if (initialAccountName.isEmpty()) refreshCaptcha()
    }

    fun selectMode(mode: LoginMode) {
        _uiState.update { it.copy(mode = mode, errorMessage = null) }
    }

    fun onTokenChange(value: String) {
        _uiState.update { it.copy(tokenInput = value, errorMessage = null) }
    }

    fun onPhoneChange(value: String) {
        _uiState.update {
            it.copy(
                phoneInput = value.filter(Char::isDigit).take(AuthUiState.PHONE_LENGTH),
                errorMessage = null,
            )
        }
    }

    fun onCaptchaTextChange(value: String) {
        _uiState.update { it.copy(captchaText = value.trim(), errorMessage = null) }
    }

    fun onSmsCodeChange(value: String) {
        _uiState.update { it.copy(smsCodeInput = value.filter(Char::isDigit), errorMessage = null) }
    }

    /**
     * Replaces the picture *and* its key; the old code is no longer valid.
     *
     * [reason] is for the case where a refresh is a *consequence* of something
     * that already went wrong — a rejected captcha, say. Clearing the message
     * there would swap the picture and tell the user nothing, leaving them to
     * guess why the screen changed. Passing the sentence in means the refresh
     * reports what caused it and still clears any stale message when it is a
     * plain user-initiated refresh.
     */
    fun refreshCaptcha(reason: String? = null, clearText: Boolean = true) {
        if (_uiState.value.isLoadingCaptcha) return

        _uiState.update { it.copy(isLoadingCaptcha = true, errorMessage = reason) }

        viewModelScope.launch {
            when (val result = repository.loadCaptcha()) {
                is Outcome.Success -> _uiState.update {
                    it.copy(
                        isLoadingCaptcha = false,
                        captcha = result.value,
                        captchaText = if (clearText) "" else it.captchaText,
                    )
                }

                is Outcome.Failure -> _uiState.update {
                    it.copy(isLoadingCaptcha = false, errorMessage = result.error.toChinese())
                }
            }
        }
    }

    /** Uses a token the official app already issued. */
    fun submitToken() {
        val state = _uiState.value
        if (!state.canSubmitToken) return

        _uiState.update { it.copy(isBusy = true, errorMessage = null) }

        viewModelScope.launch {
            when (val result = repository.adoptToken(state.tokenInput)) {
                is Outcome.Success -> {
                    AppLog.log("Auth", "token login OK account=${result.value.accountName()}")
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            tokenInput = "",
                            accountName = result.value.accountName(),
                        )
                    }
                }

                is Outcome.Failure -> {
                    AppLog.log("Auth", "token login FAILED error=${result.error}")
                    _uiState.update {
                        it.copy(isBusy = false, errorMessage = result.error.toChinese())
                    }
                }
            }
        }
    }

    /** Asks the gateway to send the SMS. */
    fun sendSmsCode() {
        val state = _uiState.value
        val captcha = state.captcha
        if (!state.canSendSms || captcha == null) return

        _uiState.update { it.copy(isBusy = true, errorMessage = null) }

        viewModelScope.launch {
            val result = repository.sendSmsCode(
                phone = state.phoneInput,
                captchaText = state.captchaText,
                captchaKey = captcha.key,
            )

            when (result) {
                is Outcome.Success -> {
                    AppLog.log("Auth", "sms code sent (cooldown ${RESEND_COOLDOWN_SECONDS}s)")
                    _uiState.update { it.copy(isBusy = false, isSmsSent = true) }
                    startResendCooldown()
                }

                is Outcome.Failure -> {
                    val message = result.error.toChinese()
                    AppLog.log("Auth", "sms code FAILED error=${result.error} rendered=$message")
                    _uiState.update { it.copy(isBusy = false, errorMessage = message) }
                    // A rejected captcha is single-use: reusing the picture
                    // would send the same wrong code again. This has to be the
                    // *last* thing that touches the state, and it has to be told
                    // the reason — it clears `errorMessage` when left to itself,
                    // which erased this very sentence in an earlier revision.
                    if (result.error is AppError.Server) refreshCaptcha(reason = message)
                }
            }
        }
    }

    /**
     * Sends a second code, once the first one's cooldown has run out.
     *
     * Separate from [sendSmsCode] only in that it also resets the countdown, and
     * in that it is reachable at all: once a code has been sent the main button
     * turns into "登录", so without this the only way to get another code would
     * be to sign out and start over.
     */
    fun resendSmsCode() {
        if (_uiState.value.canResendSms) sendSmsCode()
    }

    /**
     * Refreshes the captcha behind a spent one.
     *
     * Called when the cooldown expires, not when the code was sent: the picture
     * that was on screen has already been spent by the send that succeeded, so
     * a resend would be rejected for a reason the user cannot see. Clearing the
     * text is right here — the digits on screen belong to the old picture.
     */
    private fun startResendCooldown() {
        countdownJob?.cancel()
        _uiState.update { it.copy(resendSeconds = RESEND_COOLDOWN_SECONDS) }

        countdownJob = viewModelScope.launch {
            repeat(RESEND_COOLDOWN_SECONDS) {
                delay(1_000)
                _uiState.update {
                    it.copy(resendSeconds = (it.resendSeconds - 1).coerceAtLeast(0))
                }
            }
            refreshCaptcha()
        }
    }

    /** Completes the SMS flow. */
    fun submitSmsCode() {
        val state = _uiState.value
        if (!state.canSubmitSms) return

        _uiState.update { it.copy(isBusy = true, errorMessage = null) }

        viewModelScope.launch {
            when (val result = repository.login(state.phoneInput, state.smsCodeInput)) {
                is Outcome.Success -> {
                    AppLog.log("Auth", "sms login OK account=${result.value.accountName()}")
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            smsCodeInput = "",
                            accountName = result.value.accountName(),
                        )
                    }
                }

                is Outcome.Failure -> {
                    AppLog.log("Auth", "sms login FAILED error=${result.error}")
                    _uiState.update {
                        it.copy(isBusy = false, errorMessage = result.error.toChinese())
                    }
                }
            }
        }
    }

    /**
     * Forgets the session.
     *
     * The mode goes back to [LoginMode.SMS], which is the default, so signing
     * out leaves the form in the shape a fresh install would have. The captcha
     * is dropped with it and re-fetched, because the one that was on screen was
     * matched to a request that no longer matters and may already have been
     * spent.
     */
    fun signOut() {
        repository.logout()
        _uiState.update {
            it.copy(
                accountName = "",
                mode = LoginMode.SMS,
                errorMessage = null,
                isSmsSent = false,
                smsCodeInput = "",
                captchaText = "",
                captcha = null,
                // Cleared so the refresh below cannot be swallowed by the
                // in-flight guard: the picture being dropped is precisely the
                // one that request was going to install.
                isLoadingCaptcha = false,
            )
        }
        refreshCaptcha()
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

/**
 * The best name this account has.
 *
 * Falls back to the phone number, then to a non-empty marker: the settings row
 * keys off emptiness to decide between "sign in" and "sign out", so returning
 * `""` for an account that genuinely has neither field would render the row as
 * signed out while a valid token sits in the session store.
 */
private fun Account.accountName(): String =
    name?.takeIf { it.isNotBlank() }
        ?: phone?.takeIf { it.isNotBlank() }
        ?: "已登录"

