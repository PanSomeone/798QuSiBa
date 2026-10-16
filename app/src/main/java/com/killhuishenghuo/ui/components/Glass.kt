package com.killhuishenghuo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.killhuishenghuo.ui.theme.colors

/**
 * A surface that floats above the content instead of sitting in the layout.
 *
 * Three things make it read as lifted, and all three are doing work:
 *
 * 1. **A translucent fill, brighter at the top.** Glass catches light at its
 *    upper edge. The gradient is doing what the eye is looking for; a flat
 *    translucent rectangle looks like a hole, not a pane.
 * 2. **A drop shadow.** This is the part that actually says "above" — the fill
 *    only says "not opaque".
 * 3. **One hairline edge.** A real bevel has a highlight along it. Without the
 *    edge the shape dissolves into whatever it is covering.
 *
 * ## What this deliberately does *not* do
 *
 * No backdrop blur. iOS gets frosted glass by blurring the pixels behind the
 * pane, and Compose has no such modifier — `Modifier.blur` blurs a composable's
 * *own* content, so applying it here would soften the pane's background rather
 * than the content it covers. Faking it means drawing the covered content a
 * second time into a clipped, blurred layer, and the payoff is a blurred
 * rectangle behind a bar that is only ever a few dp thick. The gradient,
 * shadow and edge above carry the effect on their own.
 *
 * ## The one rule that is not a suggestion
 *
 * **Never stack two of these.** Translucency is multiplicative: a glass bar
 * over a translucent panel over the background leaves text on top of three
 * layers of partial coverage, and contrast collapses. If a screen needs a bar
 * and a panel, the panel goes opaque.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShape,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = colors

    // Both of these are pure functions of colours that only change when the
    // theme does. Built inline they allocated a `Brush` and a `List` per
    // recomposition — and this pane sits under the device list, so it
    // recomposed on every refresh.
    val fill = remember(colors) {
        Brush.verticalGradient(colors = listOf(colors.glassLit, colors.glassFill))
    }
    val elevation = if (colors.isDark) 18.dp else 5.dp

    Box(
        modifier = modifier
            // Order matters and is load-bearing. `shadow` must come first: it
            // draws outside the bounds, and a later `background` would paint over
            // the part of the shadow that falls inside the shape. `clip` then
            // comes *before* the gradient so the fill stops at the rounded edge
            // instead of squaring off behind it.
            //
            // The elevation is not the same in both themes, and that is a
            // measured decision rather than a stylistic one. `shadow` scrims the
            // fill, and it scrims *much* harder against a light surface: at 18dp
            // the dark bar landed 18 levels above its page while the light bar
            // landed 14 levels *below* its own — a floating element reading as a
            // recess. Light gets less elevation so its fill survives the scrim.
            .shadow(elevation = elevation, shape = shape, clip = false)
            .clip(shape)
            .background(brush = fill)
            .border(width = 1.dp, color = colors.glassEdge, shape = shape),
        content = content,
    )
}

/**
 * The one shape every pane in the app is cut to.
 *
 * Hoisted out of the default argument it used to be, because a default
 * `RoundedCornerShape(...)` is a fresh object at every call site that omits it —
 * and a new `Shape` is a changed parameter, which is enough to invalidate the
 * shadow and clip layers underneath.
 */
private val GlassShape: Shape = RoundedCornerShape(32.dp)

/**
 * The scrim a floating bar stands on.
 *
 * The bar is translucent, so whatever scrolls under it stays faintly visible —
 * which is the point, right up until a line of text is half-covered by the bar
 * and reads as neither background nor foreground. This fades the content out
 * before it reaches the bar.
 *
 * A gradient rather than a solid strip, because the seam between "content" and
 * "content behind glass" should not be an edge anybody can point at.
 */
@Composable
fun GlassScrim(modifier: Modifier = Modifier) {
    val colors = colors
    val fade = remember(colors) {
        Brush.verticalGradient(
            colors = listOf(
                colors.surface.copy(alpha = 0f),
                colors.surface.copy(alpha = 0.85f),
                colors.surface,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(fade),
    )
}
