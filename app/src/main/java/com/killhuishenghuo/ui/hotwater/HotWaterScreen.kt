package com.killhuishenghuo.ui.hotwater

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.killhuishenghuo.data.notice.Notice
import com.killhuishenghuo.ui.components.NoticeBanner
import com.killhuishenghuo.ui.components.WaterAmbienceBox
import com.killhuishenghuo.ui.components.glassTabBarSpace
import com.killhuishenghuo.ui.theme.Radii
import com.killhuishenghuo.ui.theme.Spacing
import com.killhuishenghuo.ui.theme.colors

/**
 * One screen, one button, and a bar along the bottom.
 *
 * There is no top bar any more. The title was the app's own name — which the
 * launcher already says, on the screen the user just came from — and the gear
 * that used to sit in the corner is now the second tab, where a destination
 * belongs. What is left is the only thing this app has ever answered: is the
 * water running, and turn it on.
 *
 * There is also no device list. This app answers exactly one question — "turn my
 * shower on" — and a list of rows asks the user to re-answer "which one?" every
 * single time, at 1am, with wet hands. Which device the button controls is a
 * decision made once, in settings, and remembered.
 *
 * [buttonBounds] arrives from above rather than being remembered here, and that
 * is a fix rather than a style choice. It used to be a `remember` inside this
 * function, which meant every trip to settings and back dropped the measurement:
 * the rectangle went to zero, the ambience's `remember(buttonBounds, canvasBounds)`
 * built itself again, and `onGloballyPositioned` had to fire twice before the
 * steam knew where the button was. The button does not move across a tab switch,
 * so the number that describes where it is should not either.
 */
@Composable
fun HotWaterScreen(
    viewModel: HotWaterViewModel,
    onOpenSettings: () -> Unit,
    buttonBounds: Rect,
    onButtonBounds: (Rect) -> Unit,
    notice: Notice?,
    onDismissNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // There was an `events` collector here. It matched all four event types and
    // did `Unit` against each one: the button's label, the state line and the
    // device list already said everything those events carried, so the only thing
    // the collector did was keep a channel from filling up. The channel is gone
    // rather than the collector, because a message nobody acts on is not a
    // confirmation.

    HotWaterContent(
        state = state,
        buttonBounds = buttonBounds,
        onButtonBounds = onButtonBounds,
        notice = notice,
        onDismissNotice = onDismissNotice,
        onPrimaryAction = {
            // The button never lies about what it will do. When there is nothing
            // to switch — no session, or an account with no devices on it — its
            // label says "去设置" and this is the branch that runs, so the user
            // is never left pressing a button that silently does nothing.
            if (state.needsSetup) onOpenSettings() else viewModel.toggleWater()
        },
        modifier = modifier,
    )
}

/**
 * The screen without its ViewModel, so a preview or a test can drive it.
 *
 * The button's rectangle is hoisted up here rather than kept inside the ambience
 * because the two live in different parts of the tree: the ambience is behind the
 * content and the button is inside it, so the measurement has to travel the same
 * path the layout does.
 *
 * There is no `onSelectTab` and no bottom bar here. The bar is drawn once, over
 * both destinations, by `MainTabBar` — see the note there for why it cannot live
 * in here. What this screen owes it is the *space* it occupies, which is what the
 * `bottomBar` below reserves.
 */
@Composable
fun HotWaterContent(
    state: HotWaterUiState,
    buttonBounds: Rect = Rect.Zero,
    onButtonBounds: (Rect) -> Unit = {},
    notice: Notice? = null,
    onDismissNotice: (String) -> Unit = {},
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            containerColor = colors.surface,
            // Space, not a drawing. The bar this makes room for is the one
            // `MainTabBar` draws on top of everything; all this screen needs from
            // it is the height, so the button can never end up underneath it.
            bottomBar = { Spacer(Modifier.height(glassTabBarSpace())) },
        ) { innerPadding ->
            // The ambience sits behind the content, not behind the button: the
            // steam rises from the button's top edge into the empty page above
            // it, and the ripple spreads outward from the button's middle. Both
            // need the whole page to move through, which is why `PrimaryAction`
            // reports its own bounds rather than being wrapped in a sized box.
            WaterAmbienceBox(
                active = state.isRunning,
                buttonBounds = buttonBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppWordmark()

                    // 公告贴在字标下面、按钮上面。
                    //
                    // 位置是它唯一的自由度:再往上就是屏幕最边缘,再往下就要挤按钮。
                    // 它只有一行、而且绝大多数时候不存在,所以放在这里既不会让每次
                    // 打水都看见,也不会在真的有话说时藏起来。
                    if (notice != null) {
                        Spacer(Modifier.height(Spacing.md))
                        NoticeBanner(notice = notice, onDismiss = onDismissNotice)
                    }

                    Spacer(Modifier.weight(BottomBias))

                    PrimaryAction(
                        state = state,
                        onPrimaryAction = onPrimaryAction,
                        onButtonBounds = onButtonBounds,
                    )

                    Spacer(Modifier.weight(TopBias))
                }
            }
        }
    }
}

/**
 * The app's name, at the top, once.
 *
 * Sitting *above* the device name rather than in a top bar: a bar would draw a
 * line across the page and the ambience needs that page. It is a wordmark, not
 * navigation — there is nothing up here to press, and nothing up here to press
 * would compete with the one button that matters.
 *
 * The second line is a phonetic joke and is deliberately the smallest thing on
 * the screen: 惠生活 is *huì shēng huó*, which lands close enough to 七九八 —
 * 798. The app is named after the company it exists to complain about, so the
 * homophone is the whole point of the name rather than a decoration on it.
 * Set in `inkFaint` because a joke that has to be loud is not a good joke.
 */
@Composable
private fun AppWordmark(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "惠生活 去死吧",
            style = MaterialTheme.typography.titleMedium,
            color = colors.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "798 748",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
            color = colors.inkFaint,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The device name, the state line, and the button.
 *
 * Sits below centre on purpose. A phone is held in one hand and the thumb
 * sweeps the lower half of the glass; the middle of the screen is where a thumb
 * has to *reach* rather than rest. The two spacers carry unequal weights so the
 * block lands in the lower third without being pinned there — the error line
 * appears under the button, and a block glued to the bottom would have nowhere
 * to put it.
 */
@Composable
private fun PrimaryAction(
    state: HotWaterUiState,
    onPrimaryAction: () -> Unit,
    onButtonBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = state.selected
    val running = state.isRunning
    val needsSetup = state.needsSetup

    // The gap is stored as a deadline so the rule itself needs no timer, which
    // leaves the *screen* needing one: nothing recomposes when a wall-clock
    // instant passes. This is that timer, and it is the only one — it fires once
    // per action, at the exact moment the button becomes live again.
    //
    // It drives rendering only. The rule is `state.isStartBlocked`, consulted
    // here and again in the ViewModel, because a disabled button is a hint and
    // not a guarantee: `enabled = false` still leaves the click handler one
    // recomposition away from being reachable.
    var startBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(state.startBlockedUntilMillis) {
        val remaining = state.startBlockedUntilMillis - System.currentTimeMillis()
        startBlocked = remaining > 0
        if (remaining > 0) {
            delay(remaining)
            startBlocked = false
        }
    }
    // Recomputed rather than read straight from the timer, so the statement
    // "starting is blocked" is answered by the same function the ViewModel uses.
    val gateShut = startBlocked && !running

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // With nothing bound there is no device to name, so the line says what
        // to do instead of leaving a blank where a name should be.
        Text(
            text = device?.displayName ?: "",
            style = MaterialTheme.typography.headlineSmall,
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Spacing.sm))

        Text(
            text = state.statusLine,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                needsSetup -> colors.ctaFill
                running -> colors.water
                else -> colors.inkMuted
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Spacing.xl))

        PrimaryButton(
            label = state.buttonLabel,
            running = running,
            needsSetup = needsSetup,
            // Busy is the spinner; the gap is only the lock. Passing the gap as
            // busy would replace "关闭热水" with a spinner for three seconds
            // right after the water started, which reads as still working when
            // the water is already running.
            busy = state.isBusy,
            enabled = !state.isBusy && !gateShut,
            onClick = onPrimaryAction,
            onBounds = onButtonBounds,
        )

        val message = state.errorMessage
        if (message != null) {
            Spacer(Modifier.height(Spacing.md))
            ErrorLine(message = message)
        }
    }
}

/**
 * The button itself, split out only so it can report where it landed.
 *
 * That measurement is the whole reason this is a separate composable: the
 * ambience needs the *button's* rectangle, and the column that holds it also
 * holds the device name and the status line, so reporting from there would put
 * the steam's origin somewhere above the button's top edge and the ripple's
 * centre above its middle. A three-item difference is invisible in the source
 * and obvious on the screen.
 *
 * All four colour slots are set explicitly. Leaving `contentColor` out was a real
 * bug: Material falls back to the *theme's* `onSurface`, and this app drives its
 * own palette rather than Material's scheme, so the "去设置" label came out
 * near-black on a near-black button — legible in the source, invisible on the
 * phone.
 */
@Composable
private fun PrimaryButton(
    label: String,
    running: Boolean,
    needsSetup: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Both of these were inline, and both are asked for on every recomposition of
    // a screen that recomposes on every device refresh and every busy flip. The
    // shape is an object allocation inside a hot path.
    //
    // `palette` is read once, outside, because `colors` is itself a composable
    // property — it cannot be called from inside a `remember` lambda. It is the
    // right key anyway: a new instance exactly when the theme changes.
    //
    // `buttonColors` is deliberately *not* cached. `ButtonDefaults.buttonColors`
    // is a `@Composable`, so it cannot be called inside `remember` either, and
    // Material already scopes the colour set per call — the same lesson the two
    // field-styling helpers learned.
    val palette = colors
    val shape = remember(palette) { RoundedCornerShape(Radii.hero) }
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = when {
            // Amber, not grey: "go configure yourself" is a call to action,
            // and a disabled-looking button is the one thing it must not look
            // like. `ctaFill` rather than `warm`, because this is a fill and
            // not a sentence — see the role's KDoc.
            needsSetup -> palette.ctaFill
            running -> palette.water
            else -> palette.heated
        },
        contentColor = when {
            needsSetup -> palette.onCta
            running -> palette.onWater
            else -> palette.onHeated
        },
        // Not `surfaceRaised` in the light theme: Material lays the disabled
        // container down at 38% over the *content* colour, so a white fill
        // over a grey label composited to 139,101,12 — a brown slab with a
        // label you had to hunt for. `surfaceSunken` is already defined to sit
        // below the page, so it composites to something that reads as "not
        // now" in both palettes without needing a second disabled pair.
        disabledContainerColor = palette.surfaceSunken,
        disabledContentColor = palette.inkMuted,
    )
    // `displayMedium` with a smaller size rather than a different style: the
    // button's label and the headlines are the same face at different
    // volumes, and 40sp in a 66dp button left the ascenders touching both
    // edges.
    val labelStyle = MaterialTheme.typography.displayMedium.copy(fontSize = 33.sp)

    // 风格 D 的「玻璃感」落在这一颗按钮上：一条自上而下的渐变 + 一道顶部高光，
    // 让它是「一块有厚度的东西」而不是「一个填了色的矩形」。湿手在暗光里看到的
    // 首先就是这个边缘。
    //
    // 做法是把自己画在按钮**背后**，并把 Material 的填充设成透明：这样水波纹、
    // 按压态、无障碍节点全都还是 Material 那套，变的只是那一层颜色。
    val base = when {
        needsSetup -> palette.ctaFill
        running -> palette.water
        else -> palette.heated
    }
    val fill = remember(base) {
        Brush.verticalGradient(listOf(lerp(base, Color.White, 0.18f), lerp(base, Color.Black, 0.06f)))
    }
    val highlight = remember(palette) { Color.White.copy(alpha = if (palette.isDark) 0.22f else 0.30f) }

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(PrimaryButtonHeight)
            .clip(shape)
            .drawBehind {
                if (!enabled) return@drawBehind
                drawRect(fill)
                // 顶部那一道：只在最上面两像素，就是「玻璃的边」在这块面上的说法。
                drawRect(
                    color = highlight,
                    size = Size(size.width, 2.dp.toPx()),
                )
            }
            .onGloballyPositioned { onBounds(it.boundsInRoot()) },
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = buttonColors.contentColor,
            disabledContainerColor = palette.surfaceSunken,
            disabledContentColor = palette.inkMuted,
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                color = colors.inkFaint,
                strokeWidth = 3.dp,
            )
        } else {
            Text(
                text = label,
                style = labelStyle,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The failure, in place, under the button that caused it.
 *
 * Deliberately not a snackbar: a message on a timer is one a slow reader never
 * finishes, and this app is read by somebody standing in a bathroom. It sits
 * below the button so it never pushes the button itself around.
 */
@Composable
private fun ErrorLine(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.danger.copy(alpha = 0.12f), RoundedCornerShape(Radii.card))
            .padding(Spacing.sm),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.danger,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * How tall the button is.
 *
 * A compromise between two things that pull in opposite directions. The ambience
 * needs empty page around the button to move through, and a thumb needs a target
 * it cannot miss at 1am. 112dp cleared the first bar easily and failed the
 * second: it filled so much of the screen that there was nothing left to see
 * behind it. 84dp gave the page its room back but still read as a slab across
 * the screen; this is the version that leaves the ambience somewhere to move and
 * still clears the 48dp minimum by half again.
 */
private val PrimaryButtonHeight = 66.dp

/**
 * The two spacers that place the button, as a ratio rather than a distance.
 *
 * Unequal so the block sits below centre — see `PrimaryAction`. A ratio and not
 * a fixed offset because the page height is not a constant: a taller phone
 * should push the button down with it, not leave it stranded in the middle.
 */
private const val BottomBias = 1.45f
private const val TopBias = 0.55f

/**
 * What the screen says under the device name.
 *
 * Not inline in the layout any more, and not private: these strings are the whole
 * of the screen's vocabulary, and the state they are chosen from is what actually
 * went wrong in the field. Keeping the mapping out of the composable is what lets
 * a test read the sentence a user would have seen.
 */
internal val HotWaterUiState.statusLine: String
    get() = when {
        isBusy -> "正在处理"
        needsSetup -> "还没有可用的设备"
        isRunning -> "热水已开"
        // Not "热水已关": the water is off, but the account still has an open
        // session here and starting a new one is refused until it is settled at
        // the machine. Folding this into "closed" is exactly what produced a
        // screen reading 关 while the server answered 设备正在使用.
        needsSettling -> "上次没结算"
        else -> "热水已关"
    }

/**
 * What the button says.
 *
 * The label is a promise about the next press, so it tracks
 * [HotWaterUiState.needsSetup] rather than the device state: a button labelled
 * "一键启动热水" that opens settings would be the app lying about itself.
 *
 * [HotWaterUiState.needsSettling] deliberately has no branch: pressing the button
 * is the *only* way to find out whether the session cleared, and the refusal it
 * can produce (`-2`) is now a sentence rather than a wall. A disabled button would
 * take away the one action available.
 */
internal val HotWaterUiState.buttonLabel: String
    get() = when {
        needsSetup -> "去设置"
        isRunning -> "关闭热水"
        else -> "一键启动热水"
    }
