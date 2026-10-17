package com.killhuishenghuo.widget

import com.killhuishenghuo.data.model.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面上那张卡「该说什么」,以及它「该长多大」。
 *
 * 这个类的 write/read 要 Context,所以整体不是 JVM 可测的;但真正有判断的几个函数
 * 不是 —— 而它们恰好是唯一会做错事的地方。
 */
class WidgetSnapshotTest {

    // ---------------------------------------------------------------- 登录状态

    @Test
    fun `a signed-in user is never told to log in`() {
        // 这就是用户报的那个 bug:组件刚加到桌面上时,缓存里是默认值 needLogin=true,
        // 于是明明登录着,卡片上写着「还没有登录」。缓存会过期,会话不会。
        val staleCache = WidgetSnapshot(
            deviceName = "",
            state = "",
            running = false,
            needLogin = true,
        )

        val resolved = WidgetSnapshot.resolve(staleCache, signedIn = true)

        assertFalse("a live session must win over a stale cache", resolved.needLogin)
        assertTrue(resolved.deviceName.isNotBlank())
    }

    @Test
    fun `a stale cache keeps the device name it did have`() {
        val staleCache = WidgetSnapshot(
            deviceName = "荟园13栋134",
            state = "",
            running = false,
            needLogin = true,
        )

        assertEquals("荟园13栋134", WidgetSnapshot.resolve(staleCache, signedIn = true).deviceName)
    }

    @Test
    fun `a fresh cache is used as it is`() {
        val fresh = WidgetSnapshot(
            deviceName = "荟园13栋134",
            state = "热水已开",
            running = true,
            needLogin = false,
        )

        assertEquals(fresh, WidgetSnapshot.resolve(fresh, signedIn = true))
    }

    @Test
    fun `without a session the card asks for a login`() {
        val fresh = WidgetSnapshot(
            deviceName = "荟园13栋134",
            state = "热水已开",
            running = true,
            needLogin = false,
        )

        val resolved = WidgetSnapshot.resolve(fresh, signedIn = false)

        assertTrue(resolved.needLogin)
        assertFalse(resolved.running)
    }

    // ------------------------------------------------------------------ 尺寸

    @Test
    fun `one cell is a switch, two cells wide is still a switch`() {
        // 一列一格:40–70dp 是启动器实际报的值。
        assertEquals(WidgetSize.TINY, widgetSizeOf(minWidthDp = 70, minHeightDp = 70))
        // 两列一格:宽度过了 110,高度还是一行。
        assertEquals(WidgetSize.WIDE, widgetSizeOf(minWidthDp = 160, minHeightDp = 70))
        // 两列两行:这才配得上设备名。
        assertEquals(WidgetSize.FULL, widgetSizeOf(minWidthDp = 160, minHeightDp = 160))
    }

    @Test
    fun `a tall narrow cell stays a switch`() {
        // 1x2:宽 70、高 160。宽度说了算 —— 它还是"一列",塞不下设备名。
        assertEquals(WidgetSize.TINY, widgetSizeOf(minWidthDp = 70, minHeightDp = 160))
    }

    @Test
    fun `the biggest widget keeps the full card`() {
        // 4x2 和 2x2 是同一副样子:多出来的宽度由布局自己填。
        assertEquals(WidgetSize.FULL, widgetSizeOf(minWidthDp = 300, minHeightDp = 160))
    }

    // -------------------------------------------------------------- 失败与空态

    @Test
    fun `a dead session sends the card back to the login row`() {
        // 会话失效时最要紧的不是那句话,而是按钮的**去处**:它必须变成「去登录」,
        // 否则用户按下去只会再失败一次。
        val snapshot = WidgetSnapshot.failed(AppError.Unauthorized, deviceId = "865137068739782")

        assertTrue(snapshot.needLogin)
        assertFalse(snapshot.running)
    }

    @Test
    fun `an ordinary failure keeps the toggle reachable`() {
        // 网络断开不是登录问题:把按钮变成「去登录」会让一个只是没信号的人去重新登录。
        val snapshot = WidgetSnapshot.failed(AppError.Offline, deviceId = "865137068739782")

        assertFalse(snapshot.needLogin)
        assertEquals("865137068739782", snapshot.deviceName)
    }

    @Test
    fun `the first-run card asks for a login and nothing else`() {
        val snapshot = WidgetSnapshot.needLogin()

        assertTrue(snapshot.needLogin)
        assertFalse(snapshot.running)
    }

    @Test
    fun `the no-device card does not ask for a login`() {
        // 这两种空状态的责任方不同:一个要用户去登录,一个要用户去绑设备。
        assertFalse(WidgetSnapshot.noDevice().needLogin)
    }
}
