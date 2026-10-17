package com.killhuishenghuo.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.killhuishenghuo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 小组件的布局真的能被系统展开吗,而且它长什么样。
 *
 * 这是组件最常见的一类故障:`RemoteViews` 只认系统认识的那几类视图和属性,写了一个
 * 它不认识的东西,构建照样过、单元测试照样过,而桌面上的那张卡是空的、或者干脆不出现。
 * 唯一能提前抓住它的办法就是**真的 `apply()` 一次**。
 *
 * **它调的是 [widgetViews],也就是生产代码本身,不是这里复刻的一份。** 第一版在测试
 * 文件里重写了一遍那串 `set…`,漏了 `needLogin` 分支,报了一个假失败 —— 复制出来的
 * 测试测的是复制品。
 *
 * 除了断言,它还把每一副样子**画成 PNG 写到应用内部目录**:启动器多难驱动已经试过了,
 * 而「把它画出来看一眼」不需要经过启动器。取文件的命令:
 *
 * ```
 * adb exec-out run-as com.killhuishenghuo cat files/widget/widget-tiny.png > tiny.png
 * ```
 *
 * 仪器测试而不是 JVM 测试:inflate 要 Resources 和真实的 View 系统。
 *
 * 方法名不能带空格(不能用反引号)。minSdk 26 对应的 DEX 版本早于 040,带空格的方法名
 * 会在 dex 阶段失败,报错还是关于 SimpleName 的。
 */
@RunWith(AndroidJUnit4::class)
class WaterWidgetLayoutTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun inflate(state: WidgetSnapshot, size: WidgetSize): View =
        widgetViews(context, state, size).apply(context, FrameLayout(context))

    private fun textOf(view: View, id: Int): String =
        view.findViewById<TextView>(id).text.toString()

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** 按实际格子的比例画一张 PNG。 */
    private fun render(name: String, state: WidgetSnapshot, size: WidgetSize, w: Int, h: Int): File {
        val view = inflate(state, size)
        val width = dp(w)
        val height = dp(h)
        view.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 桌面底色:圆角外面是透明的,不铺一层就看不出圆角。
        canvas.drawColor(PAGE_COLOR)
        view.draw(canvas)

        val dir = File(context.filesDir, "widget").apply { mkdirs() }
        val file = File(dir, "widget-$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("RENDERED ${file.absolutePath} (${file.length()} B)")
        return file
    }

    // ------------------------------------------------------------- 2x2 那张卡

    @Test
    fun fullLayoutInflatesWithTheWordmark() {
        val view = inflate(
            WidgetSnapshot("荟园13栋134", "热水已关", running = false, needLogin = false),
            WidgetSize.FULL,
        )

        assertNotNull(view)
        assertEquals("惠生活 去死吧", textOf(view, R.id.widget_title))
        assertEquals("798 748", textOf(view, R.id.widget_pun))
        assertEquals("荟园13栋134", textOf(view, R.id.widget_device))
        assertEquals("一键启动热水", textOf(view, R.id.widget_button))
    }

    @Test
    fun fullLayoutShowsTheRunningState() {
        val view = inflate(
            WidgetSnapshot("荟园13栋134", "热水已开", running = true, needLogin = false),
            WidgetSize.FULL,
        )

        assertEquals("热水已开", textOf(view, R.id.widget_state))
        assertEquals("关闭热水", textOf(view, R.id.widget_button))
    }

    // ----------------------------------------------------------- 1x1 / 2x1 开关

    @Test
    fun tinySwitchIsOneWord() {
        // 1x1 只有一格:一个「开」字,没有标题、没有设备名。
        val view = inflate(
            WidgetSnapshot("荟园13栋134", "热水已关", running = false, needLogin = false),
            WidgetSize.TINY,
        )

        assertEquals("开", textOf(view, R.id.widget_switch))
    }

    @Test
    fun wideSwitchHasRoomForFourCharacters() {
        val view = inflate(
            WidgetSnapshot("荟园13栋134", "热水已开", running = true, needLogin = false),
            WidgetSize.WIDE,
        )

        assertEquals("关热水", textOf(view, R.id.widget_switch))
    }

    @Test
    fun switchesAskForALoginWhenThereIsNoSession() {
        val view = inflate(WidgetSnapshot.needLogin(), WidgetSize.WIDE)

        assertEquals("登录", textOf(view, R.id.widget_switch))
    }

    // ------------------------------------------------------------------- 渲染

    @Test
    fun everySizeRendersToPng() {
        val files = listOf(
            render("tiny-off", WidgetSnapshot("荟园13栋134", "热水已关", false, false), WidgetSize.TINY, 70, 70),
            render("tiny-on", WidgetSnapshot("荟园13栋134", "热水已开", true, false), WidgetSize.TINY, 70, 70),
            render("wide-off", WidgetSnapshot("荟园13栋134", "热水已关", false, false), WidgetSize.WIDE, 160, 70),
            render("wide-on", WidgetSnapshot("荟园13栋134", "热水已开", true, false), WidgetSize.WIDE, 160, 70),
            render("full-off", WidgetSnapshot("荟园13栋134", "热水已关", false, false), WidgetSize.FULL, 320, 150),
            render("full-on", WidgetSnapshot("荟园13栋134", "热水已开", true, false), WidgetSize.FULL, 320, 150),
            render("full-login", WidgetSnapshot.needLogin(), WidgetSize.FULL, 320, 150),
        )

        for (file in files) {
            assertTrue("${file.name} was not written", file.exists() && file.length() > 0)
        }
    }

    private companion object {
        /** 页面底色,和主界面同一个值。 */
        const val PAGE_COLOR = 0xFF070B12.toInt()
    }
}
