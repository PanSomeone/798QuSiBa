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
 *
 * Every URL the checker knows is pinned to this server, including the mirror —
 * an unpinned mirror would make a test that exercises the 403 road quietly ask
 * gitee.com how fast its download is today.
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
    private fun checker(): UpdateChecker = UpdateChecker(
        endpoint = server.url("/releases").toString(),
        // Never the real github.com / gitee.com. If these are not pinned here, a
        // test that walks the fallback roads would quietly ask the internet.
        fallbackEndpoint = server.url("/releases.atom").toString(),
        mirrorEndpoint = server.url("/mirror").toString(),
        mirrorDownload = server.url("/gitee/download").toString(),
        mirrorPage = server.url("/gitee/tag/").toString(),
    )

    private fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse.Builder()
                .code(code)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )
    }

    private fun enqueueAtom(vararg tags: String) {
        val entries = tags.joinToString("\n") {
            val url = server.url("/releases/tag/$it")
            """  <entry><title>$it</title><link rel="alternate" href="$url"/></entry>"""
        }
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/atom+xml; charset=utf-8")
                .body("""<?xml version="1.0"?><feed>$entries</feed>""")
                .build(),
        )
    }

    private fun enqueueStatus(code: Int) {
        server.enqueue(MockResponse.Builder().code(code).build())
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

    /** A release exactly as Gitee's API describes it: no `html_url`, no `draft`. */
    private fun mirrorRelease(tag: String, withApk: Boolean = true): String {
        val asset = if (withApk) {
            """{"name": "huishui-$tag-fast.apk", "browser_download_url": "${server.url("/gitee/download/$tag/huishui-$tag-fast.apk")}"}"""
        } else {
            ""
        }
        return """{"tag_name":"$tag","name":"release $tag","prerelease":true,"assets":[$asset]}"""
    }

    @Test
    fun `the update request carries no authorization header`() = runBlocking {
        // The gateway's client attaches the session token to every request it can
        // (AuthInterceptor). This request goes to api.github.com, and a token for
        // someone's dormitory water account has no business being sent to GitHub.
        // The checker therefore builds its own client -- and this is the assertion
        // that keeps it that way.
        enqueueJson("[${release("v1.2.4")}]")
        enqueueStatus(404) // the mirror has not been stocked with this tag

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
        enqueueStatus(404)

        val info = checker().newest().getOrNull()

        assertEquals("v1.3.0", info?.tag)
    }

    @Test
    fun `a draft is not a release anybody can download`() = runBlocking {
        enqueueJson("[${release("v1.4.0", draft = true)}, ${release("v1.2.4")}]")
        enqueueStatus(404)

        assertEquals("v1.2.4", checker().newest().getOrNull()?.tag)
    }

    @Test
    fun `the apk asset is picked out of the release`() = runBlocking {
        enqueueJson("[${release("v1.2.4")}]")
        enqueueStatus(404) // no mirror copy, so the release's own asset is used

        val info = checker().newest().getOrNull()

        assertEquals("https://example.com/v1.2.4-fast.apk", info?.apkUrl)
        assertEquals("https://github.com/PanSomeone/798QuSiBa/releases/tag/v1.2.4", info?.releaseUrl)
    }

    @Test
    fun `the mirror's copy is preferred when the mirror has it`() = runBlocking {
        // The tag is the only thing needed: the file name is this project's own
        // convention. The HEAD is what turns that guess into a download button.
        enqueueJson("[${release("v1.2.4")}]")
        enqueueStatus(200)

        val info = checker().newest().getOrNull()

        assertEquals(
            server.url("/gitee/download/v1.2.4/huishui-1.2.4-fast.apk").toString(),
            info?.apkUrl,
        )
    }

    @Test
    fun `a release with no apk attached is still an answer`() = runBlocking {
        // Notes-only tags are legitimate, and the row falls back to the release
        // page rather than claiming there is nothing to install.
        enqueueJson("[${release("v1.2.4", assets = "")}]")
        enqueueStatus(404) // the mirror has nothing for it either

        val info = checker().newest().getOrNull()

        assertEquals("v1.2.4", info?.tag)
        assertNull(info?.apkUrl)
        assertTrue(info!!.releaseUrl.isNotBlank())
    }

    @Test
    fun `a failing server is a failure, not an empty list`() = runBlocking {
        enqueueJson("""{"message":"rate limited"}""", code = 403)
        enqueueStatus(500) // the mirror is down too
        enqueueStatus(500) // and so is the feed

        val outcome = checker().newest()

        assertTrue(outcome is Outcome.Failure)
    }

    @Test
    fun `a 403 walks the release feed instead of giving up`() = runBlocking {
        // api.github.com is 60 requests an hour per exit IP, and a dormitory is one
        // exit IP. github.com serves releases.atom off a different budget, so the
        // second road exists exactly for the moment the first one says 403.
        enqueueJson("""{"message":"API rate limit exceeded"}""", code = 403)
        enqueueStatus(500) // the mirror answers nothing useful
        enqueueAtom("v1.3.2", "v1.3.3")
        enqueueStatus(404) // no mirror copy of v1.3.3
        enqueueStatus(200) // HEAD on the constructed asset URL

        val info = checker().newest().getOrNull()

        assertEquals("v1.3.3", info?.tag)
        assertEquals(server.url("/releases/tag/v1.3.3").toString(), info?.releaseUrl)
        assertTrue(
            "the asset URL should be built from the project's own naming",
            info!!.apkUrl!!.endsWith("/releases/download/v1.3.3/huishui-1.3.3-fast.apk"),
        )
    }

    @Test
    fun `the feed is read for the newest tag, not the first one`() = runBlocking {
        // The feed is ordered by time; a hotfix tagged today can be older than a
        // release tagged yesterday, and the fallback must not answer with the wrong
        // one just because it arrived first.
        enqueueJson("""{"message":"API rate limit exceeded"}""", code = 403)
        enqueueStatus(500)
        enqueueAtom("v1.2.9", "v1.2.10", "v1.3.0")
        enqueueStatus(404)
        enqueueStatus(200)

        assertEquals("v1.3.0", checker().newest().getOrNull()?.tag)
    }

    @Test
    fun `a feed with no asset behind it still offers the release page`() = runBlocking {
        // The asset URL is a guess at a naming convention. A 404 on it must not turn
        // into a download button that downloads nothing.
        enqueueJson("""{"message":"API rate limit exceeded"}""", code = 403)
        enqueueStatus(500)
        enqueueAtom("v1.3.3")
        enqueueStatus(404) // mirror
        enqueueStatus(404) // github

        val info = checker().newest().getOrNull()

        assertEquals("v1.3.3", info?.tag)
        assertNull(info?.apkUrl)
        assertTrue(info!!.releaseUrl.isNotBlank())
    }

    @Test
    fun `a feed that is not a feed keeps the rate limit sentence`() = runBlocking {
        enqueueJson("""{"message":"API rate limit exceeded"}""", code = 403)
        enqueueStatus(500)
        server.enqueue(
            MockResponse.Builder().code(200).body("<html>not a feed</html>").build(),
        )

        val outcome = checker().newest()

        assertEquals(
            "GitHub 这边限流了，过一会儿再看",
            (outcome as Outcome.Failure).error.toChinese(),
        )
    }

    @Test
    fun `github's rate limit gets a sentence that says so`() = runBlocking {
        // 403 from api.github.com is not "this request is malformed", it is "this exit
        // IP has spent its 60 per hour" -- and a dorm shares one exit IP. The generic
        // "server refused (code 403)" gives somebody standing in a bathroom nothing to
        // do next, so the sentence has to name GitHub and say it is temporary. It is
        // still the answer when every other road is blocked too.
        enqueueJson("""{"message":"API rate limit exceeded"}""", code = 403)
        enqueueStatus(500) // mirror
        enqueueStatus(403) // feed

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
        enqueueStatus(500) // mirror

        val outcome = checker().newest()

        assertEquals(
            "服务器拒绝了（错误码 500）",
            (outcome as Outcome.Failure).error.toChinese(),
        )
    }

    @Test
    fun `a list with no readable tag is a failure`() = runBlocking {
        enqueueJson("[${release("latest")}]")
        enqueueStatus(500) // mirror

        assertTrue(checker().newest() is Outcome.Failure)
    }

    @Test
    fun `the mirror answers when github does not`() = runBlocking {
        // The whole point of the mirror: api.github.com is unreachable in the place
        // where this app is used, and gitee.com is not.
        enqueueJson("""{"message":"boom"}""", code = 502)
        enqueueJson("[${mirrorRelease("v1.3.7")}]")

        val info = checker().newest().getOrNull()

        assertEquals("v1.3.7", info?.tag)
        assertEquals(server.url("/gitee/tag/v1.3.7").toString(), info?.releaseUrl)
        assertEquals(
            server.url("/gitee/download/v1.3.7/huishui-v1.3.7-fast.apk").toString(),
            info?.apkUrl,
        )
        // Two requests: GitHub, then the mirror. Neither may carry the token.
        repeat(2) {
            val request = takeRequest()
            assertNull(
                "the session token must never reach ${request.url}",
                request.headers["Authorization"],
            )
        }
    }

    @Test
    fun `the mirror's list is read for the newest tag, not the first one`() = runBlocking {
        // Same trap as the feed: Gitee lists by time, so the hotfix can arrive first
        // with an older version number.
        enqueueJson("""{"message":"boom"}""", code = 500)
        enqueueJson("[${mirrorRelease("v1.2.9")}, ${mirrorRelease("v1.3.1")}]")

        assertEquals("v1.3.1", checker().newest().getOrNull()?.tag)
    }

    @Test
    fun `a mirror entry with no apk still names the version`() = runBlocking {
        enqueueJson("""{"message":"boom"}""", code = 500)
        enqueueJson("[${mirrorRelease("v1.3.7", withApk = false)}]")

        val info = checker().newest().getOrNull()

        assertEquals("v1.3.7", info?.tag)
        assertNull(info?.apkUrl)
        assertTrue(info!!.releaseUrl.isNotBlank())
    }
}