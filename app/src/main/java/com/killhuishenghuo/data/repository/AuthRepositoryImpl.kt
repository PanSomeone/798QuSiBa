package com.killhuishenghuo.data.repository

import com.killhuishenghuo.core.net.ApiConstants
import com.killhuishenghuo.core.net.GatewayClient
import com.killhuishenghuo.data.local.SessionStore
import com.killhuishenghuo.data.model.Account
import com.killhuishenghuo.data.model.AppError
import com.killhuishenghuo.data.model.Outcome
import com.killhuishenghuo.data.remote.ApiResultMapper
import com.killhuishenghuo.data.remote.LoginRequest
import com.killhuishenghuo.data.remote.SmsCodeRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Gateway-backed sign-in.
 *
 * The token is written to [SessionStore] in exactly one place — [adoptToken] —
 * so there is a single definition of "signed in" rather than one per entry
 * point.
 */
class AuthRepositoryImpl(
    private val gateway: GatewayClient,
    private val session: SessionStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val captchaKeyFactory: () -> String = ::randomCaptchaKey,
    private val clock: () -> Long = System::currentTimeMillis,
) : AuthRepository {

    override suspend fun loadCaptcha(): Outcome<CaptchaChallenge> = withContext(ioDispatcher) {
        val key = captchaKeyFactory()

        runCatching { ApiResultMapper.mapBytes(gateway.captcha(key)) }
            .getOrElse(ApiResultMapper::mapThrown)
            .let { outcome ->
                when (outcome) {
                    is Outcome.Success -> Outcome.Success(
                        CaptchaChallenge(key = key, jpegBytes = outcome.value),
                    )

                    is Outcome.Failure -> outcome
                }
            }
    }

    override suspend fun sendSmsCode(
        phone: String,
        captchaText: String,
        captchaKey: String,
    ): Outcome<Unit> = withContext(ioDispatcher) {
        val body = SmsCodeRequest(
            phone = phone,
            captchaText = captchaText,
            captchaKey = captchaKey,
        )

        runCatching { ApiResultMapper.mapAction(gateway.sendSmsCode(body)) }
            .getOrElse(ApiResultMapper::mapThrown)
    }

    override suspend fun login(phone: String, smsCode: String): Outcome<Account> =
        withContext(ioDispatcher) {
            val body = LoginRequest(
                smsCode = smsCode,
                phone = phone,
                clientId = session.clientId,
            )

            val token = runCatching { ApiResultMapper.map(gateway.login(body)) }
                .getOrElse(ApiResultMapper::mapThrown)
                .let { outcome ->
                    when (outcome) {
                        is Outcome.Success -> outcome.value.al?.token.orEmpty()
                        is Outcome.Failure -> return@withContext outcome
                    }
                }

            if (token.isBlank()) {
                return@withContext Outcome.Failure(
                    AppError.Unexpected("登录成功但服务器没有返回 token"),
                )
            }

            adoptToken(token)
        }

    override suspend fun adoptToken(token: String): Outcome<Account> = withContext(ioDispatcher) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            return@withContext Outcome.Failure(AppError.Unexpected("token 不能为空"))
        }

        // Verify before persisting. `ui/app/master` answers HTTP 200 with
        // `{"code":0,"data":{"ads":[]}}` for *any* token the gateway does not
        // recognise — including no token at all (verified against the live host).
        // So the request succeeding proves nothing; the only usable signal is
        // whether the response actually carries an identified account. Reading
        // success off the status code would sign the user in with a typo and then
        // show them a permanently empty device list, which looks like "you have no
        // devices" rather than "that token was wrong".
        session.token = trimmed

        runCatching { ApiResultMapper.map(gateway.master()) }
            .getOrElse(ApiResultMapper::mapThrown)
            .let { outcome ->
                when (outcome) {
                    is Outcome.Success -> {
                        val account = outcome.value.account
                        val accountId = account?.id?.takeIf { it.isNotBlank() }

                        if (accountId == null) {
                            session.token = null
                            session.uid = null
                            session.accountName = null
                            Outcome.Failure(
                                AppError.Unexpected(
                                    "服务器不认识这个 token。粘贴的应当是请求头 Authorization " +
                                        "的完整值，且需要是当前仍然有效的登录状态。",
                                ),
                            )
                        } else {
                            session.uid = accountId
                            session.accountName = account.let(::displayNameOf)
                            Outcome.Success(account)
                        }
                    }

                    is Outcome.Failure -> {
                        // Put the store back the way it was: a rejected token
                        // must not linger and make the next launch look signed
                        // in. Note the `ui/app/master` failure is reported as-is
                        // even when it is *not* -99: a token that could not be
                        // verified is not a token this client should claim to
                        // hold.
                        session.token = null
                        session.uid = null
                        session.accountName = null
                        outcome
                    }
                }
            }
    }

    override fun logout() {
        session.token = null
        session.uid = null
        session.accountName = null
    }

    private companion object {
        private const val CAPTCHA_KEY_LENGTH = 10
        private const val ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        /** The `s` query parameter: 10 characters the client invents itself. */
        fun randomCaptchaKey(): String = buildString(CAPTCHA_KEY_LENGTH) {
            repeat(CAPTCHA_KEY_LENGTH) {
                append(ALPHANUMERIC[Random.nextInt(ALPHANUMERIC.length)])
            }
        }

        /**
         * The gateway omits `name` for some accounts, so the phone number is the
         * fallback label. Never blank: an empty header looks like a bug.
         */
        fun displayNameOf(account: Account): String? =
            account.name?.takeIf { it.isNotBlank() }
                ?: account.phone?.takeIf { it.isNotBlank() }
    }
}

/** `ApiConstants` is used by callers of this repository; re-exported for them. */
internal val DEFAULT_API_BASE_URL = ApiConstants.DEFAULT_BASE_URL
