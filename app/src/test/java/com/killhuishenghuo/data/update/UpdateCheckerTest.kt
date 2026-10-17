package com.killhuishenghuo.data.update

import com.killhuishenghuo.data.model.Outcome
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The release lookup, against a real HTTP server.
 *
 * The point of using MockWebServer rather than a fake is the header assertion
 * below: what this test is really about is **what this request must not carry**,
 * and only a real request can answer that.
 */
class UpdateCheckerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    /**
     * No client is passed, so the checker's *own* default client is the one under
     * test — which is the whole point. Passing a hand-made client here would test
     * the test.
     */
    private fun checker(): UpdateChecker = UpdateChecker(endpoint = server.url("/releases").toString())

    private fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse.Builder()
                .code(code)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )
    }

    private fun takeRequest() =
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "no request reached the server" }

    private fun release(
        tag: String,
        assets: String = """
            {"name": "huishui-fast.apk", "size": 9532599, "browser_download_url": "https://example.com/$tag-fast.apk"}
        """.trimIndent(),
        draft: Boolean = false,
        url: String = "https://github.com/PanSomeone/798QuSiBa/releases/tag/$tag",
    ) = """{"tag_name":"$tag","html_url":"$url","draft":$draft,"assets":[$assets]}"""

    @Test
    fun `the update request carries no authorization header`() = runBlocking {
        // The gateway's client attaches the session token to every request it can
        // (AuthInterceptor). This request goes to api.github.com, and a token for
        // someone's dormitory water account has no business being sent to GitHub.
        // The checker therefore builds its own client -- and this is the assertion
        // that keeps it that way.
        enqueueJson("[${release("v1.2.4")}]")

        checker().newest()

        val request = takeRequest()
        assertNull("the session token must never reach GitHub", request.headers["Authorization"])
        assertNull(request.headers["ApplicationType"])
        assertNull(request.headers["VersionCode"])
    }

    @Test
    fun `the newest tag wins, not the newest list entry`() = runBlocking {
        // GitHub returns newest-first by time, which is not the same as newest by
        // version when a hotfix is tagged after a major release.
        enqueueJson("[${release("v1.2.9")}, ${release("v1.2.10")}, ${release("v1.3.0")}]")

        val info = checker().newest().getOrNull()

        assertEquals("v1.3.0", info?.tag)
    }

    @Test
    fun `a draft is not a release anybody can download`() = runBlocking {
        enqueueJson("[${release("v1.4.0", draft = true)}, ${release("v1.2.4")}]")

        assertEquals("v1.2.4", checker().newest().getOrNull()?.tag)
    }

    @Test
    fun `the apk asset is picked out of the release`() = runBlocking {
        enqueueJson("[${release("v1.2.4")}]")

        val info = checker().newest().getOrNull()

        assertEquals("https://example.com/v1.2.4-fast.apk", info?.apkUrl)
        assertEquals("https://github.com/PanSomeone/798QuSiBa/releases/tag/v1.2.4", info?.releaseUrl)
    }

    @Test
    fun `a release with no apk attached is still an answer`() = runBlocking {
        // Notes-only tags are legitimate, and the row falls back to the release
        // page rather than claiming there is nothing to install.
        enqueueJson("[${release("v1.2.4", assets = "")}]")

        val info = checker().newest().getOrNull()

        assertEquals("v1.2.4", info?.tag)
        assertNull(info?.apkUrl)
        assertTrue(info!!.releaseUrl.isNotBlank())
    }

    @Test
    fun `a failing server is a failure, not an empty list`() = runBlocking {
        enqueueJson("""{"message":"rate limited"}""", code = 403)

        val outcome = checker().newest()

        assertTrue(outcome is Outcome.Failure)
    }

    @Test
    fun `github's rate limit gets a sentence that says so`() = runBlocking {
        // 403 from api.github.com is not "this request is malformed", it is "this exit
        // IP has spent its 60 per hour" -- and a dorm shares one exit IP. The generic
        // "server refused (code 403)" gives somebody standing in a bathroom nothing to
        // do next, so the sentence has to name GitHub and say it is temporary.
        enqueueJson("""{"message":"API rate limit exceeded"}""", code = 403)

        val outcome = checker().newest()

        assertTrue(outcome is Outcome.Failure)
        assertEquals(
            "GitHub 这边限流了，过一会儿再看",
            (outcome as Outcome.Failure).error.toChinese(),
        )
    }

    @Test
    fun `a real server error keeps the generic sentence`() = runBlocking {
        // Guard against the fix above swallowing everything: a 500 is not a rate limit
        // and must not be reported as one.
        enqueueJson("""{"message":"boom"}""", code = 500)

        val outcome = checker().newest()

        assertEquals(
            "服务器拒绝了（错误码 500）",
            (outcome as Outcome.Failure).error.toChinese(),
        )
    }

    @Test
    fun `a list with no readable tag is a failure`() = runBlocking {
        enqueueJson("[${release("latest")}]")

        assertTrue(checker().newest() is Outcome.Failure)
    }
}
