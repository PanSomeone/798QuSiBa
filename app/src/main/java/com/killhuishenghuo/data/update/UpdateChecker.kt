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
 * Asks what the newest release is, and where to get it.
 *
 * The decisions in here are deliberate, and the first two are about what this
 * class must **not** do.
 *
 * **1. It does not borrow the gateway's OkHttp client.** That client adds
 * `Authorization: <token>` to every request that does not already carry one
 * ([com.killhuishenghuo.core.net.AuthInterceptor]) — correct for the 798 gateway,
 * catastrophic here, because these requests go to GitHub and to Gitee. A session
 * token for someone's dormitory water account must never be handed to either.
 * The client below has no interceptors at all, and a test pins the absence of
 * the header through a real MockWebServer.
 *
 * **2. It reads the release *list*, not `/releases/latest`.** GitHub's "latest"
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
 *
 * **5. The mirror is asked first, and the download comes from it.** A check that
 * finds v1.4.0 and then hands the user a 5 MB file from `objects.githubusercontent.com`
 * at 20 KB/s has not helped anyone standing in a bathroom; the same bytes sit on
 * `gitee.com` for the people this app is for. Two consequences:
 *
 * - the *list* is read from Gitee before GitHub is touched at all
 *   ([newestViaMirror]), because waiting for `api.github.com` to time out and only
 *   then asking Gitee turns "检查更新" into a ten-second stare;
 * - the *download* URL is built from the tag on the mirror first (a guess, hence
 *   the HEAD), and the release's own asset is the fallback.
 *
 * The rule this implies: **a release is published to both shelves**, GitHub and
 * Gitee, in the same run — `tools/publish-github.ps1` and `tools/publish-gitee.ps1`.
 * If only GitHub is updated the check still finds the version (the mirror has no
 * such tag, so it falls through) and the download is slow; if only Gitee is
 * updated, everything is fast.
 */
class UpdateChecker(
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: String = RELEASES_URL,
    private val fallbackEndpoint: String = FALLBACK_URL,
    private val mirrorEndpoint: String = MIRROR_RELEASES_URL,
    private val mirrorDownload: String = MIRROR_DOWNLOAD,
    private val mirrorPage: String = MIRROR_PAGE,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun newest(): Outcome<UpdateInfo> = withContext(ioDispatcher) {
        // 先问 Gitee。这条线路在国内直连、不需要 token、也不占 GitHub 的额度;
        // 反过来先问 api.github.com 的话,在国内常见的结果是等十几秒超时,然后才
        // 轮到 Gitee —— 「检查更新」变成一次罚站。它答不上来(连不上、列表为空、
        // tag 读不了)才回到下面这条老路。
        newestViaMirror()?.let { return@withContext it }
        newestViaGitHub()
    }

    private fun newestViaGitHub(): Outcome<UpdateInfo> {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .build()

        return runCatching {
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
                    // 所以换路:走 github.com 自己的 releases.atom(那是网站,不是 API,
                    // 额度也不共用,不需要 token)。镜像已经在 [newest] 里问过了。
                    return@use otherRoads(
                        failure = AppError.Server(response.code, null),
                        rateLimited = response.code == 403 || response.code == 429,
                    )
                }

                val body = response.body?.string().orEmpty()
                val releases = NetworkModule.json.decodeFromString<List<GitHubRelease>>(body)
                val release = newestOf(releases)

                if (release == null) {
                    AppLog.log("Update", "release list had no usable entry entries=${releases.size}")
                    // GitHub 上没有而 Gitee 上有,是「忘了发布到 GitHub」的情况 ——
                    // 那也是新版本,不该报成「服务器上没有可用的发布版本」。
                    return@use otherRoads(
                        failure = AppError.Unexpected("服务器上没有可用的发布版本"),
                        rateLimited = false,
                    )
                }

                AppLog.log(
                    "Update",
                    "newest tag=${release.tagName} assets=${release.assets.size}",
                )
                Outcome.Success(
                    UpdateInfo(
                        tag = release.tagName,
                        releaseUrl = release.htmlUrl,
                        apkUrl = apkUrlFor(
                            tag = release.tagName,
                            assetUrl = { release.assets.preferredApkUrl() },
                        ),
                    ),
                )
            }
        }.getOrElse { throwable ->
            AppLog.error("Update", throwable, "release lookup failed")
            // 网络层直接失败(超时、DNS、被墙)时,atom 那条路在同一个国家、同一
            // 条链路上,基本同样不通,镜像也已经试过 —— 没有别的路可走了。
            Outcome.Failure(ApiResultMapper.toAppError(throwable))
        }
    }

    /**
     * The mirror's release list, which needs no token and no API budget of
     * GitHub's — and, unlike anything on GitHub, is reachable from a dormitory in
     * China on a normal evening.
     *
     * Returns null when this road is not usable (unreachable, not a list, no tag
     * this app can read); the caller then keeps whatever error it had.
     */
    private fun newestViaMirror(): Outcome<UpdateInfo>? {
        val request = Request.Builder().url(mirrorEndpoint).build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.log("Update", "mirror lookup failed http=${response.code}")
                    return@use null
                }

                val body = response.body?.string().orEmpty()
                val releases = NetworkModule.json.decodeFromString<List<GitHubRelease>>(body)
                val release = newestOf(releases)
                if (release == null) {
                    AppLog.log(
                        "Update",
                        "mirror had no usable entry entries=${releases.size}",
                    )
                    return@use null
                }

                AppLog.log(
                    "Update",
                    "mirror tag=${release.tagName} assets=${release.assets.size}",
                )
                Outcome.Success(
                    UpdateInfo(
                        tag = release.tagName,
                        releaseUrl = mirrorPage + release.tagName,
                        // 镜像自己列出来的资产是事实,不是猜测,所以不再 HEAD 探一次。
                        apkUrl = release.assets.preferredApkUrl(),
                    ),
                )
            }
        }.getOrElse { throwable ->
            AppLog.log("Update", "mirror lookup failed ${throwable::class.simpleName}")
            null
        }
    }

    /**
     * What to do when GitHub's API did not answer usefully: the feed, and nothing
     * else — [newest] already asked the mirror before it ever came here.
     *
     * [rateLimited] keeps the sentence GitHub's 403 deserves — "GitHub 这边限流了"
     * — for the case where every road is shut, instead of the generic "server
     * refused (code 403)", which tells the user nothing they can act on.
     */
    private fun otherRoads(failure: AppError, rateLimited: Boolean): Outcome<UpdateInfo> {
        if (rateLimited) return newestViaAtom(AppError.Unexpected(RATE_LIMIT_SENTENCE))
        return Outcome.Failure(failure)
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
                        apkUrl = apkUrlFor(
                            tag = tag,
                            assetUrl = { guessAssetUrl(releaseUrl, tag) },
                        ),
                    ),
                )
            }
        }.getOrElse { throwable ->
            AppLog.error("Update", throwable, "atom fallback failed")
            Outcome.Failure(reason)
        }
    }

    /**
     * Where to download [tag]: the mirror first, then whatever the release itself
     * offered.
     *
     * [assetUrl] is a URL the release *listed* (trusted, no probe) or one built
     * from the project's naming convention (a guess, probed). It is asked for
     * only when the mirror has nothing — as a lambda, so that walking the
     * fallback does not spend a request (or, in a test, consume a queued
     * response) before the mirror has had its say.
     *
     * The mirror URL is always a guess — `<tag>` is read from a list that says
     * nothing about which files the mirror actually holds — so it is probed with a
     * HEAD, and a 404 simply means "this shelf has not been stocked yet", not "no
     * download".
     */
    private fun apkUrlFor(tag: String, assetUrl: () -> String?): String? {
        val mirror = "$mirrorDownload/$tag/huishui-${tag.removePrefix("v")}-fast.apk"
        return if (answers(mirror)) {
            AppLog.log("Update", "download from the mirror tag=$tag")
            mirror
        } else {
            assetUrl()
        }
    }

    /**
     * Where a direct download would be on the release's own host, if it is there.
     *
     * The asset name is this project's own convention
     * (`huishui-<version>-fast.apk`, the shrunk build that installs over the
     * previous one), so the URL can be constructed without asking the API — but a
     * constructed URL is a guess until something answers, and a guess that 404s
     * is worse than the release page.
     */
    private fun guessAssetUrl(releaseUrl: String, tag: String): String? {
        val at = releaseUrl.indexOf(TAG_PATH)
        if (at < 0) return null

        val base = releaseUrl.substring(0, at)
        val url = "$base/download/$tag/huishui-${tag.removePrefix("v")}-fast.apk"
        return if (answers(url)) url else null
    }

    /** A HEAD, and a yes or no. Redirects are followed, so a CDN in front of a
     *  release still counts as the file being there. */
    private fun answers(url: String): Boolean {
        val head = Request.Builder().url(url).head().build()
        return runCatching {
            client.newCall(head).execute().use { it.isSuccessful }
        }.getOrElse { throwable ->
            AppLog.log("Update", "apk probe failed ${throwable::class.simpleName}")
            false
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
         * The mirror's own release list (Gitee API v5). Public repositories are
         * readable without a token; verified live 2026-10-21: 200, one release,
         * four assets each with `browser_download_url`.
         */
        const val MIRROR_RELEASES_URL =
            "https://gitee.com/api/v5/repos/pansomeone/798QuSiBa/releases?per_page=10"

        /** `<mirror>/<tag>/<file>` — the same shape as GitHub's download path. */
        const val MIRROR_DOWNLOAD =
            "https://gitee.com/pansomeone/798QuSiBa/releases/download"

        /** Where a human reads the notes on the mirror. */
        const val MIRROR_PAGE =
            "https://gitee.com/pansomeone/798QuSiBa/releases/tag/"

        const val RATE_LIMIT_SENTENCE = "GitHub 这边限流了，过一会儿再看"

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

        /**
         * The APK to install: `-fast.apk` first, because that is the shrunk build
         * that installs over the previous version. The second `firstOrNull` catches
         * a release that renamed the file, and being able to download *something*
         * beats a row that only offers a page.
         */
        fun List<GitHubAsset>.preferredApkUrl(): String? =
            (firstOrNull { it.name.endsWith("-fast.apk", ignoreCase = true) }
                ?: firstOrNull { it.name.endsWith(".apk", ignoreCase = true) })
                ?.downloadUrl
                ?.takeIf { it.isNotBlank() }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            // No interceptors. See decision 1 in the class comment: the gateway's
            // client attaches the session token, and this request is not for the
            // gateway.
            .build()
    }
}