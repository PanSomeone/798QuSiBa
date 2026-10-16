package com.killhuishenghuo.ui.hotwater

import com.killhuishenghuo.data.model.Device

/**
 * Everything the one-button screen renders.
 *
 * The screen used to be a list of device cards, one action each. It is now a
 * single control, which moves a decision that used to be per-tap into state:
 * *which* device the button acts on. [selected] is that answer, and it is the
 * only thing on this screen the user has to know.
 */
data class HotWaterUiState(
    val isLoadingDevices: Boolean = false,
    val devices: List<Device> = emptyList(),
    /** The device the button controls. Null before devices are known. */
    val selected: Device? = null,
    /** True while a start or stop is in flight, so the button cannot double-fire. */
    val isBusy: Boolean = false,
    /**
     * What this screen believes about the valve, when the device list disagrees.
     *
     * `true` after a start the server accepted, `false` after a stop, `null`
     * while the device list is the only thing that has spoken.
     *
     * **It has to outlive the confirmation, and it has to outrank the list.**
     * Both of those were wrong, and together they produced the bug that wasted
     * two rounds of logs: press 启动, the server returns `code: 0`, the button
     * flips to 关闭 — and then the device list still reports `gene.status = 99`,
     * which this app reads as "never settled, not running", so the button flips
     * back to 启动. The user presses it again. The device is of course already
     * running, so the server answers `-15 设备正在使用` — and the app reports
     * "someone is using it" about a session the user opened themselves seconds
     * ago.
     *
     * `gene.status` is a *billing* field and it lags the valve. The action
     * response does not lag; it is the answer to what was just asked. So it wins
     * until something more authoritative arrives — a device list that agrees the
     * valve is on, or a stop.
     */
    val runningOverride: Boolean? = null,

    /**
     * When the next *start* becomes allowed, or 0 when it already is.
     *
     * A wall-clock deadline rather than a `true`/`false` flag, because a flag has
     * to be cleared by something — a timer, or a recomposition — and until that
     * happens the state is lying about the rule. A deadline is the rule: it is
     * true or false the instant it is asked, with nothing running.
     *
     * [isStartBlocked] is the question; this is only the storage.
     */
    val startBlockedUntilMillis: Long = 0L,

    /**
     * The server has told us this device has a session that has to be settled on
     * the machine itself (`-2 设备运行中,请在设备上点击结算`).
     *
     * Set from a **refusal**, never inferred from `gene.status`. It used to be
     * derived from `status == 99`, and that produced the complaint this field
     * exists to answer: a user logged in, had started nothing, and the screen
     * announced 上次没结算. `99` is the device's resting value and means the
     * server has not said — it is not evidence of an unsettled session, and the
     * app has no business inventing one.
     *
     * Cleared by a successful start or stop, because either one proves there is
     * nothing left to settle.
     */
    val settleRequired: Boolean = false,

    /** Last failure, shown as a line under the button. */
    val errorMessage: String? = null,
    /** True when the last device fetch failed and there is nothing to show. */
    val deviceLoadFailed: Boolean = false,
    /**
     * A scan is being exchanged for a device on this account right now.
     *
     * Its own flag rather than reusing `isBusy`, which belongs to the valve: a
     * bind in flight must not disable the button on the other screen, and a valve
     * in flight must not make the bind row spin.
     */
    val isBinding: Boolean = false,
    /**
     * The sentence the bind block shows after an attempt — success or failure.
     *
     * A separate field from [errorMessage] because most of what it says is not an
     * error: "已添加，按钮现在控制它" is the outcome the user was after, and
     * routing that through a field named `errorMessage` is how a success ends up
     * rendered in red under the wrong heading.
     */
    val bindMessage: String? = null,
    /** Whether [bindMessage] is a failure. Success and failure are both sentences. */
    val bindFailed: Boolean = false,
    /**
     * `code: -99` came back, so the stored token is dead. The screen says so and
     * points at settings rather than offering a retry that would fail the same
     * way.
     */
    val sessionExpired: Boolean = false,
    /** Who is signed in. `null` when unknown. */
    val accountName: String? = null,
) {
    val isEmpty: Boolean get() = !isLoadingDevices && devices.isEmpty()

    /**
     * Whether the valve should be presented as open.
     *
     * [runningOverride] answers first when it has an opinion, because it is a
     * response to this device rather than a summary of its billing state. See
     * its KDoc for what happens when the order is reversed.
     */
    val isRunning: Boolean get() = runningOverride ?: (selected?.isRunning == true)

    /**
     * Whether a *start* is still out of reach after an action just landed.
     *
     * **It guards one direction, not the button.** The first version of this
     * blocked both, and a test caught what that means: press 启动, the server
     * accepts, and for the next three seconds the app refuses to stop the water
     * it just started. Blocking water is never the safe failure.
     *
     * So the rule follows where the money is. A second `dev/start` on a valve
     * that is already open begins a second session — two charges for one shower
     * — which is worth a three-second delay. A stop is idempotent and cannot
     * cost anything that a delay would save, so it is never held back; if the
     * user is standing in the water wanting it off, the app has no business
     * asking them to wait.
     *
     * Not [isBusy] either: nothing is in flight here. The water is already
     * running and the button already says so. What the gap buys is the beat the
     * device needs to write down what just happened before it is asked the
     * opposite.
     *
     * [now] is a parameter rather than a call to `System.currentTimeMillis()`, so
     * this is a pure function of the state and a test can ask the question at any
     * instant without a clock.
     */
    fun isStartBlocked(now: Long): Boolean = now < startBlockedUntilMillis

    /**
     * Whether the last session on this device was never settled.
     *
     * Its own state and not a flavour of "closed", because it is the one state
     * where the button is *guaranteed* to be refused — the server answers
     * `-2 设备运行中,请在设备上点击结算` — and the fix is at the machine rather than
     * in the app. Saying 热水已关 here is what left a user staring at "it says
     * closed, but the server says in use" with nothing to do about it.
     *
     * Suppressed once the valve is known to be on: a device cannot be both
     * waiting to settle and pouring water.
     */
    val needsSettling: Boolean get() = settleRequired && !isRunning

    /** More than one device means the button's target has to be chosen. */
    val needsDeviceChoice: Boolean get() = devices.size > 1

    /**
     * Whether pressing the button has to leave this screen to do anything.
     *
     * Two unrelated causes, one user-visible consequence. A dead token means the
     * app cannot act at all; an account with no devices bound means it has
     * nothing to act *on*. Both are fixed in the same place, by the user, on
     * purpose — so both collapse into one branch rather than two error screens
     * that say "go to settings" in two different sets of words.
     *
     * [deviceLoadFailed] is deliberately *not* here: a failed fetch is a
     * question about the network, not about the account, and it is answered by
     * retrying rather than by reconfiguring anything. The momentary gap while
     * the first fetch is in flight is not here either — [selected] being null
     * during loading would otherwise flip the button to "去设置" and back.
     */
    val needsSetup: Boolean
        get() = !isLoadingDevices && (sessionExpired || selected == null)
}
