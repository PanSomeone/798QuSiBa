package com.killhuishenghuo.ui.components

import androidx.compose.ui.unit.dp
import com.killhuishenghuo.ui.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the sliding highlight lands.
 *
 * This arithmetic spent its life inside a `BoxWithConstraints`, where it was
 * unreachable from a test and where being wrong costs nothing: a pill a few dp
 * off-centre is not a crash and not a failed build, it is a thing a person
 * notices and no build ever catches. It is a pure function now so it can be
 * asserted here.
 *
 * The numbers are the real ones: a 1080px screen at 420dpi is 411dp wide, the
 * bar insets by `Spacing.md` on each side and `Spacing.xxs` inside, leaving the
 * row 371dp of content.
 */
class GlassTabBarTest {

    private val rowWidth = 371.dp

    @Test
    fun `the two tabs split the row minus the gutter between them`() {
        val tab = tabWidthFor(rowWidth, tabCount = 2)

        // 371 - 4 = 367, halved.
        assertEquals(183.5.dp, tab)
        assertEquals(rowWidth, tab * 2 + Spacing.xxs)
    }

    @Test
    fun `the first tab starts at the row's edge and the last one ends at it`() {
        val count = MainTab.entries.size

        assertEquals(0.dp, tabPillOffset(rowWidth, count, index = 0))

        val last = tabPillOffset(rowWidth, count, index = count - 1)
        val lastEnd = last + tabWidthFor(rowWidth, count)
        // Exact, not "roughly": a pill that stops short leaves a gap of page
        // showing inside the bar, and one that runs over looks like a rendering
        // fault. Either way it is the kind of off-by-a-gutter that only a test
        // notices.
        assertEquals(rowWidth, lastEnd)
    }

    @Test
    fun `the highlight advances by one tab plus one gutter per step`() {
        val count = MainTab.entries.size
        val step = tabWidthFor(rowWidth, count) + Spacing.xxs

        for (index in 1 until count) {
            assertEquals(
                "step to index $index",
                step * index,
                tabPillOffset(rowWidth, count, index),
            )
        }
    }

    @Test
    fun `an unmeasured width yields a zero-size pill instead of a negative one`() {
        // The first frame of a preview reports 0. A negative width or offset
        // does not draw nothing — it crashes the layout.
        assertEquals(0.dp, tabWidthFor(0.dp, tabCount = 2))
        assertEquals(0.dp, tabPillOffset(0.dp, tabCount = 2, index = 1))
        assertEquals(0.dp, tabWidthFor(rowWidth, tabCount = 0))
        assertEquals(0.dp, tabPillOffset(rowWidth, tabCount = 0, index = 3))
    }

    @Test
    fun `an out-of-range index is clamped rather than sliding off the bar`() {
        val count = MainTab.entries.size

        assertEquals(
            tabPillOffset(rowWidth, count, index = count - 1),
            tabPillOffset(rowWidth, count, index = count + 5),
        )
        assertEquals(0.dp, tabPillOffset(rowWidth, count, index = -2))
    }
}
