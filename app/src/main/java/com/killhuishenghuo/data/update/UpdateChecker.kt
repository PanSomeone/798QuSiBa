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
 *
 * **4. There is a second road, for when the API says 403.** `api.github.com` is
 * capped at 60 requests an hour *per exit IP*, and a dormitory is one exit IP
 * for a whole building — so the rate limit is the normal case there, not the
 * exception. `releases.atom` is served by github.com itself (the web site, a
 * different budget entirely), needs no token, and lists pre-releases, which the
 * `/releases/latest` page and endpoint both refuse to do. Verified live
 * 2026-10-20: 200, 16 KB, every tag from v1.3.3 down, newest first.
 */
class UpdateChecker(
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: String = RELEASES_URL,
    private val fallbackEndpoint: String = FALLBACK_URL,
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
                    //
                    // 所以先走 github.com 自己的 releases.atom:那是网站,不是 API,
                    // 额度不共用,也不需要 token。它连不上再说限流那句话。
                    if (response.code == 403 || response.code == 429) {
                        return@use newestViaAtom(
                            reason = AppError.Unexpected("GitHub 这边限流了，过一会儿再看"),
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
     * The newest tag, read out of the release *feed* rather than the API.
     *
     * Every `/releases/tag/<tag>` link in the document is a candidate, and the
     * winner is chosen by [Version] for the same reason [newestOf] does it: a
     * feed is ordered by time, and time is not version order.
     *
     * `reason` is what gets reported if this road is also blocked — the rate
     * limit sentence, which is then the true explanation of the failure.
     */
    private fun newestViaAtom(reason: AppError): Outcome<UpdateInfo> {
        val request = Request.Builder().url(fallbackEndpoint).build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.log("Update", "atom fallback failed http=${response.code}")
                    return@use Outcome.Failure(reason)
                }

                val body = response.body?.string().orEmpty()
                val links = TAG_LINK.findAll(body)
                    .map { it.groupValues[0] to it.groupValues[1] }
                    .toList()
                val best = links
                    .filter { Version.parts(it.second) != null }
                    .maxWithOrNull { left, right ->
                        Version.compare(left.second, right.second) ?: 0
                    }

                if (best == null) {
                    AppLog.log(
                        "Update",
                        "atom fallback had no usable tag (links=${links.size})",
                    )
                    return@use Outcome.Failure(reason)
                }

                val (releaseUrl, tag) = best
                AppLog.log("Update", "atom fallback tag=$tag links=${links.size}")
                Outcome.Success(
                    UpdateInfo(
                        tag = tag,
                        releaseUrl = releaseUrl,
                        apkUrl = apkUrlFor(releaseUrl, tag),
                    ),
                )
            }
        }.getOrElse { throwable ->
            AppLog.error("Update", throwable, "atom fallback failed")
            Outcome.Failure(reason)
        }
    }

    /**
     * Where a direct download would be, if it is there.
     *
     * The asset name is this project's own convention
     * (`huishui-<version>-fast.apk`, the shrunk build that installs over the
     * previous one), so the URL can be constructed without asking the API — but a
     * constructed URL is a guess until something answers, and a guess that 404s
     * is worse than the release page. Hence the HEAD: 200 keeps it, anything else
     * (including a network error) leaves [UpdateInfo.apkUrl] null and the row
     * offers the page.
     */
    private fun apkUrlFor(releaseUrl: String, tag: String): String? {
        val marker = TAG_PATH
        val at = releaseUrl.indexOf(marker)
        if (at < 0) return null

        val base = releaseUrl.substring(0, at)
        val url = "$base/download/$tag/huishui-${tag.removePrefix("v")}-fast.apk"
        val head = Request.Builder().url(url).head().build()

        return runCatching {
            client.newCall(head).execute().use { if (it.isSuccessful) url else null }
        }.getOrElse { throwable ->
            AppLog.log("Update", "apk probe failed ${throwable::class.simpleName}")
            null
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

        /**
         * The release feed. Served by github.com, not api.github.com, so it is not
         * the budget the API exhausts — and unlike `/releases/latest` it lists
         * pre-releases, which is every release this project has.
         */
        const val FALLBACK_URL = "https://github.com/PanSomeone/798QuSiBa/releases.atom"

        /**
         * The segment that turns a release page into a download: the asset lives at
         * `<repo>/releases/download/<tag>/<file>`, i.e. the page's last path segment
         * replaced. Cutting at `/tag/` keeps `releases` in the base, which is what
         * makes the difference between a 200 and a 404.
         */
        const val TAG_PATH = "/tag/"

        /**
         * A whole release URL plus the tag at its end, matched out of the feed.
         * Reads the URL from the document rather than rebuilding it, so a
         * different host (a test server, a mirror) works unchanged.
         */
        val TAG_LINK = Regex("""https?://[^"'<>\s]+?/releases/tag/([^"'<>\s?&#]+)""")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            // No interceptors. See decision 1 in the class comment: the gateway's
            // client attaches the session token, and this request is not for the
            // gateway.
            .build()
    }
}
