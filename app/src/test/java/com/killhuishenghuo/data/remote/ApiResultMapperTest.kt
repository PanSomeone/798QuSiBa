package com.killhuishenghuo.data.remote

import com.killhuishenghuo.data.model.ApiEnvelope
import com.killhuishenghuo.data.model.AppError
import com.killhuishenghuo.data.model.LoginData
import com.killhuishenghuo.data.model.Outcome
import com.killhuishenghuo.core.net.NetworkModule
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Covers the four failure layers of [ApiResultMapper]: HTTP status, business
 * code, a missing payload, and a parse failure.
 *
 * Every response is constructed directly rather than fetched from a mock
 * server — the mapper takes a [Response] and nothing else, so a live socket
 * would add flakiness and prove nothing about the logic under test.
 *
 * Envelopes are decoded with [NetworkModule.json], so these tests also fail if
 * the production parser stops tolerating the extra keys the gateway sends.
 */
class ApiResultMapperTest {

    private companion object {
        val json: Json = NetworkModule.json
        val JSON_MEDIA: okhttp3.MediaType = "application/json".toMediaType()

        fun envelope(raw: String): ApiEnvelope<LoginData> =
            json.decodeFromString<ApiEnvelope<LoginData>>(raw)

        /**
         * Carries the narrowed payload the way Retrofit would. `ApiEnvelope` is
         * covariant in `T`, and `Response<out ApiEnvelope<T>>` accepts this, so
         * the mapper sees the same shape it sees in production.
         */
        fun parsed(raw: String): Response<ApiEnvelope<LoginData>> =
            Response.success(envelope(raw))

        /** The `dev/start` shape: success reported by the code, with no payload. */
        fun startBody(raw: String): Response<ApiEnvelope<LoginData>> =
            Response.success(envelope(raw))

        /**
         * A non-2xx answer. The payload type has to match the call site, and
         * `Response<T>` is invariant, so an HTTP-status case cannot borrow a
         * response built for another payload type even though the body is never
         * parsed on this path.
         */
        fun errorOf(code: Int, text: String?): Response<ApiEnvelope<LoginData>> =
            Response.error(code, text?.toResponseBody(JSON_MEDIA))

        fun jsonBody(code: Int, text: String): Response<ResponseBody> =
            Response.error(code, text.toResponseBody(JSON_MEDIA))

        fun mapEnvelope(response: Response<out ApiEnvelope<LoginData>>): Outcome<LoginData> =
            ApiResultMapper.map(response)

        fun mapAction(response: Response<out ApiEnvelope<*>>): Outcome<Unit> =
            ApiResultMapper.mapAction(response)

        fun loggedIn(outcome: Outcome<LoginData>): LoginData {
            assertTrue("expected a success, got $outcome", outcome is Outcome.Success)
            return (outcome as Outcome.Success).value
        }

        fun failure(outcome: Outcome<*>): AppError {
            assertTrue("expected a failure, got $outcome", outcome is Outcome.Failure)
            return (outcome as Outcome.Failure).error
        }
    }

    @Test
    fun `a successful login envelope yields the login data`() {
        val outcome = mapEnvelope(
            parsed("""{"code":0,"msg":"ok","data":{"al":{"token":"t","uid":"u"}}}"""),
        )

        val data = loggedIn(outcome)
        assertEquals("t", data.al?.token)
        assertEquals("u", data.al?.uid)
    }

    /**
     * The gateway returns `ads`, `ep`, `subs` and friends on almost every
     * response. A strict parser would reject the lot, so tolerance is the
     * contract, not a convenience.
     */
    @Test
    fun `unknown envelope fields are tolerated`() {
        val outcome = mapEnvelope(
            parsed(
                """{"code":0,"msg":"ok","time":1792081702558,"ads":[],"ep":{},"data":{"al":{"token":"t","uid":"u"},"acc":{"id":"1"}}}""",
            ),
        )

        assertEquals("t", loggedIn(outcome).al?.token)
    }

    /** `dev/start` answers success by code alone; a payload call must not. */
    @Test
    fun `code zero without data is a failure for a payload call`() {
        val outcome = mapEnvelope(startBody("""{"code":0,"msg":"ok"}"""))

        val error = failure(outcome)
        assertTrue("expected Unexpected, got $error", error is AppError.Unexpected)
        assertEquals("响应缺少 data 字段", (error as AppError.Unexpected).detail)
    }

    @Test
    fun `a business code becomes a server error carrying the gateway message`() {
        val outcome = mapEnvelope(parsed("""{"code":7,"msg":"余额不足"}"""))

        assertEquals(AppError.Server(7, "余额不足"), failure(outcome))
    }

    /** -99 is the one marker for a dead session, on every authenticated call. */
    @Test
    fun `code minus ninety nine is an expired session`() {
        assertEquals(AppError.Unauthorized, failure(mapEnvelope(startBody("""{"code":-99,"msg":"请登录"}"""))))
    }

    @Test
    fun `code minus ninety eight is the server rate limit`() {
        assertEquals(AppError.RateLimited, failure(mapEnvelope(startBody("""{"code":-98,"msg":"操作频繁"}"""))))
    }

    /** Some endpoints spell the failure key `message`; both must reach the UI. */
    @Test
    fun `the alternate message key is carried through`() {
        val outcome = mapEnvelope(parsed("""{"code":7,"message":"别催了"}"""))

        assertEquals(AppError.Server(7, "别催了"), failure(outcome))
    }

    /**
     * The whole reason [ApiResultMapper.mapAction] and [ApiResultMapper.mapStartAction] exist: `dev/start` reports a
     * successfully opened valve as `{"code":0}` with no `data` at all. Routing
     * that through [ApiResultMapper.map] would report the open as a failure.
     */
    @Test
    fun `mapAction accepts code zero without data`() {
        val outcome = ApiResultMapper.mapAction(startBody("""{"code":0}"""))

        assertTrue("expected a success, got $outcome", outcome is Outcome.Success)
        assertEquals(Unit, (outcome as Outcome.Success).value)
    }

    @Test
    fun `mapAction still surfaces an expired session`() {
        assertEquals(AppError.Unauthorized, failure(ApiResultMapper.mapAction(startBody("""{"code":-99}"""))))
    }

    @Test
    fun `mapAction rejects a non zero business code`() {
        assertEquals(
            AppError.Server(7, "余额不足"),
            failure(ApiResultMapper.mapAction(startBody("""{"code":7,"msg":"余额不足"}"""))),
        )
    }

    /**
     * Found by using the app against the real gateway, not by reading anything.
     *
     * Pressing the button on a valve the server already considers open comes back
     * as a business refusal whose message says so. The plain path rendered that as
     * "服务器拒绝了：设备正在运行" — a rejection prefix on a sentence that reports
     * the state the user asked for. The request did not fail; the screen's idea of
     * the device was stale.
     */
    @Test
    fun `mapStartAction treats an already running valve as success`() {
        val outcome = ApiResultMapper.mapStartAction(startBody("""{"code":1201,"msg":"设备正在运行"}"""))

        assertTrue("expected a success, got $outcome", outcome is Outcome.Success)
    }

    /**
     * The narrowness matters more than the match. A refusal that merely contains
     * 运行 is not evidence that water is flowing, and swallowing one as success
     * would turn a real failure into a screen that claims the valve is open.
     */
    @Test
    fun `mapStartAction does not swallow an unrelated refusal that mentions running`() {
        val outcome = ApiResultMapper.mapStartAction(startBody("""{"code":1202,"msg":"系统运行中，请稍后"}"""))

        assertTrue("expected a failure, got $outcome", outcome is Outcome.Failure)
    }

    @Test
    fun `mapStartAction does not swallow a known refusal`() {
        val outcome = ApiResultMapper.mapStartAction(startBody("""{"code":-52,"msg":"账户欠费"}"""))

        assertEquals(AppError.Refused(-52, "这个账号欠费了。"), failure(outcome))
    }

    /**
     * `设备运行中,请在设备上点击结算` — the real string this device answers with a
     * session still open, captured in the wire log on 2026-10-16.
     *
     * It arrives as code `-2`, which the refusal table knows about, so it reaches
     * the already-open check as an [AppError.Refused] rather than a raw server
     * error. For a start that means the valve is already open: the state asked
     * for holds.
     */
    @Test
    fun `mapStartAction treats the settle on device refusal as already open`() {
        val outcome = ApiResultMapper.mapStartAction(
            startBody("""{"code":-2,"msg":"设备运行中,请在设备上点击结算"}"""),
        )

        assertTrue("expected a success, got $outcome", outcome is Outcome.Success)
    }

    /**
     * The bug this split exists to prevent, and the second bug the first fix
     * introduced.
     *
     * Both refusals arrive as a non-zero code on `dev/end`, and they are not the
     * same thing:
     *
     *   -2 设备运行中,请在设备上点击结算 → this account has a session open. The
     *      valve is shut by the time this answers, so "stopped" is true; what the
     *      user still owes the machine is a settlement, and `LastAction` carries
     *      that up to the screen.
     *   -15 当前设备正在使用             → someone else is using it. The valve is
     *      open and this close did not shut it, so reporting success made the app
     *      claim to have closed something it had never opened.
     *
     * The first cut of this split reused one `isAlreadyOpen` predicate for both
     * directions and swallowed `-15` as well, which reintroduced exactly the bug
     * it was written to fix, one refusal code over.
     */
    @Test
    fun `a close tolerates the settle refusal but not the in use one`() {
        val settle = ApiResultMapper.mapCloseAction(
            startBody("""{"code":-2,"msg":"设备运行中,请在设备上点击结算"}"""),
        )
        val inUse = ApiResultMapper.mapCloseAction(
            startBody("""{"code":-15,"msg":"当前设备正在使用"}"""),
        )

        assertTrue("the valve is shut, only the session is open, got $settle", settle is Outcome.Success)
        assertTrue("this close did not shut anything, got $inUse", inUse is Outcome.Failure)
    }

    /**
     * The same input, both directions — which is the whole reason there are two
     * functions instead of one with a flag.
     */
    @Test
    fun `the settle refusal means success for a start and success for a close`() {
        val body = """{"code":-2,"msg":"设备运行中,请在设备上点击结算"}"""

        assertTrue(ApiResultMapper.mapStartAction(startBody(body)) is Outcome.Success)
        assertTrue(ApiResultMapper.mapCloseAction(startBody(body)) is Outcome.Success)
    }

    /**
     * `-15` is a failure in **both** directions, which is the shape the real device
     * produced and not what an earlier version of this test asserted.
     *
     * The logs from 2026-08-16 carry it twice, both times on a start, both times
     * surfaced to the user:
     *
     *     Refusal       code=-15 message=当前设备正在使用
     *     Water         open FAILED device=865137068739782 error=Server(code=-15, message=当前设备正在使用)
     *     Water         open FAILED device=865137068739782 error=Refused(code=-15, message=这台设备有人正在用，等它空下来再试。)
     *
     * So 当前设备正在使用 does **not** mean "this is your valve and it is already
     * open" — it means someone else holds it, and the word 运行 is not in the
     * message at all, which is why the message-based predicate never matched it.
     * Only `-2` is the app's own valve with a session left open.
     */
    @Test
    fun `the in use refusal is a failure in both directions`() {
        val body = """{"code":-15,"msg":"当前设备正在使用"}"""

        assertTrue(
            "an open on someone else's valve is not a success, got ${ApiResultMapper.mapStartAction(startBody(body))}",
            ApiResultMapper.mapStartAction(startBody(body)) is Outcome.Failure,
        )
        assertTrue(
            "and neither is a close that shut nothing, got ${ApiResultMapper.mapCloseAction(startBody(body))}",
            ApiResultMapper.mapCloseAction(startBody(body)) is Outcome.Failure,
        )
    }

    /**
     * 401 never carries a usable body, and the UI has one action for it
     * (re-login), so the status alone decides the error.
     */
    @Test
    fun `http 401 is an expired session`() {
        assertEquals(AppError.Unauthorized, failure(mapEnvelope(errorOf(401, """{"msg":"unauthorized"}"""))))
    }

    @Test
    fun `a failing http status carries the gateway message`() {
        assertEquals(AppError.Server(500, "boom"), failure(mapEnvelope(errorOf(500, """{"msg":"boom"}"""))))
    }

    /**
     * Most failures carry a body, but not all do and Retrofit's factories
     * cannot express "absent" — `Response.error` rejects a null body outright,
     * and `Response.success(null)` is a 200. An empty or blank body is the
     * closest a test can get, and it is the shape a gateway that answers with
     * nothing actually produces.
     */
    @Test
    fun `a failing http status without a body reports no message`() {
        assertEquals(AppError.Server(500, null), failure(mapEnvelope(errorOf(500, ""))))
        assertEquals(AppError.Server(500, null), failure(mapEnvelope(errorOf(500, "   "))))
        assertEquals(AppError.Server(404, null), failure(mapEnvelope(errorOf(404, "\n"))))
    }

    /** A proxy's HTML error page must not escape as an exception. */
    @Test
    fun `a non json error body reports no message instead of throwing`() {
        val outcome = mapEnvelope(errorOf(502, "<html><body>Bad Gateway</body></html>"))

        assertEquals(AppError.Server(502, null), failure(outcome))
    }

    @Test
    fun `an empty success body is reported as an empty response`() {
        val outcome = mapEnvelope(Response.success<ApiEnvelope<LoginData>>(null))

        val error = failure(outcome)
        assertTrue("expected Unexpected, got $error", error is AppError.Unexpected)
        assertEquals("服务器返回了空响应体（HTTP 200）", (error as AppError.Unexpected).detail)
    }

    @Test
    fun `transport exceptions map to offline`() {
        assertEquals(AppError.Offline, ApiResultMapper.toAppError(UnknownHostException("no dns")))
        assertEquals(AppError.Offline, ApiResultMapper.toAppError(IOException("connection reset")))
        assertEquals(AppError.Offline, failure(ApiResultMapper.mapThrown(UnknownHostException("no dns"))))
    }

    @Test
    fun `a socket timeout maps to timeout rather than offline`() {
        // Both are IOExceptions, so the order of the branches is what decides
        // whether the UI offers a retry.
        assertEquals(AppError.Timeout, ApiResultMapper.toAppError(SocketTimeoutException("timed out")))
    }

    @Test
    fun `a parse failure maps to an unexpected error`() {
        assertEquals(
            AppError.Unexpected("响应格式无法解析"),
            ApiResultMapper.toAppError(SerializationException("bad shape")),
        )
    }

    @Test
    fun `any other exception keeps its message for the log`() {
        val error = ApiResultMapper.toAppError(IllegalStateException("not what we expected"))

        assertEquals(AppError.Unexpected("not what we expected"), error)
    }

    @Test
    fun `mapBytes returns the captcha image`() {
        val outcome = ApiResultMapper.mapBytes(
            Response.success(byteArrayOf(0xFF.toByte(), 0xD8.toByte()).toResponseBody(JSON_MEDIA)),
        )

        assertTrue("expected a success, got $outcome", outcome is Outcome.Success)
        assertEquals(2, (outcome as Outcome.Success).value.size)
    }

    @Test
    fun `a zero length captcha is a failure not an empty image`() {
        val outcome = ApiResultMapper.mapBytes(Response.success(ByteArray(0).toResponseBody(JSON_MEDIA)))

        val error = failure(outcome)
        assertTrue("expected Unexpected, got $error", error is AppError.Unexpected)
        assertEquals("验证码图片为空", (error as AppError.Unexpected).detail)
    }

    @Test
    fun `a missing captcha body is a failure`() {
        val error = failure(ApiResultMapper.mapBytes(Response.success<ResponseBody>(null)))


        assertEquals("验证码响应为空", (error as AppError.Unexpected).detail)
    }

    @Test
    fun `mapBytes reports a failing http status`() {
        val outcome = ApiResultMapper.mapBytes(jsonBody(500, """{"msg":"boom"}"""))

        assertEquals(AppError.Server(500, null), failure(outcome))
    }

    /** Only two sentences are pinned verbatim; the rest are free to be reworded. */
    @Test
    fun `pinned chinese sentences stay as the UI expects them`() {
        assertEquals("网络连不上，检查一下连接再试", AppError.Offline.toChinese())
        assertEquals("登录状态失效了，重新登录一次", AppError.Unauthorized.toChinese())
        assertEquals("服务器拒绝了：余额不足", AppError.Server(7, "余额不足").toChinese())
        assertFalse(AppError.Server(7, null).toChinese().isBlank())
    }
}
