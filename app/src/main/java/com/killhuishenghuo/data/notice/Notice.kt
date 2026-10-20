package com.killhuishenghuo.data.notice

import com.killhuishenghuo.data.update.Version
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * 一条公告。
 *
 * 和「检查更新」一样,它是从本仓库的静态文件读的,不是从服务器:
 * `https://pansomeone.github.io/798QuSiBa/notice.json`。这么做有三个理由,每条都
 * 和这个 app 的约束直接相关:
 *
 * **1. 没有服务器。** 公告必须能由一个人改一行字就发出去,而不是先部署一个后端。
 * GitHub Pages 就是那个地方,而网页端读的是**同一个文件**,所以两端永远不会说法
 * 不一致。
 *
 * **2. 不走 `api.github.com`。** 那条线路的未鉴权额度是每小时 60 次、按出口 IP 算,
 * 宿舍一栋楼常常共用一两个 IP —— 检查更新已经因为 403 失败过一次,公告不该再踩一遍。
 * 静态文件没有额度。
 *
 * **3. 读失败就是没有公告。** 没有 `notices`、文件没发布、没网,一律静默:一条读不出
 * 来的公告不该变成一个弹窗。
 */
@Serializable
data class Notice(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("body") val body: String = "",
    /** 详情链接,可以是空串。非空时详情页多一个「查看详情」。 */
    @SerialName("url") val url: String = "",
    /**
     * 只发给这个版本及以上的 app。
     *
     * 用来避免"新版才有的事"提前出现在旧版上——旧版看不懂,也没法照做。null 表示
     * 不限制。
     */
    @SerialName("minVersion") val minVersion: String? = null,
    /** `yyyy-MM-dd`,过了这一天就不再显示。null 表示长期有效。 */
    @SerialName("until") val until: String? = null,
)

/** `notice.json` 的整体形状。数组而不是单体,因为「发一条」和「现在正在发哪条」是两件事。 */
@Serializable
data class NoticeFeed(
    @SerialName("updated") val updated: String = "",
    @SerialName("notices") val notices: List<Notice> = emptyList(),
)

/**
 * 该显示哪一条。全是纯函数,所以这些规则能被 JVM 测试直接钉住,不用起界面。
 */
object NoticeRules {

    /**
     * 第一条该显示的公告,没有就 null。
     *
     * 「第一条」而不是「最新一条」:`notice.json` 的顺序就是优先顺序,写文件的人
     * 决定哪条更重要,不需要客户端去比较时间戳——时间戳一旦有时区问题,公告就会
     * 在别人的手机上早到或晚到。
     */
    fun visible(
        feed: NoticeFeed,
        today: String,
        versionName: String,
        dismissedId: String?,
    ): Notice? = feed.notices.firstOrNull { it.isVisible(today, versionName, dismissedId) }

    /** 今天,按 ISO 写(`2026-10-20`)。ISO 日期字符串按字典序就是按时间序。 */
    fun todayIso(today: LocalDate = LocalDate.now()): String = today.toString()

    private fun Notice.isVisible(today: String, versionName: String, dismissedId: String?): Boolean {
        if (id.isBlank() || title.isBlank()) return false
        if (id == dismissedId) return false

        if (!until.isNullOrBlank() && until.length >= 10 && today > until) return false

        if (!minVersion.isNullOrBlank()) {
            // 读不出版本号时按「不显示」处理:一条看不懂是否适用的公告,贴在屏幕顶上
            // 只会让人多点一下。
            if ((Version.compare(versionName, minVersion) ?: -1) < 0) return false
        }

        return true
    }
}