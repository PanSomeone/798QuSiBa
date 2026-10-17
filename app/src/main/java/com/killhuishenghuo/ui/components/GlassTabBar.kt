package com.killhuishenghuo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.killhuishenghuo.ui.theme.Motion
import com.killhuishenghuo.ui.theme.Radii
import com.killhuishenghuo.ui.theme.Spacing
import com.killhuishenghuo.ui.theme.colors

/**
 * The app's two destinations, as a floating bar.
 *
 * Hand-rolled rather than `NavigationBar`: Material's version is a full-width
 * opaque strip welded to the bottom edge, and the whole request here was for
 * something that floats. It also insists on three to five items, and this app
 * has two — the extra destinations a nav bar normally implies do not exist.
 *
 * Both labels are always visible. An icon-only bar would be smaller and quieter,
 * but a gear is only obviously "settings" once you already know it is, and the
 * user of this app is standing in a bathroom at 1am, not learning an icon set.
 */
@Composable
fun GlassTabBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
            .padding(bottom = bottomInset + Spacing.sm),
        shape = PillShape,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.xxs),
        ) {
            // Every tab is the same width, so the pill needs one number: how far
            // along the row the selected one starts. Derived from the measured
            // width rather than from a hardcoded fraction, because the row is
            // `weight(1f)`-based and a "half the bar" guess would drift the moment
            // a third tab or a wider gutter appeared.
            val tabWidth = tabWidthFor(maxWidth, MainTab.entries.size)
            val index = MainTab.entries.indexOf(selected).coerceAtLeast(0)

            // Swapped for a spring, from a `tween`. `Motion.standardMillis` is a
            // duration, and a tween on a distance is the one place where a
            // duration is the wrong control: it makes a two-tab hop and a
            // hypothetical ten-tab hop take the same time, so the near hop reads
            // as sluggish and the far one as a teleport. A spring is specified by
            // how it feels — stiffness and damping — and arrives in whatever time
            // that distance needs. `Motion.spring()` deliberately has no bounce:
            // overshoot on a selection indicator reads as a wobble, not as
            // energy.
            val offset by animateDpAsState(
                targetValue = tabPillOffset(maxWidth, MainTab.entries.size, index),
                animationSpec = Motion.spring(),
                label = "tabHighlightOffset",
            )

            Box(
                modifier = Modifier
                    .offset(x = offset)
                    .width(tabWidth)
                    .height(TabHeight)
                    .background(colors.water.copy(alpha = 0.18f), PillShape),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                MainTab.entries.forEach { tab ->
                    TabButton(
                        tab = tab,
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        modifier = Modifier
                            .weight(1f)
                            .height(TabHeight),
                    )
                }
            }
        }
    }
}

/**
 * Both the pill and the tab have to agree on this, or the fill misses the row.
 *
 * 48dp was the Material floor, and it read as "a strip", not as something to press —
 * 这条栏是给一只手、可能是湿着的手用的,而它要按的只有两个目标。56dp 让每一格
 * 的触摸区域从「刚好达标」变成「不用瞄准」,代价只是栏高多了 8dp。
 */
private val TabHeight = 56.dp

/**
 * The bar's own shape, hoisted.
 *
 * A `RoundedCornerShape(...)` written at the call site is a new object every
 * time that lambda runs, and a changed `Shape` argument is by itself enough to
 * rebuild the shadow and clip layers under the glass. One object, built once.
 */
private val PillShape = RoundedCornerShape(Radii.pill)

/**
 * How wide one tab is, once the gutters are taken out.
 *
 * `coerceAtLeast(0.dp)` because the measured width is 0 on the first frame of a
 * preview, and a negative width crashes the layout rather than showing nothing.
 */
internal fun tabWidthFor(contentWidth: Dp, tabCount: Int): Dp {
    if (tabCount <= 0) return 0.dp
    val gaps = Spacing.xxs * (tabCount - 1)
    return ((contentWidth - gaps) / tabCount).coerceAtLeast(0.dp)
}

/**
 * Where the selected tab's highlight starts, measured from the row's own edge.
 *
 * A pure function of the measured width rather than a constant, and separate
 * from the composable so it can be asserted: a pill that lands a few dp off is
 * not a crash or a failed build, it is a thing a person notices and nobody's
 * tests catch. The row is `weight(1f)`-based, so the only honest way to know
 * where a tab starts is to ask how wide the row actually is.
 */
internal fun tabPillOffset(contentWidth: Dp, tabCount: Int, index: Int): Dp {
    if (tabCount <= 0) return 0.dp
    // Guarded separately: with no measured width every tab is 0dp wide, but the
    // gutters still add up, so the *second* tab would report a 4dp offset and
    // the pill would flash as a sliver at the wrong end of an empty bar.
    if (contentWidth <= 0.dp) return 0.dp
    val clamped = index.coerceIn(0, tabCount - 1)
    return tabWidthFor(contentWidth, tabCount) * clamped + Spacing.xxs * clamped
}

/** The two places the app can be. */
enum class MainTab(val label: String, val icon: ImageVector) {
    USE("使用", Icons.Filled.Home),
    SETTINGS("设置", Icons.Filled.Settings),
}

@Composable
private fun TabButton(
    tab: MainTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = colors
    // The ripple is turned off rather than restyled: it draws a grey wash that
    // fights a translucent fill. The selection itself moves under the finger,
    // which is the feedback that matters here.
    val interaction = remember { MutableInteractionSource() }

    // Delayed, so the tint waits for the finger's own movement to finish before
    // it changes. Matching the two timings exactly read as slightly wrong: the
    // label arrived before the pill it was supposed to be sitting on.
    val tint by animateColorAsState(
        targetValue = if (selected) colors.water else colors.inkMuted,
        animationSpec = tween(Motion.standardMillis, delayMillis = Motion.labelDelayMillis),
        label = "tabTint",
    )

    Row(
        modifier = modifier
            .height(TabHeight)
            .selectable(
                selected = selected,
                role = Role.Tab,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )

        Spacer(Modifier.width(Spacing.xs))

        Text(
            text = tab.label,
            style = MaterialTheme.typography.titleSmall,
            // 15sp instead of the scale's 14: the bar got taller so the label could
            // grow with it, and a label that grows alone reads as a bigger bar.
            fontSize = 15.sp,
            color = tint,
        )
    }
}

/**
 * The room a screen must leave at its bottom so the floating bar never covers
 * real content.
 *
 * Exported rather than guessed at each call site: the bar's height and its
 * bottom inset live in one place, and a screen that pads by "about the right
 * amount" is a screen where the last row is unreachable on somebody's phone.
 *
 * [bottomInset] can be supplied by a caller that has already read
 * `WindowInsets.navigationBars` — the bar itself has. Each read is a
 * `CompositionLocal` lookup that walks the insets tree, and this was being
 * asked three times per bar composition to answer a question whose answer had
 * not changed.
 */
@Composable
fun glassTabBarSpace(bottomInset: Dp? = null): Dp {
    val inset = bottomInset
        ?: WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return TabHeight + Spacing.xxs * 2 + Spacing.sm + inset + Spacing.md
}
