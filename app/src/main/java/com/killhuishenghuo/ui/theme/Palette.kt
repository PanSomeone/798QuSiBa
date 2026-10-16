package com.killhuishenghuo.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Palette.
 *
 * The subject is hot water, so the accent is the colour of a heated element and
 * the counter-accent is the colour of cold water. Surfaces are a deep slate
 * rather than pure black: the screen is read in a dormitory at night, and pure
 * #000 next to a bright accent is harsh at 2am.
 *
 * Contrast: ink on slate is ~15:1, heated on slate ~8:1, water on slate ~9:1 —
 * all comfortably past WCAG AA for body text.
 *
 * This is the **dark** palette and it is what the app was designed against.
 * [LightPalette] is the same twelve values re-derived for a bright room; see
 * [KhsColors] for how a screen picks one without knowing which it got.
 */
object Palette {
    val Slate = Color(0xFF0E1420)
    val SlateRaised = Color(0xFF161E2C)
    val SlateSunken = Color(0xFF0A0F18)
    val Hairline = Color(0xFF243044)
    val HairlineStrong = Color(0xFF33415A)

    val Ink = Color(0xFFE9EEF6)
    val InkMuted = Color(0xFF9AA8BE)
    /**
     * Brightened from `#6C7C96`, which measured 4.3:1 on the surface below —
     * under the 4.5:1 small text needs. This lands at 4.7:1 and is still
     * unmistakably the quietest of the three inks.
     */
    val InkFaint = Color(0xFF7889A4)

    /** Water that has not been heated. */
    val Water = Color(0xFF4CC2F1)
    val WaterDeep = Color(0xFF1E7FB0)

    /** Water that has. */
    val Heated = Color(0xFFFF6B35)
    val HeatedDeep = Color(0xFFC8451A)

    val Warm = Color(0xFFFFB020)
    val Cooling = Color(0xFF4ADE80)

    val Danger = Color(0xFFFF5A5F)
    val DangerDeep = Color(0xFFB03035)

    val Pending = Color(0xFF8B93A7)
}

/**
 * The same palette for a lit room.
 *
 * These are not the dark values lightened. On white, the dark accent colours
 * lose the contrast that made them work: `#FF6B35` on white is 3.1:1, which
 * fails AA for the button labels that use it. The accents here are darkened
 * until they pass with white text on top and still read as the same hue.
 *
 * Surfaces step *down* in brightness from the card to the sunken well, the
 * inverse of the dark palette, so that the same component code produces a
 * raised-looking card in both.
 */
object LightPalette {
    /**
     * Near `#ECEEF4` rather than the near-white this started as.
     *
     * A white floating bar needs a page it can sit *above*, and white was
     * already the page's own value. The value is repeated in
     * `KhsColors.LightKhsColors.surface`; keep the two in step, because the
     * system bars are painted from the palette while the content is painted from
     * the scheme, and a mismatch shows up as a seam under the status bar.
     */
    val Slate = Color(0xFFECEEF4)
    val SlateRaised = Color(0xFFFFFFFF)
    val SlateSunken = Color(0xFFE3E7F0)
    val Hairline = Color(0xFFDCE0EA)
    val HairlineStrong = Color(0xFFBFC6D6)

    val Ink = Color(0xFF131A26)
    val InkMuted = Color(0xFF4E5A6E)
    /**
     * Set from a measured ratio, not from a swatch.
     *
     * `#7A8699` — what this started as — came to 3.9:1 on the page above, under
     * the 4.5:1 that small text needs. This lands at 4.9:1 and is still
     * unmistakably the quietest of the three inks.
     */
    val InkFaint = Color(0xFF5E6A80)

    val Water = Color(0xFF0B6E96)
    val WaterDeep = Color(0xFF084F6D)

    val Heated = Color(0xFFC8451A)
    val HeatedDeep = Color(0xFF9C3210)

    val Warm = Color(0xFF8A5A00)
    val Cooling = Color(0xFF15803D)

    val Danger = Color(0xFFB3261E)
    val DangerDeep = Color(0xFF8C1D17)

    val Pending = Color(0xFF6B7688)
}

/**
 * Corner radii, in dp.
 *
 * Must be `Dp`, not `TextUnit`: these feed straight into `RoundedCornerShape`
 * and the type has to match at the boundary.
 */
object Radii {
    val hairline = 2.dp
    val chip = 10.dp
    val card = 16.dp
    val sheet = 24.dp
    val hero = 28.dp

    /** A fully rounded end. Used by the floating bar, which is a lozenge, not a card. */
    val pill = 32.dp
}

/**
 * One spacing rhythm, in dp, so vertical cadence stays even.
 *
 * Typed as `Dp` rather than as bare numbers: every consumer is a Compose
 * modifier, and the conversion belongs here once instead of `.dp` at each of
 * the hundred call sites (where a single missed one is a type error).
 */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

/**
 * How transitions are timed.
 *
 * Named for what they are for rather than for their duration, because the number
 * is the thing that gets tuned and the *name* is the thing call sites should be
 * able to read. A `tween(260)` at a call site says nothing about whether 260 was
 * chosen or inherited.
 *
 * ## Why there is a spring in here at all
 *
 * The rule is not "springs are nicer than durations" — it is **a duration is the
 * wrong control for a distance**. `standardMillis` says "this takes 260 ms", and
 * a tween obeys that literally: a pill hopping one tab and a pill hopping five
 * would take the same time, so the short hop reads as sluggish and a long one as
 * a teleport. A spring is specified by how it feels and finishes in whatever time
 * the distance needs, which is the control the moving-highlight case actually
 * wants.
 *
 * Neither is a `MotionScheme`. That is a package of a dozen specs for a Material
 * app's worth of transitions; this app has two, and both are named here.
 */
object Motion {
    /** A control moving between two known positions, when the distance is fixed. */
    const val standardMillis = 260

    /** After a finger has landed: the acknowledgement has to be immediate. */
    const val quickMillis = 120

    /**
     * The label sits still and waits this long before it starts changing, so a
     * quick back-and-forth does not read as two crossfades racing each other.
     */
    const val labelDelayMillis = 60

    /**
     * A filled shape travelling between two positions a variable distance apart.
     *
     * `DampingRatioNoBouncy` with a low stiffness: no overshoot, because a
     * selection indicator that shoots past its tab and comes back reads as a
     * wobble rather than as energy, and 200 rather than the default 1500 because
     * the default settles in about the time a tween would and gives back nothing
     * for having been written as a spring. At 200 a one-tab hop lands in roughly
     * the 260 ms `standardMillis` was already using, and a longer travel takes
     * proportionally longer instead of snapping.
     */
    fun <T> spring(): SpringSpec<T> = SpringSpec(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
    )
}

/**
 * Type.
 *
 * Latin runs use a condensed grotesque so numerals and the temperature scale
 * read as instrumentation rather than as prose; Chinese runs fall back to the
 * platform CJK face at a heavier weight, which is what keeps the two scripts
 * from looking like different applications.
 */
private val Display = FontFamily.SansSerif
private val Body = FontFamily.Default

val KhsTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Black,
        fontSize = 60.sp,
        lineHeight = 64.sp,
        letterSpacing = (-1.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-1).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.6.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
)

/**
 * The settings screen's type, defined as a set rather than pulled from
 * [KhsTypography] at each call site.
 *
 * Two reasons it is its own object. The practical one: every string on this
 * screen gets rewritten together, and chasing `bodyMedium` through seven
 * composables is how one of them ends up a size behind the rest. The load-bearing
 * one: these sizes are local *decisions about this screen*, not part of the
 * app's type scale — raising `bodyMedium` to make a settings hint legible would
 * silently re-space the sign-in page and the device row.
 *
 * A step above the [KhsTypography] defaults, all of them. The rest of the app is
 * read at a glance — a button label, a device name — while this screen is read,
 * and it is the only screen with prose on it.
 */
object SettingsType {
    /** Group titles: "账号", "外观", "反馈", "关于". */
    val section = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp,
    )

    /** A row's label — the thing the row is. */
    val label = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 25.sp,
    )

    /** A row's second line, and the explanatory copy under a block. */
    val detail = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )

    /** Field placeholders. Same size as [detail]; named apart so they can move apart. */
    val placeholder = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )
}
