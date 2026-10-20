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
        date: String? = null,
    ) = Notice(
        id = id,
        title = title,
        body = "正文",
        minVersion = minVersion,
        until = until,
        date = date,
    )

    private fun feed(vararg notices: Notice) = NoticeFeed(updated = "2026-10-20", notices = notices.toList())

    @Test
    fun `the first notice in the file is the one that shows`() {
        // 顺序就是优先级:写文件的人决定哪条更重要,客户端不比时间戳 —— 时间戳一旦
        // 有时区问题,公告就会在别人的手机上早到或晚到。
        val shown = NoticeRules.visible(feed(notice(id = "first"), notice(id = "second")), "2026-10-20", "1.3.4", emptySet())

        assertEquals("first", shown?.id)
    }

    @Test
    fun `an empty feed shows nothing`() {
        assertNull(NoticeRules.visible(NoticeFeed(), "2026-10-20", "1.3.4", emptySet()))
    }

    @Test
    fun `a notice with no id or no title is skipped`() {
        // 没有 id 就没法记已读 —— 它会永远出现,那就是个错误,不是公告。
        assertNull(NoticeRules.visible(feed(notice(id = "", title = "x")), "2026-10-20", "1.3.4", emptySet()))
        assertNull(NoticeRules.visible(feed(notice(id = "n1", title = "")), "2026-10-20", "1.3.4", emptySet()))
    }

    @Test
    fun `dismissing today's notice does not swallow tomorrow's`() {
        // 记的是 id 不是布尔:否则点过一次「知道了」,以后发什么都看不见了。
        val two = feed(notice(id = "old"), notice(id = "new"))

        assertEquals("new", NoticeRules.visible(two, "2026-10-20", "1.3.4", dismissedIds = setOf("old"))?.id)
    }

    @Test
    fun `dismissing both leaves nothing to show`() {
        // 记一个 id 时两条会互相顶回来:点掉新的 → 旧的冒出来 → 点掉旧的 → 新的又回来。
        // 没有出口的循环看起来就像"公告关不掉",而这个状态必须存在。
        val two = feed(notice(id = "new"), notice(id = "old"))

        assertEquals("old", NoticeRules.visible(two, "2026-10-20", "1.3.6", setOf("new"))?.id)
        assertNull(NoticeRules.visible(two, "2026-10-20", "1.3.6", setOf("new", "old")))
    }

    @Test
    fun `a dismissed notice is gone even when it is the only one`() {
        assertNull(NoticeRules.visible(feed(notice(id = "n1")), "2026-10-20", "1.3.4", dismissedIds = setOf("n1")))
    }

    @Test
    fun `an expired notice disappears on the day after until`() {
        val expiring = feed(notice(id = "n1", until = "2026-10-20"))

        assertEquals("n1", NoticeRules.visible(expiring, "2026-10-20", "1.3.4", emptySet())?.id)
        assertNull("过了当天就不该再出现", NoticeRules.visible(expiring, "2026-10-21", "1.3.4", emptySet()))
    }

    @Test
    fun `a notice for a newer app is hidden from this one`() {
        // 公告里写"新版才有的事"时,旧版看不懂也做不到,不该显示。
        val forNewer = feed(notice(id = "n1", minVersion = "1.4.0"))

        assertNull(NoticeRules.visible(forNewer, "2026-10-20", "1.3.4", emptySet()))
        assertEquals("n1", NoticeRules.visible(forNewer, "2026-10-20", "1.4.0", emptySet())?.id)
        assertEquals("n1", NoticeRules.visible(forNewer, "2026-10-20", "1.4.1", emptySet())?.id)
    }

    @Test
    fun `an unreadable minVersion hides the notice`() {
        // 读不出"是否适用"时按不显示处理:一条看不懂的公告贴在屏幕顶上只会让人多点一下。
        val broken = feed(notice(id = "n1", minVersion = "最新版"))

        assertNull(NoticeRules.visible(broken, "2026-10-20", "1.3.4", emptySet()))
    }

    @Test
    fun `todayIso writes the shape until is compared against`() {
        // 两个字段都是 ISO 字符串,所以字典序就是时间序 —— 这个格式一旦变了,
        // `today > until` 会安静地错。
        assertEquals("2026-10-20", NoticeRules.todayIso(java.time.LocalDate.of(2026, 10, 20)))
    }

    /* ------------------------------------------------------------ 历史公告 */

    @Test
    fun `the history keeps the ones that expired`() {
        // 过期的那条正好就是"历史"。用户点掉之后才想起"刚才那条说了什么"时,
        // 设置里得能找到它 —— 而在那之前它很可能已经过期了。
        val past = feed(
            notice(id = "expired", until = "2026-10-01"),
            notice(id = "live"),
        )

        assertEquals(listOf("expired", "live"), NoticeRules.history(past, "1.3.6").map { it.id })
    }

    @Test
    fun `the history keeps the one that was dismissed`() {
        // 「知道了」的意思是"别贴在顶上",不是"删掉记录"。归档和显示是两件事。
        val one = feed(notice(id = "n1"))

        assertEquals(listOf("n1"), NoticeRules.history(one, "1.3.6").map { it.id })
    }

    @Test
    fun `the history hides what this version cannot read`() {
        // 旧版 app 里不该出现"新版才有的事":它读得懂的才算发生过。
        val mixed = feed(
            notice(id = "old", title = "旧事"),
            notice(id = "future", title = "新版才有", minVersion = "1.9.0"),
            notice(id = "", title = "没有 id"),
            notice(id = "untitled", title = ""),
        )

        assertEquals(listOf("old"), NoticeRules.history(mixed, "1.3.6").map { it.id })
    }

    @Test
    fun `the history keeps the file order`() {
        // 文件顺序就是发布顺序(新的写在上面)。这里不按日期重排:日期是给人看的,
        // 再排一次只会多一个和主界面说法不一致的机会。
        val two = feed(
            notice(id = "newer", date = "2026-10-21"),
            notice(id = "older", date = "2026-09-01"),
        )

        assertEquals(listOf("newer", "older"), NoticeRules.history(two, "1.3.6").map { it.id })
    }

    @Test
    fun `an empty feed has an empty history`() {
        assertEquals(emptyList<Notice>(), NoticeRules.history(NoticeFeed(), "1.3.6"))
    }
}