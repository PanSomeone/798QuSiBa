package com.killhuishenghuo.data.remote

import com.killhuishenghuo.core.net.ApiConstants
import com.killhuishenghuo.core.net.NetworkModule
import com.killhuishenghuo.core.net.RetrofitGatewayClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The wire contract, checked against a real HTTP server rather than a mock.
 *
 * Everything here is a claim about what the *gateway* sees: a wrong path, a
 * missing `args` parameter or a `Bearer `-prefixed token does not fail loudly in
 * production — the server answers `code: 0` and does nothing, or answers `-99`
 * and the user is told to log in again. The only place that can catch a drift
 * from the contract working third-party clients use is a test that reads the
 * bytes that actually went out on the socket, so this file builds the real
 * Retrofit + OkHttp stack through [NetworkModule] and points it at a local
 * MockWebServer.
 */
class GatewayContractTest {

    private lateinit var server: MockWebServer

    /**
     * What the "session" holds. Read at request time on purpose: one test can
     * then change the token mid-flight and observe the header change with it.
     */
    private var token: String? = null

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    // ------------------------------------------------------------- fixtures

    /**
     * The base URL includes `/api/v1/`, because that prefix lives in
     * `NetworkModule.baseUrl` and `HuiShengHuoApi` declares relative paths. A
     * test that rooted the mock at `/` would still pass while the app talked to
     * the wrong path, so the prefix is part of what is under test.
     */
    private fun baseUrl(): String = server.url("/api/v1/").toString()

    private fun okHttp(tokenProvider: () -> String? = { token }): OkHttpClient =
        // Logging off: HTTP logs on a failing assertion are noise, not evidence.
        NetworkModule.okHttpClient(tokenProvider = tokenProvider, enableLogging = false)

    private fun gateway(client: OkHttpClient = okHttp()): RetrofitGatewayClient =
        RetrofitGatewayClient(NetworkModule.api(NetworkModule.retrofit(client = client, baseUrl = baseUrl())))

    private fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse.Builder()
                .code(code)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )
    }

    private fun enqueueJpeg(bytes: ByteArray) {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/jpeg")
                .body(Buffer().write(bytes))
                .build(),
        )
    }

    /** Bounded wait: a missing request must fail the test, not hang the build. */
    private fun takeRequest(): RecordedRequest =
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "no request reached the server" }

    /** `target` is path + query, exactly as it went on the wire. */
    private fun path(request: RecordedRequest): String = request.target.orEmpty().substringBefore('?')

    /** Query parameters in wire order, so an empty value stays distinguishable from no value. */
    private fun query(request: RecordedRequest): List<Pair<String, String>> =
        request.target.orEmpty()
            .substringAfter('?', "")
            .takeIf { it.isNotEmpty() }
            ?.split('&')
            ?.map { it.substringBefore('=') to it.substringAfter('=', "") }
            .orEmpty()

    private fun params(request: RecordedRequest): Map<String, String> = query(request).toMap()

    private fun requestBody(request: RecordedRequest): JsonObject =
        NetworkModule.json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject

    private fun bodyText(request: RecordedRequest): String = requireNotNull(request.body).utf8()

    private fun assertClientHeaders(
        request: RecordedRequest,
        applicationType: String = ApiConstants.APPLICATION_TYPE_DEVICE,
    ) {
        assertEquals(ApiConstants.USER_AGENT, request.headers["user-agent"])
        assertEquals(ApiConstants.APP_VERSION_CODE, request.headers["VersionCode"])
        assertEquals(applicationType, request.headers["ApplicationType"])
    }

    // ---------------------------------------------------------------- paths

    @Test
    fun `master is a GET of the versioned path`() = runBlocking {
        enqueueJson("""{"code":0,"data":{"ads":[]}}""")

        assertEquals(200, gateway().master().code())

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/ui/app/master", path(request))
        assertClientHeaders(request)
    }

    @Test
    fun `start carries exactly the six captured query parameters`() = runBlocking {
        enqueueJson("""{"code":0}""")

        gateway().start(did = "D1", ptype = 91, args = "")

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/dev/start", path(request))
        // `args=` must be *present and empty*, not omitted: that is what a
        // dormitory shower takes, and `cnt=1` is one wallet debit, not a repeat.
        assertEquals(
            mapOf(
                "did" to "D1",
                "upgrade" to "true",
                "ptype" to "91",
                "args" to "",
                "rcp" to "false",
                "cnt" to "1",
            ),
            params(request),
        )
        assertTrue("args must be sent, even when empty", query(request).any { it.first == "args" })
        // 开阀走**账户作用域** —— 设备作用域上加了一道「请升级最新版APP」的闸,
        // 和客户端报的版本号无关;见 ApiConstants.applicationTypeFor。
        assertClientHeaders(request, ApiConstants.APPLICATION_TYPE_ACCOUNT)
    }

    @Test
    fun `qr-use asks for the scanned id under the device scope`() = runBlocking {
        enqueueJson("""{"code":0,"data":{"dev":{"id":"D1"},"qr":{"type":3}}}""")

        gateway().qrUse(id = "AbCd1234")

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/qr/use", path(request))
        assertEquals(mapOf("id" to "AbCd1234"), params(request))
        assertClientHeaders(request)
    }

    @Test
    fun `favourite adds the device rather than removing it`() = runBlocking {
        enqueueJson("""{"code":0}""")

        gateway().favourite(did = "865137068739782", remove = false)

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/dev/favo", path(request))
        // `remove=0` is the whole feature: it is what puts a device into the
        // `favos[]` array that `ui/app/master` answers with, and that array is the
        // only device list this app can read. `remove=1` takes a device off the
        // account — the two are one boolean apart, so the boolean is asserted and
        // not assumed.
        assertEquals(mapOf("did" to "865137068739782", "remove" to "0"), params(request))
        assertClientHeaders(request)
    }

    @Test
    fun `stop carries did and rcp only`() = runBlocking {
        enqueueJson("""{"code":0}""")

        gateway().stop(did = "D1")
        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/dev/end", path(request))
        assertEquals(mapOf("did" to "D1", "rcp" to "false"), params(request))
        // 同 start:关阀也必须走账户作用域。
        assertClientHeaders(request, ApiConstants.APPLICATION_TYPE_ACCOUNT)
    }

    @Test
    fun `status asks for did with more=false`() = runBlocking {
        enqueueJson("""{"code":0,"data":{"device":{"status":0}}}""")

        gateway().status(did = "D1")

        val request = takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/ui/app/dev/status", path(request))
        assertEquals(mapOf("did" to "D1", "more" to "false"), params(request))
        assertClientHeaders(request)
    }

    @Test
    fun `captcha keeps its trailing slash and invents a key and a cache buster`() = runBlocking {
        enqueueJpeg(JPEG)

        val response = gateway().captcha(key = "KEY1234567")

        val request = takeRequest()
        assertEquals("GET", request.method)
        // The trailing slash is load-bearing: the gateway 404s on `captcha`.
        assertEquals("/api/v1/captcha/", path(request))
        assertEquals("KEY1234567", params(request)["s"])
        // `r` is a cache buster, so only "a plausible epoch millis" is assertable.
        val r = params(request)["r"]
        assertNotNull(r)
        assertTrue("r should be epoch millis, was $r", (r!!.toLongOrNull() ?: 0L) > 1_600_000_000_000L)
        assertClientHeaders(request)

        // The bytes are the whole point of the call, so check them end to end.
        assertTrue(requireNotNull(response.body()).bytes().contentEquals(JPEG))
        assertTrue(response.body()!!.contentType().toString().contains("image/jpeg"))
    }

    // ---------------------------------------------------------------- bodies

    @Test
    fun `login posts the four captured body keys`() = runBlocking {
        enqueueJson("""{"code":0,"data":{"al":{"token":"T","uid":"U"}}}""")

        gateway().login(
            LoginRequest(openCode = "", smsCode = "1234", phone = "13800138000", clientId = "CID"),
        )

        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/acc/login", path(request))
        assertClientHeaders(request)
        assertTrue(request.headers["Content-Type"].orEmpty().startsWith("application/json"))

        val body = requestBody(request)
        // `openCode` must survive as an empty string rather than being dropped:
        // it is a declared field of the captured client, and the gateway treats
        // a missing key differently from an empty one.
        assertEquals(setOf("openCode", "authCode", "un", "cid"), body.keys)
        assertEquals("", body["openCode"]!!.jsonPrimitive.content)
        assertEquals("1234", body["authCode"]!!.jsonPrimitive.content)
        assertEquals("13800138000", body["un"]!!.jsonPrimitive.content)
        assertEquals("CID", body["cid"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sendSmsCode posts the three captured body keys`() = runBlocking {
        enqueueJson("""{"code":0}""")

        gateway().sendSmsCode(SmsCodeRequest(phone = "13800138000", captchaText = "AB12", captchaKey = "KEY1234567"))

        val request = takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/acc/login/code", path(request))
        val body = requestBody(request)
        assertEquals(setOf("un", "authCode", "s"), body.keys)
        assertEquals("13800138000", body["un"]!!.jsonPrimitive.content)
        assertEquals("AB12", body["authCode"]!!.jsonPrimitive.content)
        assertEquals("KEY1234567", body["s"]!!.jsonPrimitive.content)
        assertClientHeaders(request)
    }

    // --------------------------------------------------------------- headers

    @Test
    fun `the three client headers are stamped on every request`() = runBlocking {
        enqueueJson("""{"code":0,"data":{"ads":[]}}""")
        enqueueJson("""{"code":0}""")
        enqueueJpeg(JPEG)

        val client = gateway()
        client.master()
        client.start(did = "D1", ptype = 91, args = "")
        client.captcha(key = "KEY1234567")

        // Three different calls, including an unauthenticated one: the headers
        // are the gateway's price of entry on all of them. Only the scope differs,
        // and `dev/start` is the one that moved to the account scope.
        assertClientHeaders(takeRequest())
        assertClientHeaders(takeRequest(), ApiConstants.APPLICATION_TYPE_ACCOUNT)
        assertClientHeaders(takeRequest())
    }

    @Test
    fun `a token is sent bare, with no Bearer prefix`() = runBlocking {
        token = "abc123token"
        enqueueJson("""{"code":0,"data":{"ads":[]}}""")

        gateway().master()

        // Verbatim equality is the assertion: any prefix would fail here.
        assertEquals("abc123token", takeRequest().headers["Authorization"])
    }

    @Test
    fun `no Authorization header without a usable token`() = runBlocking {
        enqueueJson("""{"code":0,"data":{"ads":[]}}""")
        enqueueJson("""{"code":0,"data":{"ads":[]}}""")
        enqueueJson("""{"code":0,"data":{"ads":[]}}""")

        // null, empty and whitespace-only all mean "not signed in". Sending
        // `Authorization: ` (empty) is not the same request as omitting it, and
        // the gateway answers -99 for the former.
        for (value in listOf(null, "", "   ")) {
            token = value
            gateway().master()
        }

        repeat(3) { assertNull(takeRequest().headers["Authorization"]) }
    }

    @Test
    fun `an explicitly set Authorization is never overwritten`() {
        // This is how the login screen sends a token it has just obtained but
        // has not persisted yet: the interceptor must stand aside.
        token = "from-the-session"

        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"code":0}""").build(),
        )
        okHttp().newCall(
            Request.Builder()
                .url(baseUrl() + "ui/app/master")
                .header("Authorization", "hand-set-token")
                .build(),
        ).execute().use { /* body is drained by close() */ }

        val request = takeRequest()
        assertEquals("hand-set-token", request.headers["Authorization"])
        assertClientHeaders(request)
    }

    // -------------------------------------------------------------- parsing

    @Test
    fun `master parses favos and tolerates the keys this client does not model`() = runBlocking {
        enqueueJson(
            """
            {"code":0,"data":{
              "account":{"id":"a1","name":"张三","pn":"13800000000","img":"http://x/y.png"},
              "favos":[
                {"id":"d1","name":"淋浴器A","bm":{"dtype":6},"gene":{"status":0}},
                {"id":"d2","name":"","bm":{"dtype":5},"gene":{"status":1}}
              ],
              "ads":[{"id":"ad1"}],"ep":{"x":1},"subs":[]
            },"time":1792081702558}
            """.trimIndent(),
        )

        val body = requireNotNull(gateway().master().body())

        assertEquals(0, body.code)
        val data = requireNotNull(body.data)
        assertEquals("a1", data.account?.id)
        assertEquals("张三", data.account?.name)
        assertEquals("13800000000", data.account?.phone)
        assertEquals(2, data.favos.size)
        assertEquals(listOf("d1", "d2"), data.favos.map { it.id })
        assertEquals(listOf(6, 5), data.favos.map { it.bm?.dtype })
        assertEquals(listOf(0, 1), data.favos.map { it.gene?.status })
        // A blank name is kept as-is here; falling back to a label is the UI's job.
        assertEquals("", data.favos[1].name)
        assertEquals(1792081702558L, body.time)
    }

    @Test
    fun `master without a favos key parses to an empty list`() = runBlocking {
        // Verbatim shape of what the live gateway answered unauthenticated:
        // `{"code":0,"data":{"ads":[]},"time":...}`. Requiring `favos` would turn
        // a logged-out launch into a parse failure instead of an empty screen.
        enqueueJson("""{"code":0,"data":{"ads":[]},"time":1792081702558}""")

        val data = requireNotNull(gateway().master().body()?.data)

        assertTrue(data.favos.isEmpty())
        assertNull(data.account)
    }

    @Test
    fun `a non-2xx master response is not an exception`() = runBlocking {
        enqueueJson("""{"code":-99,"msg":"登录已过期"}""", code = 200)

        val response = gateway().master()

        assertTrue(response.isSuccessful)
        assertEquals(-99, response.body()?.code)
        assertEquals("登录已过期", response.body()?.message)
    }

    private companion object {
        /** Not a real image: the test only asserts the bytes survive the trip. */
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02, 0x03, 0xFF.toByte(), 0xD9.toByte())
    }
}
