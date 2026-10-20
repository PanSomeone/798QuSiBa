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
    /**
     * 写这条公告的日子,`yyyy-MM-dd`,只用于「历史公告」里那行小字。
     *
     * 不参与排序,也不参与"该显示哪条":顺序仍然是文件顺序(见 [NoticeRules.visible])。
     * 它是给人看的,所以允许为空——空着就不显示那一行,而不是编一个日期。
     */
    @SerialName("date") val date: String? = null,
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
     *
     * [dismissedIds] 是集合而不是单个 id:文件里同时有两条公告时,只记最后点掉的那
     * 一条会让两条互相顶回来(点掉新的 → 旧的冒出来 → 点掉旧的 → 新的又回来),而
     * 这个循环没有出口。集合才有"都看过"这个状态。
     */
    fun visible(
        feed: NoticeFeed,
        today: String,
        versionName: String,
        dismissedIds: Set<String>,
    ): Notice? = feed.notices.firstOrNull { it.isVisible(today, versionName, dismissedIds) }

    /** 今天,按 ISO 写(`2026-10-20`)。ISO 日期字符串按字典序就是按时间序。 */
    fun todayIso(today: LocalDate = LocalDate.now()): String = today.toString()

    /**
     * 设置里「历史公告」那一块:这个版本读得懂的所有公告,**含已经过期的**。
     *
     * 过期的正好就是"历史",所以这里不看 [Notice.until];不看 dismissed 是因为
     * 「知道了」只是想让它从主屏顶端消失,不是想让它从记录里消失——用户点掉之后
     * 又想起"刚才那条说了什么",设置里得能找回来。
     *
     * 顺序沿用文件顺序(新的在上面),不按 [Notice.date] 重排:公告的顺序是写文件的
     * 人定的,日期是给人看的,两者在文件里一致,在这里再排一次只会多一种出错方式。
     */
    fun history(feed: NoticeFeed, versionName: String): List<Notice> =
        feed.notices.filter { it.isReadableBy(versionName) }

    private fun Notice.isVisible(today: String, versionName: String, dismissedIds: Set<String>): Boolean {
        if (!isReadableBy(versionName)) return false
        if (id in dismissedIds) return false

        if (!until.isNullOrBlank() && until.length >= 10 && today > until) return false

        return true
    }

    /** 这个版本读得懂的公告:有 id、有标题、版本够新。和"现在该显示哪条"无关。 */
    private fun Notice.isReadableBy(versionName: String): Boolean {
        if (id.isBlank() || title.isBlank()) return false

        if (!minVersion.isNullOrBlank()) {
            // 读不出版本号时按「不显示」处理:一条看不懂是否适用的公告,贴在屏幕顶上
            // 只会让人多点一下。
            if ((Version.compare(versionName, minVersion) ?: -1) < 0) return false
        }

        return true
    }
}