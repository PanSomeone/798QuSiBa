package com.killhuishenghuo.data.repository

import com.killhuishenghuo.core.net.GatewayClient
import com.killhuishenghuo.data.local.FakeSessionStore
import com.killhuishenghuo.data.model.Account
import com.killhuishenghuo.data.model.ApiEnvelope
import com.killhuishenghuo.data.model.AppError
import com.killhuishenghuo.data.model.DeviceStatusData
import com.killhuishenghuo.data.model.LoginData
import com.killhuishenghuo.data.model.LoginToken
import com.killhuishenghuo.data.model.MasterData
import com.killhuishenghuo.data.model.Outcome
import com.killhuishenghuo.data.model.QrUseData
import com.killhuishenghuo.data.remote.LoginRequest
import com.killhuishenghuo.data.remote.SmsCodeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Sign-in, against a hand-written gateway and the in-memory session store.
 *
 * The token is the whole point of this app's login: it is the only credential
 * that opens a valve, so every test here is really about *when the store is
 * allowed to hold one*.
 */
class AuthRepositoryImplTest {

    private val session = FakeSessionStore(clientId = "CID-1")
    private val gateway = FakeAuthGateway()

    private val repository: AuthRepository = AuthRepositoryImpl(
        gateway = gateway,
        session = session,
        ioDispatcher = Dispatchers.Unconfined,
        // Deterministic: the captcha key is invented by the client, so a test
        // that let it be random could not tell which key was sent back.
        captchaKeyFactory = { CAPTCHA_KEY },
        clock = { FIXED_NOW },
    )

    // --------------------------------------------------------------- captcha

    @Test
    fun `loadCaptcha returns the key it invented with the bytes it fetched`() = runBlocking {
        gateway.captchaAnswer = { Response.success(JPEG.toResponseBody("image/jpeg".toMediaType())) }

        val challenge = repository.loadCaptcha().getOrNull()

        // The pair travels together: the server matches the typed code against
        // the key it was fetched under, so a key that drifts is an unusable code.
        assertEquals(CaptchaChallenge(key = CAPTCHA_KEY, jpegBytes = JPEG), challenge)
        assertEquals(listOf(CAPTCHA_KEY), gateway.captchaKeys)
    }

    @Test
    fun `loadCaptcha reports a non-2xx as a server failure`() = runBlocking {
        gateway.captchaAnswer = { httpError(500) }

        val outcome = repository.loadCaptcha()

        assertEquals(AppError.Server(code = 500, message = null), outcome.errorOrNull())
        assertEquals("服务器拒绝了（错误码 500）", outcome.errorOrNull()?.toChinese())
    }

    @Test
    fun `loadCaptcha refuses an empty image`() = runBlocking {
        gateway.captchaAnswer = { Response.success(ByteArray(0).toResponseBody("image/jpeg".toMediaType())) }

        val outcome = repository.loadCaptcha()

        // A zero-byte captcha renders as a broken image the user cannot read a
        // code off, so it is a failure rather than a challenge with no picture.
        assertEquals(AppError.Unexpected("验证码图片为空"), outcome.errorOrNull())
    }

    // ------------------------------------------------------------ adoptToken

    @Test
    fun `adoptToken trims the token before it is persisted or verified`() = runBlocking {
        var tokenWhenVerified: String? = null
        gateway.masterAnswer = {
            tokenWhenVerified = session.token
            envelope(MasterData(account = Account(id = "u1", name = "李四")))
        }

        val outcome = repository.adoptToken("  tok  ")

        // Pasted tokens arrive with a trailing newline from the clipboard; the
        // gateway compares the header byte for byte, so the store must hold the
        // trimmed value and the verification must run against that same value.
        assertEquals("tok", tokenWhenVerified)
        assertEquals("tok", session.token)
        assertEquals("u1", session.uid)
        assertEquals("李四", session.accountName)
        assertEquals(Account(id = "u1", name = "李四"), outcome.getOrNull())
    }

    @Test
    fun `adoptToken accepts an identified account that has no name`() = runBlocking {
        gateway.masterAnswer = { envelope(MasterData(account = Account(id = "u1"))) }

        val outcome = repository.adoptToken("tok")

        // An id is the signal that the token is real; the display name is
        // optional and its absence must not log the user back out.
        assertEquals("u1", outcome.getOrNull()?.id)
        assertEquals("tok", session.token)
        assertEquals("u1", session.uid)
        assertNull(session.accountName)
    }

    @Test
    fun `adoptToken labels the account with the phone number when it has no name`() = runBlocking {
        gateway.masterAnswer = { envelope(MasterData(account = Account(id = "u1", name = "   ", phone = "13800000000"))) }

        repository.adoptToken("tok")

        // The header must never render blank, so a nameless account falls back
        // to the phone number it was bound with.
        assertEquals("13800000000", session.accountName)
    }

    @Test
    fun `adoptToken refuses a token the server does not recognise`() = runBlocking {
        // Verbatim body the live gateway answered for a garbage token:
        //   <-- 200  content-length: 49
        //   {"code":0,"data":{"ads":[]}}
        // HTTP 200 with `code: 0` and *no account*, which is why "the request
        // succeeded" is not evidence that a token is valid. Reading success off
        // the status code signed the user in with a typo and then showed a
        // permanently empty device list — indistinguishable from "you have no
        // devices bound". This is the regression guard for that bug.
        gateway.masterAnswer = { envelope(MasterData()) }

        val outcome = repository.adoptToken("abcdefgh12345678")

        val detail = (outcome.errorOrNull() as AppError.Unexpected).detail.orEmpty()
        assertTrue("should name the header the user has to copy, was: $detail", detail.contains("Authorization"))
        assertTrue("should mention the token, was: $detail", detail.contains("token"))
        assertNull(session.token)
        assertNull(session.uid)
        assertNull(session.accountName)
    }

    @Test
    fun `adoptToken rolls the session back when master rejects the token`() = runBlocking {
        gateway.masterAnswer = { envelope<MasterData>(code = -99, msg = "登录已过期") }

        val outcome = repository.adoptToken("stale-token")

        assertEquals(AppError.Unauthorized, outcome.errorOrNull())
        // A token that could not be verified must not linger: the next launch
        // would look signed in and fail on every screen instead of the login one.
        assertNull(session.token)
        assertNull(session.uid)
        assertNull(session.accountName)
    }

    @Test
    fun `adoptToken refuses a blank token without asking the server`() = runBlocking {
        for (value in listOf("", "   ", "\n\t")) {
            val outcome = repository.adoptToken(value)

            assertEquals(AppError.Unexpected("token 不能为空"), outcome.errorOrNull())
        }

        // Nothing was sent: an empty Authorization header is a *different*
        // request from an absent one, and the gateway answers -99 for it.
        assertEquals(0, gateway.masterCalls)
        assertNull(session.token)
    }

    // ----------------------------------------------------------------- login

    @Test
    fun `login persists the token the gateway issued`() = runBlocking {
        gateway.loginAnswer = { envelope(LoginData(al = LoginToken(token = "T", uid = "U"))) }
        gateway.masterAnswer = { envelope(MasterData(account = Account(id = "U", name = "王五"))) }

        val outcome = repository.login(phone = "13800138000", smsCode = "1234")

        assertEquals(Outcome.Success(Account(id = "U", name = "王五")), outcome)
        assertEquals("T", session.token)
        assertEquals("U", session.uid)

        val request = gateway.loginRequests.single()
        assertEquals("13800138000", request.phone)
        assertEquals("1234", request.smsCode)
        assertEquals("CID-1", request.clientId)
        // `openCode` is a declared key of the captured client's body and must be
        // sent as an empty string rather than dropped.
        assertEquals("", request.openCode)
        // A freshly issued token is verified before it is trusted.
        assertEquals(1, gateway.masterCalls)
    }

    @Test
    fun `login fails when the server returns no token`() = runBlocking {
        gateway.masterAnswer = { envelope(MasterData(account = Account(id = "u1"))) }

        gateway.loginAnswer = { envelope(LoginData(al = null)) }
        val missing = repository.login("13800138000", "1234")

        gateway.loginAnswer = { envelope(LoginData(al = LoginToken(token = "   ", uid = "U"))) }
        val blank = repository.login("13800138000", "1234")

        for (outcome in listOf(missing, blank)) {
            val error = outcome.errorOrNull() as AppError.Unexpected
            assertTrue("should mention a token, was: ${error.detail}", error.detail.orEmpty().contains("token"))
        }
        // Not signed in, and never verified against the account endpoint.
        assertNull(session.token)
        assertEquals(0, gateway.masterCalls)
    }

    @Test
    fun `login surfaces a gateway failure without touching the session`() = runBlocking {
        gateway.loginAnswer = { envelope<LoginData>(code = -99, msg = "登录已过期") }

        val outcome = repository.login("13800138000", "1234")

        assertEquals(AppError.Unauthorized, outcome.errorOrNull())
        assertNull(session.token)
        assertEquals(0, gateway.masterCalls)
    }

    // -------------------------------------------------------------- sms code

    @Test
    fun `sendSmsCode forwards the phone the code and the captcha key`() = runBlocking {
        gateway.smsAnswer = { envelope<JsonElement>(code = 0) }

        val outcome = repository.sendSmsCode(
            phone = "13800138000",
            captchaText = "AB12",
            captchaKey = CAPTCHA_KEY,
        )

        assertEquals(Outcome.Success(Unit), outcome)
        assertEquals(
            SmsCodeRequest(phone = "13800138000", captchaText = "AB12", captchaKey = CAPTCHA_KEY),
            gateway.smsRequests.single(),
        )
    }

    // ------------------------------------------------------------------ exit

    @Test
    fun `logout clears the persisted identity`() {
        session.token = "T"
        session.uid = "U"
        session.accountName = "王五"

        repository.logout()

        assertNull(session.token)
        assertNull(session.uid)
        assertNull(session.accountName)
    }

    // ------------------------------------------------------------------ fakes

    /**
     * Only the four calls sign-in uses are wired; the device calls fail loudly
     * rather than answering an empty success that a test could mistake for
     * evidence that this repository stayed on the auth path.
     */
    private class FakeAuthGateway : GatewayClient {

        var captchaAnswer: () -> Response<ResponseBody> = { httpError(404) }
        var masterAnswer: () -> Response<ApiEnvelope<MasterData>> = { envelope(MasterData()) }
        var loginAnswer: () -> Response<ApiEnvelope<LoginData>> = { envelope(LoginData()) }
        var smsAnswer: () -> Response<ApiEnvelope<JsonElement>> = { envelope<JsonElement>() }

        val captchaKeys = mutableListOf<String>()
        val loginRequests = mutableListOf<LoginRequest>()
        val smsRequests = mutableListOf<SmsCodeRequest>()
        var masterCalls = 0

        override suspend fun captcha(key: String): Response<ResponseBody> {
            captchaKeys += key
            return captchaAnswer()
        }

        override suspend fun sendSmsCode(req: SmsCodeRequest): Response<ApiEnvelope<JsonElement>> {
            smsRequests += req
            return smsAnswer()
        }

        override suspend fun login(req: LoginRequest): Response<ApiEnvelope<LoginData>> {
            loginRequests += req
            return loginAnswer()
        }

        override suspend fun master(): Response<ApiEnvelope<MasterData>> {
            masterCalls++
            return masterAnswer()
        }

        override suspend fun status(did: String): Response<ApiEnvelope<DeviceStatusData>> =
            throw NotImplementedError("sign-in does not read device state")

        override suspend fun start(did: String, ptype: Int, args: String): Response<ApiEnvelope<JsonElement>> =
            throw NotImplementedError("sign-in does not open valves")

        override suspend fun stop(did: String): Response<ApiEnvelope<JsonElement>> =
            throw NotImplementedError("sign-in does not close valves")

        override suspend fun qrUse(id: String): Response<ApiEnvelope<QrUseData>> =
            throw NotImplementedError("sign-in does not resolve scanned codes")

        override suspend fun favourite(did: String, remove: Boolean): Response<ApiEnvelope<JsonElement>> =
            throw NotImplementedError("sign-in does not bind devices")
    }

    private companion object {
        const val CAPTCHA_KEY = "KEY1234567"
        const val FIXED_NOW = 1792081702558L

        /** Not a real image: only the bytes surviving the trip are asserted. */
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte())

        fun <T : Any> envelope(data: T? = null, code: Int = 0, msg: String? = null): Response<ApiEnvelope<T>> =
            Response.success(ApiEnvelope(code = code, msg = msg, data = data))

        fun <T : Any> httpError(code: Int): Response<T> =
            Response.error(code, """{"msg":"nope"}""".toResponseBody("application/json".toMediaType()))
    }
}
