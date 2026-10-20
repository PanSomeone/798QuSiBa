package com.killhuishenghuo.data.notice

import com.killhuishenghuo.core.log.AppLog
import com.killhuishenghuo.core.net.NetworkModule
import com.killhuishenghuo.data.model.AppError
import com.killhuishenghuo.data.model.Outcome
import com.killhuishenghuo.data.remote.ApiResultMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 读 `notice.json`。
 *
 * 和 [com.killhuishenghuo.data.update.UpdateChecker] 一样,**不能借网关那个 OkHttp
 * 客户端**:那个客户端会给每个没有 `Authorization` 的请求补上登录 token,而这个请求
 * 发往 GitHub Pages,一份宿舍热水的账号凭据没有任何理由被送到那里。这里的客户端一个
 * 拦截器都没有,测试用 MockWebServer 钉住这件事。
 *
 * 与检查更新的区别是这里不走 `api.github.com`,所以没有每小时 60 次的额度:公告可以
 * 每次启动都问一次,而且不需要像检查更新那样担心 403。
 */
class NoticeRepository(
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: String = FEED_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun fetch(): Outcome<NoticeFeed> = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url(endpoint)
            // 公告是要"改成什么立刻生效"的东西,代理/中间层缓存会让它晚到一天。
            .header("Cache-Control", "no-cache")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.log("Notice", "feed lookup failed http=${response.code}")
                    return@use Outcome.Failure(AppError.Server(response.code, null))
                }

                val body = response.body?.string().orEmpty()
                val feed = NetworkModule.json.decodeFromString<NoticeFeed>(body)
                AppLog.log("Notice", "feed loaded notices=${feed.notices.size} updated=${feed.updated}")
                Outcome.Success(feed)
            }
        }.getOrElse { throwable ->
            // 读不到就是读不到:没有公告比"公告加载失败"这个提示更有用。日志留痕,
            // 屏幕上什么也不说。
            AppLog.error("Notice", throwable, "feed lookup failed")
            Outcome.Failure(ApiResultMapper.toAppError(throwable))
        }
    }

    companion object {
        /**
         * 和网页端同一个文件。改这里等于同时改两端。
         *
         * `https://pansomeone.github.io/798QuSiBa/` 是 `/docs` 目录的 Pages 地址。
         */
        const val FEED_URL = "https://pansomeone.github.io/798QuSiBa/notice.json"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            // 没有拦截器。见类注释:网关的 token 不该出现在这条线路上。
            .build()
    }
}