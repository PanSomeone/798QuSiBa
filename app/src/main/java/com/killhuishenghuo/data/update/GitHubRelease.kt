package com.killhuishenghuo.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One release, as GitHub's REST API describes it.
 *
 * Only four fields are modelled out of the dozens it returns, because only four
 * are read: the tag names the version, `html_url` is where a human reads the
 * notes, and the assets are where the APK is. `ignoreUnknownKeys` on the shared
 * [com.killhuishenghuo.core.net.NetworkModule.json] instance is what lets the
 * rest pass through — this model is a lens, not a copy.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("name") val name: String? = null,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    @SerialName("name") val name: String = "",
    @SerialName("size") val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String = "",
)

/**
 * What the update check decided, in the terms the settings row speaks.
 *
 * [apkUrl] is nullable because a release can legitimately have no APK attached —
 * a notes-only tag. In that case the row still offers the release page, which is
 * where a human finds out why.
 */
data class UpdateInfo(
    val tag: String,
    val releaseUrl: String,
    val apkUrl: String?,
)
