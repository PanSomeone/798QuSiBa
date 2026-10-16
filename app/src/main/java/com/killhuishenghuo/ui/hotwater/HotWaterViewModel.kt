package com.killhuishenghuo.ui.hotwater

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.killhuishenghuo.data.local.PreferencesStore
import com.killhuishenghuo.core.log.AppLog
import com.killhuishenghuo.data.model.AppError
import com.killhuishenghuo.data.model.Device
import com.killhuishenghuo.data.model.Outcome
import com.killhuishenghuo.data.remote.ApiResultMapper
import com.killhuishenghuo.data.repository.HuiShengHuoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the single button.
 *
 * The guard against double-firing is the load-bearing part: opening a hot-water
 * valve is a physical side effect and the gateway has no request-id parameter,
 * so a second tap is a second *real* open, not a duplicate of the first. Any tap
 * while a request is in flight is dropped rather than queued — and that now
 * covers the stop direction too, where a duplicate would be an unexpected open.
 */
class HotWaterViewModel(
    private val repository: HuiShengHuoRepository,
    private val preferences: PreferencesStore,
    private val accountName: String? = null,
    /**
     * The clock the action gap is measured against.
     *
     * A seam for tests only — every production caller takes the default. It
     * exists because the gap is a *timed* rule and the honest way to test a timed
     * rule is to ask the question at a controlled instant, rather than to hope a
     * coroutine has not been started yet.
     */
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HotWaterUiState(accountName = accountName))
    val uiState: StateFlow<HotWaterUiState> = _uiState.asStateFlow()

    /**
     * The one field of [uiState] that decides *which screen exists*.
     *
     * Separate because of where it is read rather than what it means. The root
     * has to know whether the session died before it can choose between the
     * button and the sign-in form, and if it read the whole state to find out,
     * then every device refresh and every busy flip would invalidate the root —
     * and with it both screens and the tab bar. Narrowing the observable to the
     * one boolean keeps that scope recomposing for exactly the event that
     * concerns it.
     */
    val sessionExpired: StateFlow<Boolean> = uiState
        .map { it.sessionExpired }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // There used to be a `Channel<HotWaterEvent>` here — started/stopped/failed
    // confirmations, plus a session-expired announcement — and it was the right
    // shape for a screen that needed to be *told* something once.
    //
    // Nothing ever needed telling. The screen's collector matched all four events
    // and did `Unit` against every one of them: the button's own label, the state
    // line and the device list already said everything the events carried, and
    // the session-expired one drove routing off `sessionExpired` regardless. A
    // second copy of the state, delivered to a collector that threw it away, is
    // not a confirmation — it is a contract with no caller, kept alive by tests
    // that asserted on it. Those assertions now read the state.

    init {
        refreshDevices()
    }

    fun refreshDevices() {
        if (_uiState.value.isLoadingDevices) return

        _uiState.update { it.copy(isLoadingDevices = true, errorMessage = null) }

        viewModelScope.launch {
            when (val result = repository.loadDevices()) {
                is Outcome.Success -> _uiState.update { state ->
                    val selected = resolveSelection(
                        devices = result.value,
                        rememberedId = preferences.selectedDeviceId ?: state.selected?.deviceId,
                    )
                    // Persist what the button will actually act on. The stored id is
                    // matched against the fresh list rather than trusted, because a
                    // device can be unbound — or a different account signed in on the
                    // same install — and acting on a dead id would send `dev/start`
                    // for a device this account does not own.
                    preferences.selectedDeviceId = selected?.deviceId

                    // Every field is logged, not just the count. The bug this log
                    // was built for was a screen that said the water was off while
                    // the server said it was on, and `status` is the field that
                    // decides which of those the screen believes.
                    AppLog.log(
                        "Devices",
                        "loaded=${result.value.size} selected=${selected?.deviceId ?: "无"} " +
                            "name=${selected?.displayName ?: "无"} status=${selected?.status ?: -1}",
                    )

                    state.copy(
                        isLoadingDevices = false,
                        devices = result.value,
                        selected = selected,
                        deviceLoadFailed = false,
                        sessionExpired = false,
                    )
                }
                is Outcome.Failure -> {
                    val expired = result.error is AppError.Unauthorized
                    AppLog.log(
                        "Devices",
                        "load failed expired=$expired error=${result.error::class.simpleName}",
                    )
                    _uiState.update {
                        it.copy(
                            isLoadingDevices = false,
                            deviceLoadFailed = true,
                            sessionExpired = expired,
                            errorMessage = result.error.toChinese(),
                        )
                    }
                    // The flag is the announcement. `sessionExpired` is a flow the
                    // root collects, so a dead session found here reaches the top
                    // of the app without anything having to carry a message.
                }
            }
        }
    }

    /** Points the button at [device]. Persisted, because the button is one thing. */
    fun selectDevice(device: Device) {
        if (_uiState.value.isBusy) return

        preferences.selectedDeviceId = device.deviceId
        _uiState.update { it.copy(selected = device, errorMessage = null) }
    }

    /**
     * The one action: open the valve if it is closed, close it if it is open.
     *
     * One entry point rather than two, because the button must never offer the
     * action that does not apply — sending `dev/start` to a valve that is already
     * open starts a *new* session and can bill a second time.
     */
    fun toggleWater() {
        val state = _uiState.value
        val device = state.selected ?: return
        if (state.isBusy) return

        // The decision, before it is made. Which branch was taken is the first
        // question about any wrong action, and it is not recoverable afterwards:
        // both branches end in a request.
        AppLog.log(
            "Button",
            "toggle device=${device.deviceId} isRunning=${state.isRunning} " +
                "status=${device.status} override=${state.runningOverride}",
        )

        if (state.isRunning) {
            closeWater(device)
            return
        }

        // The gap applies to starting only. See [HotWaterUiState.isStartBlocked]
        // for why a stop is never held back — briefly, a second start bills a
        // second session and a stop costs nothing, so blocking the stop is the
        // one failure mode with nothing to gain.
        if (state.isStartBlocked(now())) {
            AppLog.log("Button", "start blocked until=${state.startBlockedUntilMillis} now=${now()}")
            return
        }

        openWater(device)
    }

    private fun openWater(device: Device) {
        _uiState.update { it.copy(isBusy = true, errorMessage = null) }

        viewModelScope.launch {
            val result = repository.openHotWater(device = device, args = ARGS)

            when (result) {
                is Outcome.Success -> {
                    // `code: 0` is the whole acknowledgement — there is no order
                    // object to check, and re-reading the device immediately
                    // would race the server's own state update.
                    //
                    // So this records what the server just agreed to. The device
                    // list will keep saying `gene.status = 99`, because that field
                    // does not track the valve — and the button has to keep
                    // reading this instead. See [HotWaterUiState.runningOverride].
                    AppLog.log("Water", "open OK device=${device.deviceId}")
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            runningOverride = true,
                            // A start the server accepted proves there was nothing
                            // left to settle, so this is one of the two things
                            // that clear the flag. Without it the screen could sit
                            // on 上次没结算 forever after one -2.
                            settleRequired = false,
                        )
                    }
                    startActionGap()
                }

                is Outcome.Failure -> {
                    AppLog.log("Water", "open FAILED device=${device.deviceId} error=${result.error}")
                    finishWithFailure(result.error)
                }
            }
        }
    }

    private fun closeWater(device: Device) {
        _uiState.update { it.copy(isBusy = true, errorMessage = null) }

        viewModelScope.launch {
            when (val result = repository.closeHotWater(device)) {
                is Outcome.Success -> {
                    AppLog.log(
                        "Water",
                        "close OK device=${device.deviceId} " +
                            "settle=${ApiResultMapper.LastAction.alreadyOpen}",
                    )
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            runningOverride = false,
                            // What the server just said about settling, and only
                            // it. `gene.status` cannot answer this question — see
                            // `Device.STATUS_UNREPORTED`.
                            settleRequired = ApiResultMapper.LastAction.alreadyOpen,
                            // The row has to stop claiming to be running. A stale
                            // `isRunning` would leave the button offering "关闭"
                            // for water that is already off.
                            selected = it.selected?.copy(status = Device.STATUS_IDLE),
                        )
                    }
                    // The gap is left alone. It is measured from the last *start*,
                    // and a stop is not one — clearing it here would let a user
                    // stop and immediately restart, which is the double-session
                    // hazard the gap exists for, arriving by a different route.
                }

                is Outcome.Failure -> {
                    AppLog.log("Water", "close FAILED device=${device.deviceId} error=${result.error}")
                    finishWithFailure(result.error)
                }
            }
        }
    }

    /**
     * Blocks the next *start* for a beat after an action lands.
     *
     * The device is a small computer on the end of a slow link, and it writes
     * down what just happened before it can be told the opposite. Two taps a few
     * hundred milliseconds apart are two `dev/start` calls, and the second one
     * comes back `-15 设备正在使用` — about the user's own session.
     *
     * A deadline rather than a coroutine that flips a flag back: nothing has to
     * run for the gap to be in force, so there is no window in which the button
     * is live while the timer has not started yet. It also costs nothing when the
     * screen is asleep, because there is no timer.
     *
     * Only ever called after an *open*. There used to be a call here after a stop
     * too, and it produced the worst version of this rule: the app refusing to
     * start the water again for three seconds after it had just stopped it, with
     * nothing on screen to say why.
     */
    private fun startActionGap() {
        _uiState.update { it.copy(startBlockedUntilMillis = now() + ACTION_GAP_MILLIS) }
    }

    private suspend fun finishWithFailure(error: AppError) {
        val message = error.toChinese()
        val expired = error is AppError.Unauthorized
        // The rendered sentence and the error that produced it, together. The
        // whole reason this log exists is that the sentence alone was not enough
        // to tell a real refusal from a misread state.
        AppLog.log("Water", "failure expired=$expired rendered=$message raw=$error")
        _uiState.update {
            it.copy(
                isBusy = false,
                errorMessage = message,
                sessionExpired = expired,
            )
        }
        // A dead session found while opening the valve is carried by
        // `sessionExpired`, which the root collects and routes on. There used to
        // be an event alongside it saying the same thing.
    }

    /** Called once an error line has been acknowledged. */
    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Called when the "water started" confirmation has been delivered.
     *
     * It used to clear the running flag, which is what put the button back to
     * 启动 a few seconds after a successful start while the device list still
     * said `99`. The confirmation has been shown; what the server agreed to has
     * not stopped being true.
     */
    fun acknowledgeRunningConfirmation() = Unit

    private companion object {
        /**
         * How long the button stays shut after an action lands.
         *
         * Three seconds, chosen by the person using this: long enough that a
         * double tap cannot become two sessions, short enough that nobody
         * notices waiting. It is not a debounce — the button is disabled, not
         * silently dropping taps, because a tap that vanishes is a tap the user
         * will make again.
         */
        const val ACTION_GAP_MILLIS = 3_000L
        /**
         * The `args` query value this screen sends.
         *
         * A dormitory shower (`dtype` 5/6/8) takes neither a mode nor a channel,
         * so it sends the empty string — a *present but empty* parameter, not an
         * omitted one, which is why this is a value and not a null.
         *
         * This used to be `startArgsFor(device)` returning the same call on both
         * sides of an `isPassageOnly` branch. That branch was a false seam: it
         * read as though two cases were handled, but no device could reach a
         * different answer, so it advertised a capability the screen does not
         * have. Devices that genuinely carry a mode or a channel are not
         * supported yet, and when they are, the mode has to come from
         * `ui/app/dev/status`'s `parts[]` rather than from a flag on the device.
         */
        const val ARGS = ""

        /**
         * Which device the button should control, given a fresh list.
         *
         * Preference order: the remembered one if the account still has it, then
         * the single device if there is exactly one, then the first one that is
         * already running — because a valve that is open is the one the user most
         * likely came here to close. Otherwise the first device, and the screen
         * offers the choice explicitly.
         *
         * [rememberedId] is passed in rather than read here, because there are two
         * places a memory can live — the store (which survives the process) and
         * `state.selected` (which survives only a refresh) — and the caller is the
         * one that knows both.
         *
         * This function is pure on purpose: a test asserts the whole preference
         * order, and a lookup that reached into the store could not be checked
         * against a hand-written list without one.
         */
        fun resolveSelection(devices: List<Device>, rememberedId: String?): Device? {
            if (devices.isEmpty()) return null

            return devices.firstOrNull { it.deviceId == rememberedId }
                ?: devices.singleOrNull()
                ?: devices.firstOrNull { it.isRunning }
                ?: devices.first()
        }
    }
}
