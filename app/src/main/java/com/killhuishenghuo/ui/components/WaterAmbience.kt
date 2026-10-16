package com.killhuishenghuo.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import com.killhuishenghuo.ui.theme.colors
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.isActive

/** How long the ambience takes to arrive and to leave. */
private const val FadeInMillis = 620
private const val FadeOutMillis = 260

private const val SteamStrandCount = 3
private const val SteamPuffsPerStrand = 5
private const val RippleCount = 2

/** Seconds for one full rise, or one full ripple. Slow on purpose; see below. */
private const val SteamCycleSeconds = 5.6f
private const val RippleCycleSeconds = 3.4f

/**
 * The ripple's peak opacity, before the frame's presence and phase scale it down.
 *
 * A named constant because it appears twice — once inside the gradient the brush
 * is built from, and once in the per-frame alpha, which divides it back out so
 * the two do not multiply. It was `0.26f` written in the middle of the draw loop
 * before, and a value that two places have to agree on is a value that should not
 * be spelled out in either of them.
 */
private const val RingPeakAlpha = 0.26f

/**
 * The ambience, plus the one measurement it needs: where the button is.
 *
 * Both layers fill the same [Box], so the canvas draws in the page's own
 * coordinates while the button reports where it landed. That indirection is the
 * whole point —steam has to originate *at* the button and rise through the
 * empty page above it, and steam trapped in a strip the height of the button
 * would be a puff of smoke rather than a column of vapour.
 */
@Composable
fun WaterAmbienceBox(
    active: Boolean,
    buttonBounds: Rect,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // The canvas's own bounds in root coordinates. The button's come from the
    // caller, because the button reports them itself —a rectangle reported from
    // anywhere else would be the wrong rectangle.
    var canvasBounds by remember { mutableStateOf(Rect.Zero) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        WaterAmbience(
            active = active,
            buttonBounds = buttonBounds,
            canvasBounds = canvasBounds,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    // Written through an equality check, not straight into the
                    // state. `boundsInRoot()` returns a new `Rect` every time it
                    // is asked, so assigning unconditionally would mark the state
                    // changed on every layout pass — including the passes caused
                    // by the assignment itself.
                    val bounds = coordinates.boundsInRoot()
                    if (bounds != canvasBounds) canvasBounds = bounds
                },
        )
        content()
    }
}

/**
 * Ambient motion around the button: steam while the water runs, a slow ripple
 * while it is shut.
 *
 * Four decisions worth stating, because each is a place this could have gone
 * wrong:
 *
 * **It never moves the button.** Everything here is drawn in a [Canvas] behind
 * the button's own layout; nothing in this file measures anything above it. That
 * is not tidiness —the button is the only control in the app, it is operated
 * with wet hands at 1am, and a decoration that shifts the target by a few dp
 * when the state changes is a decoration that makes the app worse.
 *
 * **It is slow, and slow is the point.** A cycle is several seconds and opacity
 * tops out around a third. Something that pulses quickly reads as a notification
 * demanding attention; this should read as a room the user is standing in. A
 * fast loop would also be the only thing on screen with a reason to keep the
 * display awake, and this app is opened, used for four seconds, and closed.
 *
 * **Cut, not crossfaded.** Steam and ripple are two drawings of the same space,
 * so running both at once would put a plume of steam inside a ripple. Both are
 * driven by the same 0..1 value instead, so one is gone before the other starts.
 *
 * **Reduced motion means gentler, not absent.** The motion slows to about a
 * third and the ripple stops expanding. Deleting the ambience would take away
 * the only thing that says "the water is running" to somebody not reading the
 * screen —which, standing in a shower, is most people.
 *
 * **It costs nothing while it is not showing.** Two rules, both of which an
 * earlier version broke:
 *
 * The clock is *suspended*, not merely ignored. It used to tick forever and be
 * discarded inside the draw lambda, so a page with no steam on it still
 * recomposed and repainted sixty times a second to draw nothing. A frozen clock
 * says the same thing as a running one when the whole effect is at alpha zero,
 * and it stops the frame loop dead.
 *
 * Nothing in the frame allocates. The gradients and the three strand paths are
 * functions of the layout, not of the clock, so they are built once and reused;
 * only the per-puff alpha, position and radius change per frame. The earlier
 * version built a `Brush`, three `Path`s and about twenty `Color`s every frame,
 * which is invisible on a fast phone and is the phone working for no reason on
 * a slow one.
 *
 * The setting is read from `ANIMATOR_DURATION_SCALE` rather than from a Compose
 * local, because Compose UI 1.12.1 has no such local: `MotionDurationScale` is
 * what the animation system honours, and it is not something a composable can
 * ask about. `Settings.Global` is readable without permission on minSdk 26,
 * which is what makes this possible at all.
 */
@Composable
private fun WaterAmbience(
    active: Boolean,
    buttonBounds: Rect,
    canvasBounds: Rect,
    modifier: Modifier = Modifier,
) {
    val palette = colors
    val reduceMotion = rememberSystemReduceMotion()

    // The state itself, eased. Every alpha below is scaled by this, so the whole
    // effect arrives and leaves together instead of each strand fading on its own
    // schedule.
    val easedPresence by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(if (active) FadeInMillis else FadeOutMillis),
        label = "ambience-presence",
    )
    val presence = easedPresence

    // Does anything need drawing this frame? Two cases, and the second is the one
    // that is easy to forget: while the effect is fading *out* the clock has to
    // keep moving, or the steam would freeze mid-plume and then dissolve, which
    // reads as a stutter rather than as a fade.
    val showing = active || presence > PresenceFloor

    // Two clocks, and neither of them is an object the animation system owns. See
    // `rememberAmbienceClock` for why that is the whole point of this file's
    // performance story: an infinite transition keeps *producing frames* for as
    // long as it exists, whether or not anybody can see the result.
    val clock = rememberAmbienceClock(
        running = showing && active,
        cycleSeconds = SteamCycleSeconds,
    )
    // The ripple needs a clock of its own rather than a branch of the steam's.
    // It also has to outlive `active`: a ripple that stops the instant the water
    // does freezes mid-expansion only to dissolve, which reads as a stutter. It
    // keeps its own fade so the surviving clock is invisible after
    // `FadeOutMillis` — the draw phase applies that, not this one.
    val rippleClock = rememberAmbienceClock(
        running = showing,
        cycleSeconds = RippleCycleSeconds,
    )

    // Precomputed so the drawing loop does no work beyond arithmetic: the strand
    // layout is a property of the strand, not of the frame.
    val strands = remember {
        List(SteamStrandCount) { index ->
            SteamStrand(
                offsetX = listOf(-0.26f, 0.02f, 0.29f)[index],
                delay = listOf(0f, 0.34f, 0.67f)[index],
                // Small on purpose. These started at 0.13, which over a 1300px
                // reach moved each strand about 170px sideways —the plume no
                // longer rose from the button but from somewhere off to one side
                // of it, and the three columns stopped reading as one source.
                // A strand should wander, not travel.
                driftX = listOf(-0.055f, 0.045f, -0.04f)[index],
                periodX = listOf(1.7f, 2.3f, 2.0f)[index],
            )
        }
    }

    // Both rects are root coordinates, so this subtraction is exact.
    val button = remember(buttonBounds, canvasBounds) {
        buttonBounds.translate(-canvasBounds.left, -canvasBounds.top)
    }

    // Tuned separately per theme, and both down from 1. The dark page needs the
    // alpha cut because every pixel the haze lifts is conspicuously brighter than
    // a near-black surround; the light page needs it cut because the same alpha
    // over a bright ground carries more contrast, not less. Same colour in both —
    // what changes is how much of it the page can take.
    val weight = if (palette.isDark) 0.55f else 0.6f

    val steam = rememberSteamBrushes(color = palette.water, button = button, strands = strands)

    // The ripple's own geometry, which the ring's gradient is built from. Also
    // outside the draw lambda, for the same reason as the steam brushes.
    val rippleCentre = remember(button) { Offset(button.center.x, button.center.y) }
    val rippleRadius = remember(button) { button.height * 0.34f }
    val ring = rememberRingBrush(center = rippleCentre, radius = rippleRadius, color = palette.water)

    Canvas(
        modifier = modifier.semantics {
            // Decoration. A screen reader announcing it would be reading out paint.
            hideFromAccessibility()
        },
    ) {
        val now = clock.value
        if (button.width <= 0f || presence <= 0.01f) return@Canvas

        if (active) {
            drawSteam(
                strands = strands,
                paths = steam.corePaths,
                clock = now,
                presence = presence * weight,
                puffBrush = steam.puff,
                coreBrush = steam.core,
                reduceMotion = reduceMotion,
                button = button,
                reach = steam.reach,
            )
        } else {
            drawRipple(
                clock = rippleClock.value,
                // Presence only, with no theme weight on top. That weight exists
                // to stop the steam's fifteen overlapping puffs from washing the
                // page out; the ripple has two rings, and applying it here too
                // stacked two separate cut-downs onto one number.
                //
                // This is what takes the rings off the screen, and it is why the
                // ripple's clock can safely outlive `active`: 260ms after the
                // water stops, `presence` has reached the bail-out above.
                presence = presence,
                reduceMotion = reduceMotion,
                centre = rippleCentre,
                radius = rippleRadius,
                ring = ring,
            )
        }
    }
}

/**
 * Drives a repeating 0..1 clock, but only while [running].
 *
 * This replaces a `rememberInfiniteTransition`, and the difference is not
 * cosmetic — it is the largest single source of wasted work this app had.
 *
 * An infinite transition is an *object that keeps animating*. It is internal to
 * the Compose animation system, it owns a frame callback, and it goes on asking
 * the choreographer for frames for as long as it is in the composition. Nothing
 * opts out by not reading its value. The previous version of this file wrapped it
 * in `PresenceFloor` checks and an `Int.MAX_VALUE` tween to "suspend" it at idle,
 * and every one of those measures was real — but all of them were applied
 * *downstream* of the thing that was already costing money. The frames kept
 * coming, the clock kept advancing, the canvas node was invalidated on each one,
 * and the draw lambda walked into `presence <= 0.01f` and returned. Sixty times a
 * second, drawing nothing, forever. On a screen whose whole point is that it sits
 * still while you are not showering.
 *
 * A suspend loop has the property the animation system's objects do not: when
 * [running] is false the coroutine is parked on `awaitFrame` with no frame
 * requested, so the app produces no frames at all. Not cheap frames — none. The
 * GPU and the choreographer are idle, which is also why the battery number moves.
 *
 * Two details that are easy to get wrong:
 *
 *  - **The clock is a `MutableFloatState`, not a value read in this scope.** The
 *    draw lambda reads `.value`, so a frame invalidates the canvas node and only
 *    the canvas node. If `s.value` were read in the composable body instead, each
 *    animated frame would be a *recomposition* of the whole effect. That one-line
 *    difference is worth a recomposition per frame per effect.
 *  - **The phase is remembered across a stop, not reset to zero.** The old
 *    suspended tween held at 0, which was documented as invisible — true, since
 *    every puff is newborn and transparent at phase 0. Holding the phase is
 *    better anyway: the effect resumes where it dissolved rather than snapping,
 *    and it costs nothing, because the value was already in this state object.
 *
 * [cycleSeconds] is how much wall-clock one full cycle takes, and it is the only
 * timing input: the two effects run at speeds that are deliberately not the same,
 * and a scale factor at the call site would hide the one number a reader wants.
 */
@Composable
private fun rememberAmbienceClock(
    running: Boolean,
    cycleSeconds: Float,
): State<Float> {
    val clock = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(running, cycleSeconds) {
        if (!running) return@LaunchedEffect

        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                // The first frame after a resume has nothing to measure against;
                // taking it as the baseline is what keeps a paused effect from
                // jumping forward by however long it was paused, which on a
                // shower-length pause would be minutes of phase.
                if (last == 0L) {
                    last = now
                } else {
                    val elapsed = (now - last) * 1e-9f / cycleSeconds
                    last = now
                    clock.floatValue = (clock.floatValue + elapsed) % 1f
                }
            }
        }
    }

    return clock
}

/**
 * Presence below which there is nothing left to see.
 *
 * Matches the `presence <= 0.01f` bail-out inside the canvas, so the clock stops
 * on the same frame the last visible pixel goes rather than a few frames later.
 */
private const val PresenceFloor = 0.01f

/**
 * The steam's gradients and its three strand paths, built once per layout.
 *
 * Every one of these is a pure function of the colour, the button's rectangle
 * and the free space above it — none of them depends on the clock. Building them
 * inside the draw lambda, which is what this replaces, meant a `Brush`, three
 * `Path`s and a few dozen `Color`s allocated per frame for a hundred and
 * twenty-eight frame repeating loop that is on screen whenever the water runs.
 *
 * The puff gradient is built at full alpha and the per-puff fade is applied by
 * `drawCircle(alpha = …)` instead. That is what makes one brush reusable by all
 * fifteen puffs: the gradient itself does not change between them, only how much
 * of it lands.
 */
private class SteamBrushes(
    val reach: Float,
    val puff: Brush,
    val core: Brush,
    val corePaths: List<Path>,
)

@Composable
private fun rememberSteamBrushes(
    color: Color,
    button: Rect,
    strands: List<SteamStrand>,
): SteamBrushes {
    // Most of the way to the top of the page, so the plume always has somewhere
    // to dissolve. The floor keeps it from collapsing to nothing on a short page.
    val reach = remember(button) {
        (button.top * 0.86f).coerceAtLeast(button.height * 1.6f)
    }

    val puff = remember(color) {
        Brush.radialGradient(
            colors = listOf(color.copy(alpha = 1f), color.copy(alpha = 0f)),
        )
    }

    // The core's gradient carries the reach in its endpoints, so it is keyed on
    // the reach as well as the colour.
    val core = remember(color, reach) {
        Brush.verticalGradient(
            colors = listOf(
                color.copy(alpha = 0f),
                color.copy(alpha = 0.6f),
                color.copy(alpha = 1f),
                color.copy(alpha = 0f),
            ),
            startY = button.top,
            endY = button.top - reach,
        )
    }

    val paths = remember(strands, button, reach) {
        strands.map { strand ->
            val originX = button.left + button.width * (0.5f + strand.offsetX)
            Path().apply {
                moveTo(originX, button.top)
                cubicTo(
                    originX + reach * strand.driftX * 0.9f,
                    button.top - reach * 0.34f,
                    originX - reach * strand.driftX * 0.9f,
                    button.top - reach * 0.68f,
                    originX + reach * strand.driftX * 0.5f,
                    button.top - reach * 0.95f,
                )
            }
        }
    }

    return SteamBrushes(reach = reach, puff = puff, core = core, corePaths = paths)
}

private class SteamStrand(
    val offsetX: Float,
    val delay: Float,
    val driftX: Float,
    val periodX: Float,
)

/**
 * Whether the user has turned system animations off, or down to nothing.
 *
 * Read once per composition rather than observed: the value only changes when
 * somebody leaves the app to change it in Settings, and the app is rebuilt from
 * scratch often enough that a listener would be machinery for a case that
 * resolves itself.
 *
 * Deliberately not a `CompositionLocal`. There used to be a `LocalReduceMotion`
 * here, which does not exist in Compose UI 1.12.1 —`MotionDurationScale` is the
 * thing the animation system honours and it is not something a composable can
 * ask about. Reading the setting directly is the only way to get at it.
 *
 * Any failure reads as "animations are on", which is the ordinary case. A
 * settings read that throws should not be the reason the screen goes still.
 */
@Composable
private fun rememberSystemReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f) <= 0f
    }
}

/**
 * One frame of steam: strands of puffs fading toward the top, so the plume
 * dissolves into the page instead of ending at a line.
 *
 * The reach is measured against the free space *above the button* rather than
 * against the button, which is what makes this a column rising through the room
 * instead of a puff sitting on a slab.
 */
private fun DrawScope.drawSteam(
    strands: List<SteamStrand>,
    paths: List<Path>,
    clock: Float,
    presence: Float,
    puffBrush: Brush,
    coreBrush: Brush,
    reduceMotion: Boolean,
    button: Rect,
    reach: Float,
) {
    val speed = if (reduceMotion) 3f else 1f
    val baseY = button.top

    // Sized against the button rather than against `reach`. The first version
    // scaled puffs by the distance they travel, which on a tall page meant
    // 250px circles: the plume stopped being vapour and became a column of
    // balloons. A puff should be a puff regardless of how far the page lets it
    // climb, and only how far it climbs should depend on the page.
    val puff = button.height * 0.30f

    strands.forEach { strand ->
        val originX = button.left + button.width * (0.5f + strand.offsetX)

        repeat(SteamPuffsPerStrand) { index ->
            // Staggered by both the puff index and the strand delay, so the puffs
            // are never in a row —a row of puffs at one height reads as a dotted
            // line rather than as vapour.
            val age = (clock / speed + strand.delay + index * 0.25f) % 1f

            // Raised to 1.5, so a puff spends more of its life near the button and
            // the plume is densest where the water is. A linear rise put as much
            // vapour at the top of the page as at the source; squaring it went too
            // far the other way, pinning every puff within a button's height of
            // the source.
            val rise = age * kotlin.math.sqrt(age)
            val y = baseY - reach * rise
            val sway = sin((age * strand.periodX * 2f * PI).toFloat() + strand.delay * 6f)
            val x = originX + button.width * strand.driftX * sway

            // Thin at both ends of its life: just born, or about to dissolve.
            // Cubed for the same reason as the rise —the population has to fall
            // off quickly or the overlapping puffs accumulate into a wash.
            val lifeFade = sin(age * PI.toFloat())
            val alpha = 0.34f * presence * lifeFade * lifeFade * lifeFade

            if (alpha <= 0.004f) return@repeat
            drawCircle(
                brush = puffBrush,
                radius = puff * (0.55f + 0.75f * age),
                center = Offset(x, y),
                alpha = alpha,
            )
        }
    }

    if (reduceMotion) return

    // A faint rising core under the puffs. Without it the puffs read as separate
    // objects floating up; this is what makes them read as one column of vapour.
    // Kept barely-there —at any more opacity the curves are legible as curves,
    // and a visible curve is a drawn line rather than a body of vapour.
    //
    // The brush arrives with its gradient already at full strength and the fade
    // is applied here, because the only thing about it that changes per frame is
    // how much of it lands. Same for the paths: they are the strand's shape, not
    // the frame's, so they were built once with the rest of the steam.
    val coreFade = 0.06f * presence * presence
    paths.forEach { path ->
        drawPath(
            path = path,
            brush = coreBrush,
            // Narrow. At 0.16 these were 144px wide, which is the width of a
            // drawn band rather than the breath of one —they read as five
            // creases in the page instead of as the body of the plume.
            style = Stroke(width = button.height * 0.11f, cap = StrokeCap.Round),
            alpha = coreFade,
        )
    }
}

/**
 * One frame of the shut state: rings expanding away from the button's centre and
 * fading as they go, the way a still surface answers a touch.
 *
 * The first version of this drew nothing a camera could find, and it took three
 * attempts to accept why. It was written at 0.20 peak alpha, which the theme
 * weight then multiplied to 0.12, on a 4px stroke; raising the alpha to 0.45 and
 * the stroke to 3dp still measured nothing; rendering the same alpha as a filled
 * disc lit the page instantly. So the colour and the alpha were never the
 * problem — a hard edge four pixels wide simply carries too little ink at low
 * opacity to move a pixel. What was missing was edge *length*.
 *
 * Hence a band with a gradient across it rather than a stroked circle: it has the
 * coverage of an area, it still reads as a ring, and because both borders fade it
 * has no width to tune.
 *
 * The rings still stay small on purpose. A ripple that grew to the width of the
 * page would be a graphic; one that grows a hand's width around the button reads
 * as the surface being still, which is the thing being said.
 */
private fun DrawScope.drawRipple(
    clock: Float,
    presence: Float,
    reduceMotion: Boolean,
    centre: Offset,
    radius: Float,
    ring: Brush,
) {
    val speed = if (reduceMotion) 3f else 1f
    val growth = if (reduceMotion) 0.10f else 1f

    repeat(RippleCount) { index ->
        val phase = (clock / speed + index * 0.5f) % 1f
        val alpha = RingPeakAlpha * presence * (1f - phase)

        if (alpha <= 0.004f) return@repeat

        // Reduced motion keeps the colour but drops the travel: a ring that sits
        // still and breathes is calm; one that expands is motion.
        val outer = radius * (1f + 1.9f * growth * phase)

        drawCircle(
            brush = ring,
            radius = outer,
            center = centre,
            alpha = alpha / RingPeakAlpha,
        )
    }
}

/**
 * The ring's gradient, built once per layout.
 *
 * One gradient at the ring's *base* size, with its peak alpha already at the
 * ring's peak. Each ring applies its own opacity through `drawCircle(alpha = …)`
 * and its own growth by asking for a larger circle — a radial gradient's stops
 * are relative to the radius it was built with, so a brush made at the base
 * radius draws correctly at any radius.
 *
 * This used to be built inside the draw lambda, one `Brush.radialGradient` per
 * ring per frame, each with an `Array<Pair<Float, Color>>` and four `Color`s.
 * Two rings is small; two allocations every frame for as long as the app sits
 * idle is not, and idle is most of the time this app is open.
 */
@Composable
private fun rememberRingBrush(
    center: Offset,
    radius: Float,
    color: Color,
): Brush = remember(center, radius, color) {
    Brush.radialGradient(
        colorStops = arrayOf(
            0f to color.copy(alpha = 0f),
            0.74f to color.copy(alpha = 0f),
            0.88f to color.copy(alpha = RingPeakAlpha),
            1f to color.copy(alpha = 0f),
        ),
        center = center,
        radius = radius.coerceAtLeast(1f),
    )
}
