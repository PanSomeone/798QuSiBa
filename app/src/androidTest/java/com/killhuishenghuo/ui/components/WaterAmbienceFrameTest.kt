package com.killhuishenghuo.ui.components

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.killhuishenghuo.testing.AmbienceHostActivity
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ambience has to be invisible to the frame budget when nothing is happening.
 *
 * This is the regression test for the bug this file was written for. The effect used
 * to be driven by `rememberInfiniteTransition`, which owns its own frame callback, so
 * it produced a frame every 16ms for as long as the screen was composed — including,
 * and this is the part that made the app feel wrong, while the water was off. Measured
 * on the emulator with the water off and nothing touching the screen, the app produced
 * 1211 frames in a 20-second window (60.55fps) with the draw lambda bailing out at its
 * first line the entire time. The full before/after is in `tools/PERFORMANCE.md`.
 *
 * A JVM test cannot see any of that, because the bug was never in a function's return
 * value: it was in whether a frame was requested.
 *
 * ## Why this does not use `createComposeRule`
 *
 * It did, and it could not work. That rule owns the frame clock and waits for idle
 * during teardown; an effect whose entire purpose is to keep running while the water
 * is on is never idle, so the water-on case failed on the way out with
 * `ComposeNotIdleException: Idling resource timed out` regardless of what it asserted.
 * Turning the water off at the end of the test did not help either — after eleven
 * seconds of animation the composition was still reported as busy.
 *
 * So this test drives a real activity and counts real frames, through the same
 * `FrameMetrics` the platform reports for any app. Nothing owns the clock but the
 * platform, and nothing is waiting for the screen to go quiet, because the whole
 * point is that it should not.
 *
 * ## The two claims, and why both are here
 *
 * The water-on case is not decoration. Without it, a build in which the ambience had
 * been broken outright — a clock that never advances, a canvas that never draws —
 * would pass the only test that matters, and pass it by being dead.
 */
@RunWith(AndroidJUnit4::class)
class WaterAmbienceFrameTest {

    private companion object {
        /** Long enough to cover the 260ms fade-out and then some. */
        const val SettleMillis = 1_500L

        /** The window the regression is measured in. */
        const val QuietMillis = 5_000L

        /**
         * How many frames the quiet window is allowed to contain.
         *
         * Not zero, because an idle Android window is not a frozen one: the system can
         * still push a frame for a caret, a clock in the status bar, or its own
         * housekeeping. Two frames in five seconds is 0.4fps; the bug this test exists
         * for produced 60.
         */
        const val QuietFrameBudget = 2
    }

    private val frames = AtomicInteger(0)

    private fun scenario(waterOn: Boolean): ActivityScenario<AmbienceHostActivity> {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            AmbienceHostActivity::class.java,
        ).putExtra(AmbienceHostActivity.ExtraWaterOn, waterOn)

        return ActivityScenario.launch<AmbienceHostActivity>(intent)
    }

    /**
     * Counts frames for [millis], after giving the activity [settleMillis] to finish
     * starting.
     *
     * The counter is reset *after* the settle window on purpose: starting an activity
     * legitimately produces a burst of frames, and including that burst would let the
     * quiet-window assertion be satisfied by arithmetic rather than by the effect
     * having stopped.
     */
    private fun countFrames(
        scenario: ActivityScenario<AmbienceHostActivity>,
        settleMillis: Long,
        millis: Long,
    ): Int {
        val listener = Window.OnFrameMetricsAvailableListener { _, _, _ ->
            frames.incrementAndGet()
        }

        // Explicitly the main looper. `null` here is not "use the default": it throws
        // `NullPointerException: handler and its looper cannot be null` out of
        // `HardwareRendererObserver`, several frames deep inside the platform.
        val handler = Handler(Looper.getMainLooper())

        scenario.onActivity { activity: Activity ->
            activity.window.addOnFrameMetricsAvailableListener(listener, handler)
        }

        Thread.sleep(settleMillis)
        frames.set(0)
        Thread.sleep(millis)

        scenario.onActivity { activity: Activity ->
            activity.window.removeOnFrameMetricsAvailableListener(listener)
        }

        return frames.get()
    }

    /**
     * Frames per second an active ambience produces, for the record.
     *
     * Asserted only as "more than nothing". A precise number would be a claim about the
     * machine the test happens to run on, and this project has already been bitten once
     * by a threshold that was really a statement about one emulator.
     */
    private fun framesPerSecond(count: Int, millis: Long): Double =
        count * 1000.0 / millis

    @Test
    fun theAmbienceAsksForFramesWhileTheWaterIsOn() {
        scenario(waterOn = true).use { host ->
            val count = countFrames(host, settleMillis = SettleMillis, millis = QuietMillis)

            assertTrue(
                "the ambience produced no frames in ${QuietMillis}ms with the water on " +
                    "(so this test's other half would pass against a dead animation), " +
                    "or the host activity failed to draw at all",
                count > 0,
            )
        }
    }

    @Test
    fun theAmbienceAsksForNothingOnceTheWaterIsOff() {
        scenario(waterOn = false).use { host ->
            val count = countFrames(host, settleMillis = SettleMillis, millis = QuietMillis)

            val perSecond = String.format("%.1f", framesPerSecond(count, QuietMillis))

            assertTrue(
                "the ambience asked for $count frames in the ${QuietMillis}ms after the " +
                    "water was turned off and the fade had finished (${perSecond}fps); " +
                    "its clock is not being suspended when the effect is invisible. " +
                    "The budget for a quiet window is $QuietFrameBudget frames, because " +
                    "an idle Android window is not a frozen one.",
                count <= QuietFrameBudget,
            )
        }
    }
}
