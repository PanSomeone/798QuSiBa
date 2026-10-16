package com.killhuishenghuo.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrast ratios, computed rather than eyeballed.
 *
 * These are here because every colour mistake this project actually made was
 * invisible in the source and obvious on the phone: a near-black icon on a
 * near-black button in the light theme, white text on amber at 2.4:1, a
 * floating bar that measured *darker* than the page it was floating over. None
 * of those would have been caught by reading the file, and none of them are
 * caught by a screenshot unless somebody stops and measures.
 *
 * WCAG AA asks for 4.5:1 on body text and 3:1 on large text (18pt, or 14pt
 * bold). The primary button's label is `displayMedium`, so it qualifies as
 * large — but it is asserted at the body threshold anyway, because the button
 * is the one thing on the screen that must never be hard to read, and the cost
 * of the stricter bar is zero.
 */
class ContrastTest {

    @Test
    fun `the primary button label is readable on its own fill in both themes`() {
        assertTrue(
            "dark cta pair",
            ratio(DarkKhsColors.ctaFill, DarkKhsColors.onCta) >= 4.5,
        )
        assertTrue(
            "light cta pair",
            ratio(LightKhsColors.ctaFill, LightKhsColors.onCta) >= 4.5,
        )
    }

    /**
     * The regression this token exists to prevent.
     *
     * The light palette darkens its accents so they read as *text* on a light
     * page. Reusing one of those darkened accents as a button *fill* is the
     * mistake — the accent becomes the background and the label loses its
     * contrast. `warm` is the specific value that was tried, and this asserts
     * the CTA fill is not it.
     */
    @Test
    fun `the light cta fill is not one of the accents meant for text`() {
        assertTrue(
            "the CTA fill must stay bright enough to carry a dark label",
            ratio(LightKhsColors.ctaFill, LightKhsColors.onCta) >
                ratio(LightKhsColors.warm, Color.White),
        )
    }

    @Test
    fun `every ink is readable on the surface it is drawn on`() {
        listOf("dark" to DarkKhsColors, "light" to LightKhsColors).forEach { (name, colors) ->
            assertTrue("$name ink", ratio(colors.ink, colors.surface) >= 4.5)
            assertTrue("$name inkMuted", ratio(colors.inkMuted, colors.surface) >= 4.5)
            // The quietest ink, used for hints and secondary rows. This is the
            // one that fails in practice, because it is chosen by eye as "grey"
            // and nobody re-measures it after the page value moves.
            assertTrue("$name inkFaint", ratio(colors.inkFaint, colors.surface) >= 4.5)
        }
    }

    @Test
    fun `the disabled button is legible and does not read as the call to action`() {
        listOf("dark" to DarkKhsColors, "light" to LightKhsColors).forEach { (name, colors) ->
            assertTrue(
                "$name disabled label",
                ratio(colors.inkMuted, colors.surfaceSunken) >= 4.5,
            )
            // "Disabled" must be visibly *quieter* than the live button, or the
            // state is invisible. Measured as a contrast gap rather than as a
            // colour, so it keeps holding if either palette is retuned.
            assertTrue(
                "$name disabled is visibly quieter than the live fill",
                ratio(colors.inkMuted, colors.surfaceSunken) <
                    ratio(colors.ctaFill, colors.onCta),
            )
        }
    }

    /**
     * The floating bar separates from the page by *value*, and this is the
     * assertion that would have caught the light theme reading as a recess.
     *
     * The bar's real appearance is its fill under an elevation shadow, which
     * darkens it — so the fill is deliberately brighter than the page and the
     * shadow spends the difference. Both palettes must start with the fill
     * above the surface; if a palette is retuned so the bar begins darker than
     * the page, the shadow can only make that worse.
     */
    @Test
    fun `the glass fill starts above the surface in both themes`() {
        listOf("dark" to DarkKhsColors, "light" to LightKhsColors).forEach { (name, colors) ->
            assertTrue(
                "$name glass fill must be lighter than the page it floats over",
                luminance(colors.glassFill) > luminance(colors.surface),
            )
        }
    }

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** WCAG relative luminance. */
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }
}
