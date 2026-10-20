package com.killhuishenghuo

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.killhuishenghuo.core.log.AppLog
import com.killhuishenghuo.core.net.NetworkModule
import com.killhuishenghuo.data.local.ThemeMode
import com.killhuishenghuo.data.model.Device
import com.killhuishenghuo.data.update.UpdateChecker
import com.killhuishenghuo.ui.components.GlassScrim
import com.killhuishenghuo.ui.components.GlassTabBar
import com.killhuishenghuo.ui.components.MainTab
import com.killhuishenghuo.ui.components.UpdateBanner
import com.killhuishenghuo.ui.components.glassTabBarSpace
import com.killhuishenghuo.ui.theme.Spacing
import com.killhuishenghuo.ui.hotwater.HotWaterScreen
import com.killhuishenghuo.ui.hotwater.HotWaterUiState
import com.killhuishenghuo.ui.hotwater.HotWaterViewModel
import com.killhuishenghuo.ui.hotwater.HotWaterViewModelFactory
import com.killhuishenghuo.ui.login.AuthViewModel
import com.killhuishenghuo.ui.login.AuthViewModelFactory
import com.killhuishenghuo.ui.settings.SettingsScreen
import com.killhuishenghuo.ui.settings.UpdateUiState
import com.killhuishenghuo.ui.settings.UpdateViewModel
import com.killhuishenghuo.ui.start.StartScreen
import com.killhuishenghuo.ui.theme.DarkKhsColors
import com.killhuishenghuo.ui.theme.KillHuiShengHuoTheme
import com.killhuishenghuo.ui.theme.LightKhsColors
import com.killhuishenghuo.ui.theme.colors
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No bare `enableEdgeToEdge()` here on purpose. It would resolve the icon
        // tint from the *phone's* light/dark setting, and the theme is the app's own
        // choice — a phone in light mode with the app in dark mode drew dark
        // status-bar icons on a near-black bar and the clock and battery vanished.
        // `SystemBars` below sets the bars from the palette and re-runs on every
        // theme change.
        setContent { AppRoot() }
        logFirstFrame()
    }

    /**
     * Writes the startup time of the first frame into the app's own log.
     *
     * The number this reports is `FrameMetrics.FIRST_DRAW_FRAME`, which the framework
     * measures from process start to the first frame's draw pass being submitted —
     * the same thing `am start -W` prints as `TotalTime`, except this one is not
     * diluted by `am`'s own shell round trip, and it lands in the log the app
     * already exports.
     *
     * It exists because "startup got faster" is otherwise an assertion with no
     * number under it. One line per process, and the listener removes itself the
     * moment it fires — a probe that keeps measuring becomes part of what it is
     * measuring.
     */
    private fun logFirstFrame() {
        if (frameListener != null) return
        val listener = object : Window.OnFrameMetricsAvailableListener {
            override fun onFrameMetricsAvailable(
                window: Window,
                metrics: FrameMetrics,
                dropCountSinceLastInvocation: Int,
            ) {
                val millis = metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) / 1_000_000.0
                AppLog.log("Startup", "first frame %.0fms".format(millis))
                detachFirstFrameListener()
            }
        }
        frameListener = listener
        window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
    }

    /** Held so the probe can be detached if the window goes away before it fires. */
    private var frameListener: Window.OnFrameMetricsAvailableListener? = null

    /**
     * Detaches the probe, exactly once.
     *
     * `Window.removeOnFrameMetricsAvailableListener` throws
     * `IllegalArgumentException: attempt to remove OnFrameMetricsAvailableListener
     * that was never added` when handed something it does not have — it does not
     * quietly ignore a second removal of the same listener. So there is one detach
     * path and it nulls the field *before* removing, which is what makes the second
     * caller a no-op instead of a crash.
     *
     * The crash this prevents is worth keeping in mind, because it only ever
     * appeared in a minified build: both detach sites are ordinary and both are
     * correct on their own, and the probe works in `debug` from a cold start every
     * time. R8 inlined the callback into `onDestroy`, which turned "the listener
     * usually fires before the activity goes away" into "the listener always fires
     * first", and every launch died at the first frame.
     */
    private fun detachFirstFrameListener() {
        val listener = frameListener ?: return
        frameListener = null
        window.removeOnFrameMetricsAvailableListener(listener)
    }

    override fun onDestroy() {
        detachFirstFrameListener()
        super.onDestroy()
    }
}

/**
 * Which screen is up.
 *
 * An enum rather than a navigation library. Settings is reached from the one
 * screen that matters; a back stack, a graph and a route parser would be
 * machinery that exists to be machinery.
 */
private enum class Destination { MAIN, SETTINGS }

/**
 * The whole application: theme, two destinations, and no gate in front of either.
 *
 * **The app opens on the button, always** — fresh install, signed out, dead
 * token, all the same. There is no login wall and there is no first-run screen,
 * because this app answers exactly one question and every screen placed before
 * the answer is a screen that has to be dismissed first. Somebody who has opened
 * this at 1am, dripping, wants one thing on the display, and it is the button.
 *
 * What used to be a gate is now a state of the button. With no session the
 * device list comes back empty and anonymous — `ui/app/master` accepts that, and
 * answering `{"code":0,"data":{"ads":[]}}` to a request with no `Authorization`
 * is not an error — so the button reads "去设置" and pressing it goes there.
 * Same count of taps as a gate, minus the screen that had to be got past, and
 * minus the lie that an account is a precondition for looking at the app.
 *
 * [StartHost] survives for exactly one case: a session that exists but has been
 * rejected. That is a real state — the token is stale, and there is nothing to
 * look at until it is replaced — and it is the one case where asking is more
 * useful than showing an empty list.
 */
@Composable
private fun AppRoot() {
    val app = LocalContext.current.applicationContext as KillHuiShengHuoApp
    val preferences = remember(app) { app.preferences }

    var mode by remember(app) { mutableStateOf(preferences.themeMode) }
    var destination by remember(app) { mutableStateOf(Destination.MAIN) }

    // Where the button was, measured once and kept across destination changes.
    //
    // This is state about the *layout*, and the layout outlives any one screen:
    // pressing 设置 and coming back puts the button in exactly the same place. It
    // used to live inside `HotWaterScreen`, so every return trip started from
    // `Rect.Zero`, the button re-reported itself, and the ambience was rebuilt
    // from scratch against a fresh rectangle — two extra composition passes per
    // tab switch, on the one interaction where the user is already watching for
    // a stutter.
    var buttonBounds by remember { mutableStateOf(Rect.Zero) }

    // Navigation, logged where it happens rather than where it is rendered. A
    // report of "it showed the wrong screen" is mostly answerable by the order of
    // these lines.
    LaunchedEffect(destination) {
        AppLog.log("Nav", "destination=$destination")
    }

    val host = remember { hostOf(NetworkModule.baseUrl) }

    KillHuiShengHuoTheme(mode = mode) {
        // Resolved once. It used to be `val dark = mode.resolveDark()` out here
        // and then `KillHuiShengHuoTheme(mode = mode)` below, which resolves the
        // same question a second time — and under `ThemeMode.SYSTEM` subscribes
        // to the system setting twice.
        val dark = colors.isDark
        SystemBars(dark = dark)

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.surface,
        ) {
            // Built here rather than inside the main screen because settings
            // needs the same instance: it is where the button's target is
            // chosen, and a second ViewModel would fetch a second device list
            // and hold a second idea of which device is selected.
            //
            // Keyed on the session token, so replacing a dead token rebuilds the
            // list: the devices belong to the account, and a ViewModel that
            // outlived the swap would keep showing the previous account's.
            //
            // `remember(mode)`, not `remember(app, token)`: the factory is only
            // consulted the first time a key is seen, and remembering it on the
            // token cost a new factory object on every recomposition that a token
            // change did not even affect.
            val factory = remember(app, app.session.token) {
                HotWaterViewModelFactory(
                    repository = app.repository,
                    preferences = app.preferences,
                    accountName = app.session.accountName,
                )
            }
            val hotWater: HotWaterViewModel = viewModel(
                key = "hotwater-${app.session.token.orEmpty()}",
                factory = factory,
            )

            // The root listens to one boolean, not to the whole state. Reading
            // `hotWater.uiState` up here would mean a device refresh or a busy
            // flip invalidated this scope — and this scope owns the `when`, both
            // screens, and the tab bar.
            val sessionExpired by hotWater.sessionExpired.collectAsStateWithLifecycle()

            // 自动检查更新。
            //
            // ViewModel 从设置页提到这一层,有两个原因:主屏要能画横幅(否则用户得先
            // 点进设置、再点那一行才知道有新版本,这不叫自动);以及两处必须看同一个
            // 状态 —— 两个实例会出现"设置里说已是最新、主屏却在说有新版"。
            //
            // `LaunchedEffect(Unit)`:一次进程只问一次。它是个网络请求,不该随重组重复。
            val updateViewModel: UpdateViewModel = viewModel(key = "update") {
                UpdateViewModel(checker = UpdateChecker())
            }
            val updateState by updateViewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { updateViewModel.check() }

            // A stale session is the one thing that gets its own screen.
            if (sessionExpired) {
                StartHost(
                    app = app,
                    onSignedIn = { destination = Destination.MAIN },
                )
                return@Surface
            }

            when (destination) {
                Destination.MAIN -> HotWaterScreen(
                    viewModel = hotWater,
                    onOpenSettings = { destination = Destination.SETTINGS },
                    buttonBounds = buttonBounds,
                    onButtonBounds = { buttonBounds = it },
                )

                Destination.SETTINGS -> SettingsHost(
                    app = app,
                    host = host,
                    dark = dark,
                    updateState = updateState,
                    onCheckUpdate = updateViewModel::check,
                    // The flow, not a snapshot of it. Collected inside
                    // `SettingsHost` so that a device refresh recomposes the
                    // settings rows and nothing above them — which is the whole
                    // reason it is passed down as a flow rather than as
                    // `state.devices`.
                    state = hotWater.uiState,
                    onDeviceSelected = hotWater::selectDevice,
                    onBindScanned = hotWater::bindScannedDevice,
                    onThemeChange = { picked ->
                        AppLog.log("Theme", "mode=$picked")
                        preferences.themeMode = picked
                        mode = picked
                    },
                    onSignOut = {
                        // Nothing to reset up here: signing out clears the
                        // session, which changes the ViewModel key, which is what
                        // takes the device list away.
                        destination = Destination.MAIN
                    },
                )
            }

            // 自动检查的结果:只在新版可用、且在「使用」屏时出现。
            //
            // 不弹窗、不挡按钮 —— 它是一条压在底栏上方的细条。外层 Box 铺满全屏但
            // 没有 pointerInput,所以点别处会穿过去:它是层"画",不是层"盖"。
            val available = updateState as? UpdateUiState.Available
            if (available != null && destination == Destination.MAIN) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.lg),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    UpdateBanner(
                        latest = available.latest,
                        url = available.url,
                        modifier = Modifier.padding(bottom = glassTabBarSpace()),
                    )
                }
            }

            // The bar is drawn here, once, above whichever screen is up — and the
            // screens only *reserve* the space it occupies. That split is what
            // makes the highlight a moving object.
            //
            // It used to live in each screen's `Scaffold(bottomBar = …)`, which
            // reads as the same picture and is not: swapping destinations
            // destroyed one bar and built another, so the pill arrived already
            // sitting under the new tab. A thing that is redrawn in its new place
            // has not moved, and "选择移动了" is the whole message. One bar that
            // survives the swap has somewhere to move *from*.
            //
            // Nothing about navigation lives inside it: it reports the tab that
            // was pressed and this decides where that goes, the same way it
            // already does for the main screen's back-less exit.
            MainTabBar(
                selected = destination.tab,
                onSelect = { destination = it.destination },
            )
        }
    }
}

/**
 * The floating bar and the scrim under it, in one place.
 *
 * The scrim is the content fading out behind the bar rather than a second
 * surface — see [GlassScrim] — so it is sized from the bar's own height and
 * drawn first.
 */
@Composable
private fun MainTabBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        GlassScrim(
            modifier = Modifier.height(glassTabBarSpace() + Spacing.lg),
        )
        GlassTabBar(
            selected = selected,
            onSelect = onSelect,
        )
    }
}

/** Where each tab goes. Kept next to [Destination] so the two cannot drift. */
private val MainTab.destination: Destination
    get() = when (this) {
        MainTab.USE -> Destination.MAIN
        MainTab.SETTINGS -> Destination.SETTINGS
    }

/** The reverse. Also kept here, so a bar cannot claim a tab nothing routes to. */
private val Destination.tab: MainTab
    get() = when (this) {
        Destination.MAIN -> MainTab.USE
        Destination.SETTINGS -> MainTab.SETTINGS
    }

/**
 * The form for replacing a session the server has stopped accepting.
 *
 * Not a first-run gate — the app opens on the button, signed in or not. This is
 * reached only when a token exists and was rejected, which is a state with
 * nothing to look at: the account is unknown *and* the app cannot pretend the
 * empty list means "no devices", because it knows better.
 *
 * Its own composable so that recovering disposes the form: a captcha fetched
 * before the new login is worthless afterwards, and a ViewModel that outlived
 * this screen would keep a dead picture around.
 *
 * The form's ViewModel is keyed on the session, so signing in and then out does
 * not hand the next person the previous one's login form.
 */
@Composable
private fun StartHost(
    app: KillHuiShengHuoApp,
    onSignedIn: () -> Unit,
) {
    val factory = remember(app, app.session.token) {
        AuthViewModelFactory(repository = app.authRepository)
    }
    val viewModel: AuthViewModel = viewModel(
        key = "start-${app.session.token.orEmpty()}",
        factory = factory,
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.accountName) {
        if (state.accountName.isNotEmpty()) onSignedIn()
    }

    StartScreen(viewModel = viewModel)
}

/**
 * Settings, plus the account form that lives inside it.
 *
 * Signing in here is not a state change the rest of the app has to react to:
 * once the app has been initialized there is no gate to pass, so the account
 * section simply starts showing the name and the device list fills in.
 */
@Composable
private fun SettingsHost(
    app: KillHuiShengHuoApp,
    host: String,
    dark: Boolean,
    updateState: UpdateUiState,
    onCheckUpdate: () -> Unit,
    state: StateFlow<HotWaterUiState>,
    onDeviceSelected: (Device) -> Unit,
    onBindScanned: (String) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onSignOut: () -> Unit,
) {
    val factory = remember(app, app.session.accountName) {
        AuthViewModelFactory(
            repository = app.authRepository,
            initialAccountName = app.session.accountName.orEmpty(),
        )
    }
    val viewModel: AuthViewModel = viewModel(key = "settings-account", factory = factory)
    val authState by viewModel.uiState.collectAsStateWithLifecycle()

    // Collected here, at the leaf, rather than passed in as `devices`/`selected`.
    // The settings screen is the only thing that shows the device list, so the
    // subscription belongs here — see `HotWaterViewModel.sessionExpired` for the
    // other half of the same argument.
    val hotWaterState by state.collectAsStateWithLifecycle()

    SettingsScreen(
        authViewModel = viewModel,
        dark = dark,
        onThemeChange = onThemeChange,
        host = host,
        lastError = authState.errorMessage,
        devices = hotWaterState.devices,
        selected = hotWaterState.selected,
        onDeviceSelected = onDeviceSelected,
        isBinding = hotWaterState.isBinding,
        bindMessage = hotWaterState.bindMessage,
        bindFailed = hotWaterState.bindFailed,
        onBindScanned = onBindScanned,
        updateState = updateState,
        onCheckUpdate = onCheckUpdate,
        onSignOut = {
            viewModel.signOut()
            onSignOut()
        },
    )
}

/**
 * Keeps the system bars in step with the palette.
 *
 * A bare `enableEdgeToEdge()` resolves the icon tint from the *phone's*
 * light/dark setting, not the app's — so a phone in light mode drew dark
 * status-bar icons on a near-black surface and the clock and battery vanished.
 * The bars are therefore painted the surface colour with icons chosen against
 * it, and this runs again whenever the theme changes rather than being decided
 * once in `onCreate`.
 *
 * Keyed on the theme, which is the interesting part. This used to be a
 * `SideEffect`, which runs after *every* successful recomposition — and the
 * scope it lives in recomposes on every device refresh and every busy flip, so
 * the app was re-writing window flags and re-installing an insets listener
 * several times a second to arrive at the answer it already had. `LaunchedEffect`
 * keyed on `dark` runs twice in the app's life: once when it starts and once
 * when somebody changes the theme.
 */
@Composable
private fun SystemBars(dark: Boolean) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    val background = (if (dark) DarkKhsColors else LightKhsColors).surface.toArgb()

    LaunchedEffect(activity, dark, background) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = background,
                darkScrim = background,
                detectDarkMode = { dark },
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = background,
                darkScrim = background,
                detectDarkMode = { dark },
            ),
        )
    }
}

/** Bare host for display, so the screen does not print a full URL. */
private fun hostOf(baseUrl: String): String =
    baseUrl.substringAfter("://", baseUrl).substringBefore('/').ifBlank { baseUrl }
