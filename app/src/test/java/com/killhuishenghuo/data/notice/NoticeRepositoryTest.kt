package com.killhuishenghuo.data.notice

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
 * 公告文件的读取,对着一个真的 HTTP 服务器。
 *
 * 和 `UpdateCheckerTest` 一样,真正的断言是**这个请求不许带什么**:公告发往 GitHub
 * Pages,而网关的客户端会给每一个没有 `Authorization` 的请求补上登录 token。一条宿舍
 * 热水的凭据没有任何理由被送到那里,所以只有真的发一次请求才能证明它没被带出去。
 */
class NoticeRepositoryTest {

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

    /** 不传客户端,让仓库自己的默认客户端上场 —— 那才是要被证明的东西。 */
    private fun repository() = NoticeRepository(endpoint = server.url("/notice.json").toString())

    private fun enqueue(body: String, code: Int = 200) {
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

    @Test
    fun `the notice request carries nothing from the session`() = runBlocking {
        enqueue("""{"updated":"2026-10-20","notices":[{"id":"n1","title":"停水"}]}""")

        repository().fetch()

        val request = takeRequest()
        assertNull("the session token must never reach GitHub Pages", request.headers["Authorization"])
        assertNull(request.headers["ApplicationType"])
        assertNull(request.headers["VersionCode"])
        assertEquals("/notice.json", request.url.encodedPath)
    }

    @Test
    fun `a feed parses into notices`() = runBlocking {
        enqueue(
            """
            {"updated":"2026-10-20","notices":[
              {"id":"n1","title":"停水","body":"今晚十点","url":"https://example.com/n1","minVersion":"1.3.4","until":"2026-11-10"},
              {"id":"n2","title":"第二条"}
            ]}
            """.trimIndent(),
        )

        val feed = repository().fetch().getOrNull()

        assertEquals(2, feed?.notices?.size)
        val first = feed?.notices?.first()
        assertEquals("停水", first?.title)
        assertEquals("今晚十点", first?.body)
        assertEquals("1.3.4", first?.minVersion)
        assertEquals("2026-11-10", first?.until)
        assertEquals("n2", feed?.notices?.last()?.id)
    }

    @Test
    fun `unknown fields do not break the read`() = runBlocking {
        // 文件是手写的,将来一定会多出字段。多一个字段不该让公告整个消失。
        enqueue("""{"updated":"2026-10-20","pinned":"n1","notices":[{"id":"n1","title":"停水","level":"warn"}]}""")

        val feed = repository().fetch().getOrNull()

        assertEquals("停水", feed?.notices?.single()?.title)
    }

    @Test
    fun `an http error is a failure, not an empty feed`() = runBlocking {
        // 两者对界面是同一件事(都不显示),但对缓存不是:失败必须保留上一次读到的那份,
        // 所以这里不能悄悄返回一个空 feed。
        enqueue("""{"message":"Not Found"}""", code = 404)

        val result = repository().fetch()

        assertTrue("404 必须报失败", result is Outcome.Failure)
    }

    @Test
    fun `an unreadable body is a failure`() = runBlocking {
        enqueue("<!doctype html><html>404</html>")

        assertTrue(repository().fetch() is Outcome.Failure)
    }
}