package com.killhuishenghuo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.killhuishenghuo.data.local.ThemeMode

/**
 * The application's visual direction, in two exposures of one design.
 *
 * Dark is the designed one: this is a control surface for a physical thing that
 * is used at night in a dormitory. Light exists because the same phone is also
 * carried outside at noon, and a dark screen in direct sun is unreadable in a
 * way that no amount of taste fixes. They are not two designs — same radii, same
 * type scale, same spacing, same one accent colour; only the twelve palette
 * values differ.
 *
 * There is no temperature ramp in the theme. An earlier version had one, built
 * on the assumption that the server reports outlet temperature — it does not,
 * and a colour scale interpolated from a number the app invented would be a
 * decoration pretending to be an instrument.
 */
private val DarkScheme = darkColorScheme(
    primary = Palette.Heated,
    onPrimary = Color(0xFF1A0A02),
    primaryContainer = Palette.HeatedDeep,
    onPrimaryContainer = Color(0xFFFFDCCB),

    secondary = Palette.Water,
    onSecondary = Color(0xFF04202C),
    secondaryContainer = Palette.WaterDeep,
    onSecondaryContainer = Color(0xFFD6F1FF),

    tertiary = Palette.Warm,
    onTertiary = Color(0xFF241703),

    background = Palette.Slate,
    onBackground = Palette.Ink,

    surface = Palette.Slate,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.SlateRaised,
    onSurfaceVariant = Palette.InkMuted,

    surfaceContainer = Palette.SlateRaised,
    surfaceContainerHigh = Color(0xFF1B2536),
    surfaceContainerLow = Palette.SlateSunken,
    surfaceContainerLowest = Palette.SlateSunken,

    outline = Palette.HairlineStrong,
    outlineVariant = Palette.Hairline,

    error = Palette.Danger,
    onError = Color(0xFF2A0508),
    errorContainer = Palette.DangerDeep,
    onErrorContainer = Color(0xFFFFDADB),

    scrim = Color(0xCC05080E),
)

private val LightScheme = lightColorScheme(
    primary = LightPalette.Heated,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCCB),
    onPrimaryContainer = Color(0xFF3A1206),

    secondary = LightPalette.Water,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE8F7),
    onSecondaryContainer = Color(0xFF04202C),

    tertiary = LightPalette.Warm,
    onTertiary = Color(0xFFFFFFFF),

    background = LightPalette.Slate,
    onBackground = LightPalette.Ink,

    surface = LightPalette.Slate,
    onSurface = LightPalette.Ink,
    surfaceVariant = LightPalette.SlateSunken,
    onSurfaceVariant = LightPalette.InkMuted,

    surfaceContainer = LightPalette.SlateRaised,
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerLow = LightPalette.SlateSunken,
    surfaceContainerLowest = Color(0xFFFFFFFF),

    outline = LightPalette.HairlineStrong,
    outlineVariant = LightPalette.Hairline,

    error = LightPalette.Danger,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBDAD7),
    onErrorContainer = Color(0xFF410E0B),

    scrim = Color(0x99000000),
)

private val KhsShapes = Shapes(
    extraSmall = RoundedCornerShape(Radii.chip),
    small = RoundedCornerShape(Radii.chip),
    medium = RoundedCornerShape(Radii.card),
    large = RoundedCornerShape(Radii.sheet),
    extraLarge = RoundedCornerShape(Radii.hero),
)

/**
 * Resolves [mode] against the system setting.
 *
 * Public and separate from [KillHuiShengHuoTheme] for two reasons. It is the
 * only part of the theme with a question in it — "which way does SYSTEM go" —
 * and a question is the only part worth a test; and [ThemeMode.SYSTEM] is the
 * one value whose answer can change while the app is running, so it is the one
 * value that has to be read inside a composition rather than once at startup.
 *
 * It is resolved exactly once per theme, and everything downstream reads the
 * palette's own `isDark` instead of asking again: two independent answers to
 * "is it dark right now" is how a status bar ends up light while the screen
 * underneath it is dark.
 */
@Composable
fun ThemeMode.resolveDark(): Boolean = when (this) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/**
 * Sets the palette, the type scale and the shape scale.
 *
 * [content] receives no `Typography` override hook because no font files are
 * bundled: Chinese renders in the platform CJK face, and a downloaded webfont
 * would need network access this build does not depend on.
 */
@Composable
fun KillHuiShengHuoTheme(
    mode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val dark = mode.resolveDark()
    val colors = if (dark) DarkKhsColors else LightKhsColors

    CompositionLocalProvider(localKhsColors provides colors) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = KhsTypography,
            shapes = KhsShapes,
            content = content,
        )
    }
}

/** Kept as a named alias so call sites read as one family with the palette. */
val AppFontFamily: FontFamily = FontFamily.Default
