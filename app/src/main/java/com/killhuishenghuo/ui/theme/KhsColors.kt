package com.killhuishenghuo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colours a screen is allowed to name, resolved for the current theme.
 *
 * Screens used to reference [Palette] directly. That works exactly as long as
 * there is one palette, and breaks the moment there are two — every call site
 * would have to learn which theme it is in, and the first one somebody misses
 * is a near-black card on a white background that nobody sees until release.
 *
 * So the components name a role (`colors.surfaceRaised`, `colors.water`) and
 * the theme decides what that is. [localKhsColors] is a `staticCompositionLocal`
 * because the value changes only when the user changes the theme: a dynamic one
 * would invalidate every reader on each recomposition to no benefit.
 */
@Immutable
data class KhsColors(
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val water: Color,
    val waterDeep: Color,
    val heated: Color,
    val heatedDeep: Color,
    val warm: Color,
    val cooling: Color,
    val danger: Color,
    val dangerDeep: Color,
    val pending: Color,
    /**
     * Ink to put *on top of* [heated] — a filled button or a filled chip.
     *
     * Not derivable: on the dark palette the accent is bright so the label is
     * near-black, and on the light palette the accent is dark so the label is
     * near-white. Guessing "always white" fails half the time, which is how
     * you get white text on orange at 2.4:1.
     */
    val onHeated: Color,
    val onWater: Color,

    /**
     * Fill and label for the one primary button on the main screen, in the
     * state where it is asking the user to go and set something up.
     *
     * A separate role because "a colour you can read on this surface" and "a
     * colour you can put a label on" are different problems, and the palettes
     * solve them in opposite directions. Both palettes darken their accents so
     * the accents stay legible as *text* on their own surface — which is right
     * for `colors.warm` in a sentence and wrong for a button, where the accent
     * becomes the background. Reusing it produced a brown slab with a 2.2:1
     * label in the light theme: measured on the device as 139,101,12.
     *
     * Kept bright in both themes, like a system control's tint, and paired with
     * a label colour that flips with it.
     */
    val ctaFill: Color,
    val onCta: Color,
    /**
     * The floating bar's fill.
     *
     * A *translucent* colour is the whole point — it is what makes the bar read
     * as a sheet of glass lying on the content rather than as a painted stripe.
     * It is a named role rather than an `alpha` at the call site because the two
     * palettes need different opacities: 0.78 over a near-black surface still
     * separates from it, while the same value over white would leave text on a
     * milky background at about 3:1. See [LightKhsColors] for the other half.
     */
    val glassFill: Color,
    /** The gradient's light end. Glass catches light at its top edge. */
    val glassLit: Color,
    /** The edge itself, one hairline, the way a real bevel catches a highlight. */
    val glassEdge: Color,
    val isDark: Boolean,
)

/** The palette the app was designed against. */
val DarkKhsColors = KhsColors(
    surface = Palette.Slate,
    surfaceRaised = Palette.SlateRaised,
    surfaceSunken = Palette.SlateSunken,
    hairline = Palette.Hairline,
    hairlineStrong = Palette.HairlineStrong,
    ink = Palette.Ink,
    inkMuted = Palette.InkMuted,
    inkFaint = Palette.InkFaint,
    water = Palette.Water,
    waterDeep = Palette.WaterDeep,
    heated = Palette.Heated,
    heatedDeep = Palette.HeatedDeep,
    warm = Palette.Warm,
    cooling = Palette.Cooling,
    danger = Palette.Danger,
    dangerDeep = Palette.DangerDeep,
    pending = Palette.Pending,
    onHeated = Color(0xFF1A0A02),
    onWater = Color(0xFF04202C),
    ctaFill = Color(0xFFFFB020),
    onCta = Color(0xFF1A0A02),
    // Opaque, and measured rather than guessed. Three earlier attempts failed,
    // and the reason is worth writing down: `Modifier.shadow` draws its own
    // layer *at* the view's level, so it scrims the fill instead of only falling
    // behind it. A translucent fill therefore never composites to what the
    // palette says — 0xF2 of a raised colour measured as 22,28,40, seven levels
    // off a page of 14,20,32, which is no bar at all.
    //
    // So the lift comes from *value*, not from transparency: an opaque raised
    // fill, with the shadow reading as depth around its edges (this lands at
    // 32,39,53 against that page). The gradient and the hairline edge still do
    // the "pane of glass" work. A frosted pane you can see through is not worth
    // a bar you cannot see at all.
    glassFill = Color(0xFF161E2C),
    glassLit = Color(0x0DFFFFFF),
    // Under about a fifth of white. At a third it stops reading as a bevel
    // catching light and starts reading as an outline drawn round the bar.
    glassEdge = Color(0x1FFFFFFF),
    isDark = true,
)

/** The accommodation, for a phone being used outdoors at noon. */
val LightKhsColors = KhsColors(
    // Lowered for a measured reason, not for taste.
    //
    // A floating bar can only read as floating if there is somewhere for it to
    // float *to*. The dark palette has that room above its surface; the light
    // palette does not, because the bar wants to be white and white is already
    // the page's own value. So the page had to come down instead. At the old
    // 0xFFF7F8FB the shaded bar measured 218,219,220 against a page of
    // 247,248,251 — fourteen levels *darker*, which is a recess, not a lift.
    // Holding the page near 236 with a white bar landing near 243 puts the bar
    // above it, and that is the only direction that reads as float.
    surface = Color(0xFFECEEF4),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFE2E6EF),
    hairline = LightPalette.Hairline,
    hairlineStrong = LightPalette.HairlineStrong,
    ink = LightPalette.Ink,
    inkMuted = LightPalette.InkMuted,
    inkFaint = LightPalette.InkFaint,
    water = LightPalette.Water,
    waterDeep = LightPalette.WaterDeep,
    heated = LightPalette.Heated,
    heatedDeep = LightPalette.HeatedDeep,
    warm = LightPalette.Warm,
    cooling = LightPalette.Cooling,
    danger = LightPalette.Danger,
    dangerDeep = LightPalette.DangerDeep,
    pending = LightPalette.Pending,
    onHeated = Color(0xFFFFFFFF),
    onWater = Color(0xFFFFFFFF),
    ctaFill = Color(0xFFFF8A3D),
    onCta = Color(0xFF2B1200),
    // Opaque for the same measured reason as the dark palette: the elevation
    // shadow scrims whatever it is given, so a translucent fill never shows up
    // as the colour that was written. White is already the light palette's
    // raised value, so there is nothing to lift it to — the light bar separates
    // by its shadow and its hairline instead of by its fill.
    glassFill = Color(0xFFFFFFFF),
    glassLit = Color(0x0F000000),
    glassEdge = Color(0x1A000000),
    isDark = false,
)

/**
 * No default value.
 *
 * A `staticCompositionLocalOf` with a default would silently hand any component
 * rendered outside [KillHuiShengHuoTheme] the dark palette — including previews
 * and tests, where the mistake is invisible. Erroring loudly is better: the one
 * place that must not forget the theme is the one place that would.
 */
val localKhsColors = staticCompositionLocalOf<KhsColors> {
    error("No KhsColors provided. Wrap this composable in KillHuiShengHuoTheme { }.")
}

/**
 * The active palette, as a property rather than a `.current` call at each of the
 * hundred call sites.
 */
val colors: KhsColors
    @Composable @ReadOnlyComposable get() = localKhsColors.current
