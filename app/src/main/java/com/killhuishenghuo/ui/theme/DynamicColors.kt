package com.killhuishenghuo.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

/**
 * 风格 D：玻璃 + 取色。
 *
 * 取色有边界，而且这个边界是这一版最需要写下来的决定：**只取「深浅与层级」，
 * 不取「色相」。**
 *
 * 理由不是审美而是语义。这个 app 的颜色是有意思的：水蓝 = 水/正在出水，
 * 热橙 = 水关着时那个唯一能按的东西。如果整屏跟着壁纸一起变色，那壁纸是橙色的人
 * 会得到一个「开水的按钮也是橙色」的界面 —— 两个语义撞在一起，而撞掉的恰好是
 * 「什么时候水在流」这条唯一的视觉线索。
 *
 * 所以：surface / raised / sunken / hairline / ink / glassFill 这一组「中性色」全部
 * 交给系统的动态色板，水蓝、热橙、暖黄、绿、红这一组「语义色」一个都不动。
 * 屏幕会跟着壁纸的明暗与色温变，但「该看哪儿」永远不变。
 *
 * API 31 以下没有动态色，直接返回手工调过的调色板；浅色那套也保留手工值作为
 * 取不到动态色时的回落。
 */
@Composable
fun rememberKhsColors(dark: Boolean, dynamic: Boolean = true): KhsColors {
    val base = if (dark) DarkKhsColors else LightKhsColors
    if (!dynamic || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return base

    val context = LocalContext.current
    val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

    // 键是色板本身：系统换壁纸 → 色板变化 → 这里重算。`dark` 已经体现在 scheme 里，
    // 但它同时决定用哪一套 base，所以两个都进键。
    return remember(scheme, base) { base.tintedBy(scheme) }
}

/**
 * 把一张动态色板的中性色装进本项目的调色板，语义色原样保留。
 *
 * `inkFaint` 没有 Material 的对应项 —— 色板只有 `onSurface` 和 `onSurfaceVariant`
 * 两级文字。所以第三级用 `onSurfaceVariant` 往 `surface` 方向混合 35% 得到：混合
 * 比「调低不透明度」可预测，因为它不依赖底下是什么，而这个 app 的底并不总是页面本身
 * （玻璃条、卡片都有自己的填充）。
 */
private fun KhsColors.tintedBy(scheme: ColorScheme): KhsColors = copy(
    surface = scheme.surface,
    surfaceRaised = scheme.surfaceContainerHigh,
    surfaceSunken = scheme.surfaceContainerLowest,
    hairline = scheme.outlineVariant,
    hairlineStrong = scheme.outline,
    ink = scheme.onSurface,
    inkMuted = scheme.onSurfaceVariant,
    inkFaint = lerp(scheme.onSurfaceVariant, scheme.surface, 0.35f),
    // 玻璃条：原来是「按明度抬起来的不透明色」，动态色板里最接近的层级是
    // surfaceContainer。仍然不透明 —— 理由见 DarkKhsColors.glassFill 的长注释，
    // 那是量出来的，不是偏好。
    glassFill = scheme.surfaceContainer,
    glassLit = if (isDark) Color(0x0DFFFFFF) else Color(0x14FFFFFF),
    glassEdge = if (isDark) Color(0x1FFFFFFF) else Color(0x12000000),
    isDark = isDark,
)
