package com.killhuishenghuo.data.notice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 该显示哪条公告。
 *
 * 这些用例全是"什么时候**不该**出现":主界面上唯一一个按钮旁边多出来的每一样东西,
 * 都从打水这件事里拿走了一点注意力。公告的出现条件是它能存在的全部理由。
 */
class NoticeRulesTest {

    private fun notice(
        id: String = "n1",
        title: String = "今天停水",
        minVersion: String? = null,
        until: String? = null,
    ) = Notice(id = id, title = title, body = "正文", minVersion = minVersion, until = until)

    private fun feed(vararg notices: Notice) = NoticeFeed(updated = "2026-10-20", notices = notices.toList())

    @Test
    fun `the first notice in the file is the one that shows`() {
        // 顺序就是优先级:写文件的人决定哪条更重要,客户端不比时间戳 —— 时间戳一旦
        // 有时区问题,公告就会在别人的手机上早到或晚到。
        val shown = NoticeRules.visible(feed(notice(id = "first"), notice(id = "second")), "2026-10-20", "1.3.4", null)

        assertEquals("first", shown?.id)
    }

    @Test
    fun `an empty feed shows nothing`() {
        assertNull(NoticeRules.visible(NoticeFeed(), "2026-10-20", "1.3.4", null))
    }

    @Test
    fun `a notice with no id or no title is skipped`() {
        // 没有 id 就没法记已读 —— 它会永远出现,那就是个错误,不是公告。
        assertNull(NoticeRules.visible(feed(notice(id = "", title = "x")), "2026-10-20", "1.3.4", null))
        assertNull(NoticeRules.visible(feed(notice(id = "n1", title = "")), "2026-10-20", "1.3.4", null))
    }

    @Test
    fun `dismissing today's notice does not swallow tomorrow's`() {
        // 记的是 id 不是布尔:否则点过一次「知道了」,以后发什么都看不见了。
        val two = feed(notice(id = "old"), notice(id = "new"))

        assertEquals("new", NoticeRules.visible(two, "2026-10-20", "1.3.4", dismissedId = "old")?.id)
    }

    @Test
    fun `a dismissed notice is gone even when it is the only one`() {
        assertNull(NoticeRules.visible(feed(notice(id = "n1")), "2026-10-20", "1.3.4", dismissedId = "n1"))
    }

    @Test
    fun `an expired notice disappears on the day after until`() {
        val expiring = feed(notice(id = "n1", until = "2026-10-20"))

        assertEquals("n1", NoticeRules.visible(expiring, "2026-10-20", "1.3.4", null)?.id)
        assertNull("过了当天就不该再出现", NoticeRules.visible(expiring, "2026-10-21", "1.3.4", null))
    }

    @Test
    fun `a notice for a newer app is hidden from this one`() {
        // 公告里写"新版才有的事"时,旧版看不懂也做不到,不该显示。
        val forNewer = feed(notice(id = "n1", minVersion = "1.4.0"))

        assertNull(NoticeRules.visible(forNewer, "2026-10-20", "1.3.4", null))
        assertEquals("n1", NoticeRules.visible(forNewer, "2026-10-20", "1.4.0", null)?.id)
        assertEquals("n1", NoticeRules.visible(forNewer, "2026-10-20", "1.4.1", null)?.id)
    }

    @Test
    fun `an unreadable minVersion hides the notice`() {
        // 读不出"是否适用"时按不显示处理:一条看不懂的公告贴在屏幕顶上只会让人多点一下。
        val broken = feed(notice(id = "n1", minVersion = "最新版"))

        assertNull(NoticeRules.visible(broken, "2026-10-20", "1.3.4", null))
    }

    @Test
    fun `todayIso writes the shape until is compared against`() {
        // 两个字段都是 ISO 字符串,所以字典序就是时间序 —— 这个格式一旦变了,
        // `today > until` 会安静地错。
        assertEquals("2026-10-20", NoticeRules.todayIso(java.time.LocalDate.of(2026, 10, 20)))
    }
}