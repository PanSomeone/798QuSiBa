package com.killhuishenghuo.data.update

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
 * Asks GitHub what the newest release is.
 *
 * Three decisions in here are deliberate, and the first two are about what this
 * class must **not** do.
 *
 * **1. It does not borrow the gateway's OkHttp client.** That client adds
 * `Authorization: <token>` to every request that does not already carry one
 * ([com.killhuishenghuo.core.net.AuthInterceptor]) — correct for the 798 gateway,
 * catastrophic here, because this request goes to `api.github.com`. A session
 * token for someone's dormitory water account must never be handed to GitHub.
 * The client below has no interceptors at all, and a test pins the absence of
 * the header through a real MockWebServer.
 *
 * **2. It reads the release list, not `/releases/latest`.** GitHub's "latest"
 * endpoint silently skips pre-releases, and this project's releases are marked
 * pre-release on purpose. Asking `/releases/latest` would therefore answer "no
 * release" for the only releases that exist — a check that can never find an
 * update is indistinguishable from an endpoint that is down.
 *
 * **3. A release with no APK is still an answer.** [UpdateInfo.apkUrl] may be
 * null; the row then offers the release page, because that is where a human can
 * see why there is nothing to download.
 */
class UpdateChecker(
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: String = RELEASES_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun newest(): Outcome<UpdateInfo> = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.log(
                        "Update",
                        "release lookup failed http=${response.code} remaining=${response.header("X-RateLimit-Remaining")}",
                    )
                    // GitHub 的未鉴权额度是 **每小时 60 次、按出口 IP 算**,而宿舍最常见的
                    // 就是一栋楼共用一两个 IP。所以这条线路上 403/429 几乎总是「这个
                    // 出口的额度用完了」,不是「这个请求不合法」—— 而「服务器拒绝了
                    // （错误码 403）」对一个站在浴室门口的人没有任何下一步。
                    if (response.code == 403 || response.code == 429) {
                        return@use Outcome.Failure(
                            AppError.Unexpected("GitHub 这边限流了，过一会儿再看"),
                        )
                    }
                    return@use Outcome.Failure(AppError.Server(response.code, null))
                }

                val body = response.body?.string().orEmpty()
                val releases = NetworkModule.json.decodeFromString<List<GitHubRelease>>(body)
                val release = newestOf(releases)

                if (release == null) {
                    AppLog.log("Update", "release list had no usable entry entries=${releases.size}")
                    return@use Outcome.Failure(AppError.Unexpected("服务器上没有可用的发布版本"))
                }

                AppLog.log(
                    "Update",
                    "newest tag=${release.tagName} assets=${release.assets.size}",
                )
                Outcome.Success(
                    UpdateInfo(
                        tag = release.tagName,
                        releaseUrl = release.htmlUrl,
                        apkUrl = release.assets
                            .firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                            ?.downloadUrl,
                    ),
                )
            }
        }.getOrElse { throwable ->
            AppLog.error("Update", throwable, "release lookup failed")
            Outcome.Failure(ApiResultMapper.toAppError(throwable))
        }
    }

    /**
     * The entry to compare against: newest tag wins, drafts are skipped.
     *
     * The list arrives newest-first, but "newest" by *time* and newest by
     * *version* are different things when a hotfix is tagged after a major
     * release, so the comparison is on [Version] rather than on list order.
     */
    private fun newestOf(releases: List<GitHubRelease>): GitHubRelease? =
        releases
            .filter { !it.draft && Version.parts(it.tagName) != null }
            .maxWithOrNull { left, right ->
                Version.compare(left.tagName, right.tagName) ?: 0
            }

    private companion object {
        const val RELEASES_URL =
            "https://api.github.com/repos/PanSomeone/798QuSiBa/releases?per_page=10"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            // No interceptors. See decision 1 in the class comment: the gateway's
            // client attaches the session token, and this request is not for the
            // gateway.
            .build()
    }
}
