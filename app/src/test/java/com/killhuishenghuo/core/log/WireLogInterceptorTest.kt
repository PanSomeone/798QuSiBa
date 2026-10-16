package com.killhuishenghuo.core.log

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the wire log is allowed to put into the buffer.
 *
 * The interceptor's job is to record bodies, and for JSON that is the whole
 * value of the feature. For an image it is the opposite: `captcha/` answers with
 * a PNG, and `decodeToString()` on a PNG writes NUL bytes and box-drawing noise
 * into a file the user is expected to open and send. A real report arrived that
 * way — 43 NULs, and a `.txt` that ordinary text tools refuse to read, which
 * buries the JSON that was the point.
 *
 * The tests below therefore assert two things the interceptor must keep doing at
 * once: describe what it will not paste, and still hand the caller every byte.
 */
class WireLogInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().addInterceptor(WireLogInterceptor()).build()
        AppLog.install(appVersion = "test", buildType = "test")
    }

    @After
    fun tearDown() {
        server.close()
        AppLog.install(appVersion = "test", buildType = "test")
    }

    /** A PNG header and IHDR, NULs included: what the gateway actually returns. */
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x50, 0x08,
    )

    private fun enqueue(contentType: String, bytes: ByteArray) {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", contentType)
                .body(Buffer().write(bytes))
                .build(),
        )
    }

    private fun call(): Response =
        client.newCall(Request.Builder().url(server.url("/api/v1/captcha/")).build()).execute()

    @Test
    fun `an image response is described, not pasted, into the log`() {
        enqueue("image/png", png)

        call().use { it.body?.bytes() }
        val dump = AppLog.dump("test")

        assertFalse("a NUL byte reached the exported file", dump.contains('\u0000'))
        assertTrue(dump, dump.contains("body: 24 bytes of image/png, not logged"))
        // The request line still went in: knowing an image came back is the part
        // worth keeping.
        assertTrue(dump, dump.contains("← 200"))
    }

    @Test
    fun `a json response is still pasted whole`() {
        val json = """{"code":0,"data":{"favos":[]}}"""
        enqueue("application/json", json.toByteArray())

        call().use { it.body?.bytes() }
        val dump = AppLog.dump("test")

        assertTrue(dump, dump.contains(json))
        assertFalse(dump, dump.contains("not logged"))
    }

    @Test
    fun `a body with no declared type is still treated as text`() {
        // The gateway omits Content-Type on some answers, and dropping those
        // would lose exactly the payloads this log exists to show.
        server.enqueue(MockResponse.Builder().code(200).body("""{"code":-99}""").build())

        call().use { it.body?.bytes() }

        assertTrue(AppLog.dump("test").contains("""{"code":-99}"""))
    }

    @Test
    fun `the caller still receives every byte the interceptor read`() {
        enqueue("image/png", png)

        val received = call().use { it.body?.bytes() }

        // Reading the body to log it must not consume it: the captcha screen
        // decodes these bytes, and an interceptor that eats them turns a working
        // login into a blank picture.
        assertTrue(png.contentEquals(received))
    }
}
