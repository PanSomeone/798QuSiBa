package com.killhuishenghuo.ui.hotwater

import com.killhuishenghuo.data.local.FakePreferencesStore
import com.killhuishenghuo.data.model.AppError
import com.killhuishenghuo.data.model.Device
import com.killhuishenghuo.data.model.Outcome
import com.killhuishenghuo.data.remote.ApiResultMapper.LastAction
import com.killhuishenghuo.data.repository.HuiShengHuoRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The hot-water screen's one button, and the state behind it.
 *
 * The screen used to be a card per device with one action each; it is now a single
 * control, so *which* device it acts on moved from being an argument of every tap
 * into [HotWaterUiState.selected], and "open" and "close" stopped being two
 * buttons. That is why the tests below are about two things a per-row UI could not
 * get wrong: which device the button resolved to, and whether a tap reached the
 * gateway at all.
 *
 * The second of those is a *physical* property, not a performance one. `dev/start`
 * carries no request-id parameter, so there is nothing the server can deduplicate
 * on: a second request is a second real valve opening and a second wallet debit.
 * The guard is therefore asserted from the gateway's side — how many calls it
 * received — rather than from the UI's, which is what a user would have seen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HotWaterViewModelTest {

    private val repository = FakeHotWaterRepository()
    private val preferences = FakePreferencesStore()

    /**
     * The clock the action gap is measured against.
     *
     * Advanced by hand, because the gap is a *timed* rule and the only honest way
     * to test one is to ask the question at a controlled instant. Driving it from
     * the test scheduler instead would make the assertion depend on whether a
     * coroutine had been started yet, which is not the rule — and that version of
     * this test failed for exactly that reason.
     */
    private var clock = 1_000_000L
    private val now: () -> Long = { clock }

    /**
     * The one dispatcher every test shares.
     *
     * Unconfined so `viewModelScope.launch` runs eagerly to its first real
     * suspension point, and *the same instance* handed to `runTest` so that
     * `advanceUntilIdle()` drains the queue the ViewModel actually runs on. Two
     * dispatchers would mean two schedulers, and a test would then be advancing
     * one while the ViewModel waited on the other.
     */
    private lateinit var mainDispatcher: TestDispatcher

    @Before
    fun setUp() {
        mainDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        // `LastAction` is a module-level slot that the mapper writes and the
        // ViewModel reads, so it survives between tests. Resetting it here is what
        // keeps a test that deliberately raises the settling flag (see
        // `a swallowed settle refusal on close is what raises the settling flag`)
        // from leaking 上次没结算 into every test that runs after it.
        LastAction.record(swallowed = false, code = 0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------- the one button

    @Test
    fun `repeated taps on the button act exactly once while a request is in flight`() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val viewModel = loadedViewModel(listOf(device("d1", name = "3号淋浴器")))
        // The gate is wired up *before* the first tap. It used to be set after
        // `loadedViewModel`, which was fine when only `openHotWater` was gated —
        // but the load path shares the same fake, so a later assignment would
        // land after the request it was meant to freeze.
        repository.openGate = gate

        repeat(4) { viewModel.toggleWater() }

        // THE assertion of this file. `dev/start` has no idempotency key: there is
        // nothing the server can deduplicate on, so a second request is a second
        // real valve opening and a second wallet debit, not a duplicate of the
        // first. Impatient users tap a button until something happens, and this is
        // the only thing standing between that habit and four open valves.
        assertEquals("four taps must be one valve opening", 1, repository.openCalls.size)
        assertTrue("the button must be disabled for the whole request", viewModel.uiState.value.isBusy)
        // "A dropped tap must not become a deferred one" — asserted on the thing
        // that would show it. The three taps after the first must have left no
        // trace at all: no second request queued behind the gate, and no
        // optimistic running flag, which is what a queued tap would eventually
        // produce.
        assertFalse("a dropped tap must not claim the valve is open", viewModel.uiState.value.isRunning)

        gate.complete(Unit)
        advanceUntilIdle()

        // The guard releases once the first request has finished: this is a guard
        // against concurrent taps, not a one-shot latch.
        val state = viewModel.uiState.value
        assertFalse("the pending state must clear when the request lands", state.isBusy)
        assertTrue("the accepted start is what the button reports", state.isRunning)

        // And starting is shut for a beat, so the same finger cannot send a
        // second `dev/start` — which is a second real session, and a second
        // charge — before the device has written the first one down.
        assertTrue("the gap after a start is part of the start", state.isStartBlocked(clock))

        // The *stop* direction is deliberately not covered. Blocking both is what
        // the first version did, and it meant that for three seconds after a
        // successful start the app refused to turn off the water it had just
        // turned on.
        repository.openGate = null
        repository.closeAnswer = { Outcome.Success(Unit) }
        viewModel.refreshDevices()
        advanceUntilIdle()

        viewModel.toggleWater() // a stop: the override says the valve is open
        advanceUntilIdle()
        assertEquals(
            "a stop must never wait for the gap",
            listOf("d1"),
            repository.closeCalls.map { it.device.deviceId },
        )

        // But the gap is not cancelled by the stop either — it is measured from
        // the last *start*, and it still has to protect the next one.
        assertTrue("stopping does not clear the gap", viewModel.uiState.value.isStartBlocked(clock))

        clock += ACTION_GAP + 1L
        runCurrent()
        assertFalse(viewModel.uiState.value.isStartBlocked(clock))

        // Past the gap the button is a live start again, and a live start is only
        // the *right* command if the state knows the valve is shut. The device
        // list says 99, which this app reads as "the server did not say" — so the
        // accepted stop is the only thing standing between a stale 99 and a second
        // `dev/start`. This asserts it is still doing that job after a refresh has
        // been through the state.
        assertFalse("the list alone would say closed", viewModel.uiState.value.selected!!.isRunning)
        repository.openCalls.clear()
        repository.loadAnswer = { Outcome.Success(listOf(device("d1", name = "3号淋浴器", status = UNREPORTED_STATUS))) }
        viewModel.refreshDevices()
        advanceUntilIdle()
        assertEquals("a refresh must not itself act", 0, repository.openCalls.size)

        viewModel.toggleWater()
        advanceUntilIdle()
        assertEquals("the accepted stop still outranks a stale 99", 1, repository.openCalls.size)
    }

    @Test
    fun `tapping while a stop is in flight does not start anything again`() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        val running = device("d1", status = RUNNING_STATUS)
        val viewModel = loadedViewModel(listOf(running))
        repository.closeGate = gate
        repository.explodeOnOpen = "an open must never be sent while a close is in flight"

        viewModel.toggleWater()
        assertEquals(listOf("d1"), repository.closeCalls.map { it.device.deviceId })
        assertTrue(viewModel.uiState.value.isBusy)

        // The dangerous direction. For a stop, an impatient user's second tap is
        // also a second *physical* event — the valve was never meant to open, so
        // dropping the tap is what keeps "off" off. A `toggleWater()` that
        // re-read `isRunning` here would find the stale "running" flag cleared by
        // the closed request and send `dev/start`.
        repeat(3) { viewModel.toggleWater() }

        assertEquals("three taps must still be one stop", 1, repository.closeCalls.size)
        assertTrue("no start may follow a stop", repository.openCalls.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isBusy)
        // The row must stop claiming to be running, or the button would keep
        // offering "关闭" for water that is already off.
        assertFalse(state.isRunning)
        // The stop is confirmed by the state line turning back into a cold
        // device's, which is what the user actually reads — the old assertion here
        // was on a `WaterStopped` event whose only consumer did `Unit` with it.
        assertEquals("热水已关", state.statusLine)
    }

    // -------------------------------------------------------------- settling

    /**
     * The bug the user reported on 2026-08-19: "刚登录进去为什么就显示 上次未结算".
     *
     * An earlier version read `gene.status = 99` as "a session is open and needs
     * settling". The real-device logs killed that: 99 shows up in the device list
     * both while the valve is open and while it is shut, so it is a non-answer, not
     * a session statement. Nothing has been pressed at this point, so the screen
     * must read like a fresh cold device — no outstanding session, valve closed.
     */
    @Test
    fun `status 99 on load does not invent an outstanding session`() = runTest(mainDispatcher) {
        val state = loadedViewModel(listOf(device("d1", status = UNREPORTED_STATUS))).uiState.value

        assertFalse("nothing was started", state.needsSettling)
        assertEquals("热水已关", state.statusLine)
        assertEquals("一键启动热水", state.buttonLabel)
    }

    @Test
    fun `an idle device needs no settling`() = runTest(mainDispatcher) {
        val state = loadedViewModel(listOf(device("d1", status = Device.STATUS_IDLE))).uiState.value

        assertFalse(state.isRunning)
        assertFalse(state.needsSettling)
    }

    /**
     * The one thing that *does* mean "settle this": the server refusing a stop
     * with `-2 设备运行中,请在设备上点击结算`.
     *
     * It is a statement about a session, made by the only component that knows, at
     * the only moment it can be asked. The stop still counts as done — the valve
     * is shut — but the session it left open is not, and that is what the user has
     * to go clear at the machine.
     *
     * The flag is seeded through `LastAction` rather than through a status code,
     * because that is where it now comes from: the mapper writes it when it
     * swallows the refusal, and `HotWaterViewModel` copies it onto the state. The
     * fake repository in this file never touches the mapper, which is exactly why
     * an earlier version of this test could pass while asserting a rule that the
     * real app did not implement.
     */
    @Test
    fun `a swallowed settle refusal on close is what raises the settling flag`() = runTest(mainDispatcher) {
        LastAction.alreadyOpen = true
        val viewModel = loadedViewModel(listOf(device("d1", status = RUNNING_STATUS)))

        viewModel.toggleWater()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("the water really is off", state.isRunning)
        // The stop still counts as done — the valve is shut — which is the point:
        // a refusal the mapper swallowed must not read as a failure here.
        assertTrue("the stop is still done", state.settleRequired)
        assertEquals("上次没结算", state.statusLine)
    }

    /**
     * And a start the server accepted clears it, which is the only way out short
     * of a clean stop. Without this the screen could sit on 上次没结算 forever
     * after a single `-2`, which is a worse bug than the one it replaced.
     */
    @Test
    fun `a successful start clears the settling flag`() = runTest(mainDispatcher) {
        LastAction.alreadyOpen = true
        val viewModel = loadedViewModel(listOf(device("d1", status = Device.STATUS_IDLE)))

        viewModel.toggleWater() // starts, and the accepted start settles it
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isRunning)
        assertFalse("the server accepted a start, so nothing is outstanding", viewModel.uiState.value.needsSettling)
        assertEquals("热水已开", viewModel.uiState.value.statusLine)
    }

    @Test
    fun `an idle device still reads as closed`() = runTest(mainDispatcher) {
        val state = loadedViewModel(listOf(device("d1", status = Device.STATUS_IDLE))).uiState.value

        assertEquals("热水已关", state.statusLine)
        assertEquals("一键启动热水", state.buttonLabel)
    }

    // ------------------------------------------------------------ device load

    @Test
    fun `loadDevices binds the account's devices and selects the only one`() = runTest(mainDispatcher) {
        val viewModel = loadedViewModel(listOf(device("d1", name = "3号淋浴器")))

        val state = viewModel.uiState.value
        assertEquals(listOf("d1"), state.devices.map { it.deviceId })
        assertEquals("d1", state.selected?.deviceId)
        assertFalse(state.isLoadingDevices)
        assertFalse(state.deviceLoadFailed)
        assertNull(state.errorMessage)
        // Asking "which device?" when there is exactly one is a question with one
        // answer — and the answer is remembered, so the next launch is instant.
        assertEquals("d1", preferences.selectedDeviceId)
        assertFalse(state.needsDeviceChoice)
    }

    @Test
    fun `a remembered device id that the account still has wins the selection`() = runTest(mainDispatcher) {
        // d2 is both remembered and already running, so it satisfies two rules at
        // once and cannot pass by accident.
        preferences.selectedDeviceId = "d2"
        val viewModel = loadedViewModel(
            listOf(device("d1"), device("d2", status = RUNNING_STATUS), device("d3")),
        )

        val state = viewModel.uiState.value
        assertEquals("d2", state.selected?.deviceId)
        assertEquals("d2", preferences.selectedDeviceId)
        assertTrue(state.needsDeviceChoice)
    }

    @Test
    fun `without a memory the first device already running is selected`() = runTest(mainDispatcher) {
        val viewModel = loadedViewModel(
            listOf(device("d1"), device("d2"), device("d3", status = RUNNING_STATUS)),
        )

        // A valve that is open is the one the user most likely came here to close,
        // so it wins over list order.
        assertEquals("d3", viewModel.uiState.value.selected?.deviceId)
    }

    @Test
    fun `with several idle devices and no memory the first one is selected`() = runTest(mainDispatcher) {
        val viewModel = loadedViewModel(listOf(device("d1"), device("d2"), device("d3")))

        // Arbitrary, but not random: a `first()` is what makes the screen usable
        // before the user has chosen, and the choice is still offered explicitly.
        assertEquals("d1", viewModel.uiState.value.selected?.deviceId)
        assertTrue(viewModel.uiState.value.needsDeviceChoice)
    }

    @Test
    fun `a remembered device id that is gone from the account is not trusted`() = runTest(mainDispatcher) {
        // The hole this closes: the stored preference outlives the binding, so a
        // device unbound — or an account switched on the same install — leaves an
        // id that no longer exists. Acting on it would send `dev/start` for a
        // device this account does not own.
        preferences.selectedDeviceId = "ghost"
        val viewModel = loadedViewModel(listOf(device("d1"), device("d2")))
        advanceUntilIdle()

        assertEquals("d1", viewModel.uiState.value.selected?.deviceId)
        // And the dead id is overwritten rather than left to mis-resolve again.
        assertEquals("d1", preferences.selectedDeviceId)
    }

    @Test
    fun `a remembered device id that is gone falls back to the running device`() = runTest(mainDispatcher) {
        preferences.selectedDeviceId = "ghost"
        val viewModel = loadedViewModel(listOf(device("d1"), device("d2", status = RUNNING_STATUS)))

        // The fallback is the whole preference order, not "the first device": the
        // running one still outranks list order.
        assertEquals("d2", viewModel.uiState.value.selected?.deviceId)
    }

    @Test
    fun `a second device load cannot start while one is in flight`() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        repository.loadAnswer = { Outcome.Success(listOf(device("d1"))) }
        repository.loadGate = gate

        // The constructor already kicked off the first load, which is parked on the
        // gate; the initial state has to say so, or the screen would show an empty
        // account while it is in fact still asking.
        val viewModel = HotWaterViewModel(repository, preferences, now = now)
        assertTrue(viewModel.uiState.value.isLoadingDevices)

        viewModel.refreshDevices()
        viewModel.refreshDevices()
        assertEquals("a queued duplicate load would double every request", 1, repository.loadCalls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("d1"), viewModel.uiState.value.devices.map { it.deviceId })

        // The guard releases once the first load has finished.
        repository.loadGate = null
        viewModel.refreshDevices()
        advanceUntilIdle()
        assertEquals(2, repository.loadCalls)
    }

    @Test
    fun `the empty state is true only once a load has finished with no devices`() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        repository.loadAnswer = { Outcome.Success(emptyList()) }
        repository.loadGate = gate

        val viewModel = HotWaterViewModel(repository, preferences, now = now)

        // Mid-flight is not empty: "this account has no device" and "we have not
        // asked yet" are different screens, and the second one must not claim the
        // first.
        assertTrue(viewModel.uiState.value.isLoadingDevices)
        assertFalse("loading is not empty", viewModel.uiState.value.isEmpty)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue("a finished load with no devices is empty", viewModel.uiState.value.isEmpty)

        // And a populated account is never empty.
        repository.loadGate = null
        repository.loadAnswer = { Outcome.Success(listOf(device("d1"))) }
        viewModel.refreshDevices()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isEmpty)
    }

    // -------------------------------------------------------------- toggling

    @Test
    fun `toggle opens a device that is not running and closes one that is`() = runTest(mainDispatcher) {
        val viewModel = loadedViewModel(listOf(device("d1")))
        val d1 = viewModel.uiState.value.selected!!
        assertFalse(d1.isRunning)

        viewModel.toggleWater()
        advanceUntilIdle()
        assertEquals(listOf("d1"), repository.openCalls.map { it.device.deviceId })
        assertTrue("a stop must not be sent to a closed valve", repository.closeCalls.isEmpty())

        // The screen re-reads the server's flag; a device reported running is the
        // one the button now has to close.
        repository.loadAnswer = { Outcome.Success(listOf(d1.copy(status = RUNNING_STATUS))) }
        viewModel.refreshDevices()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isRunning)

        viewModel.toggleWater()
        advanceUntilIdle()
        assertEquals(listOf("d1"), repository.closeCalls.map { it.device.deviceId })
        // Still one open: sending `dev/start` to an already-open valve starts a new
        // session and can bill a second time.
        assertEquals(1, repository.openCalls.size)
    }

    @Test
    fun `a successful open is reported by the running flag and not by anything else`() = runTest(mainDispatcher) {
        val viewModel = loadedViewModel(listOf(device("d1", name = "3号淋浴器")))

        viewModel.toggleWater()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isRunning)
        assertFalse(state.isBusy)
        assertNull(state.errorMessage)
        assertEquals("热水已开", state.statusLine)
    }

    @Test
    fun `a successful stop is reported by the running flag and not by anything else`() = runTest(mainDispatcher) {
        val viewModel = loadedViewModel(listOf(device("d1", name = "3号淋浴器", status = RUNNING_STATUS)))

        viewModel.toggleWater()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isRunning)
        assertFalse(state.isBusy)
        assertEquals("热水已关", state.statusLine)
    }

    @Test
    fun `a failed open shows the Chinese sentence and leaves the button usable`() = runTest(mainDispatcher) {
        val viewModel = loadedViewModel(listOf(device("d1")))
        repository.openAnswer = { Outcome.Failure(AppError.RateLimited) }

        viewModel.toggleWater()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(AppError.RateLimited.toChinese(), state.errorMessage)
        assertFalse(state.sessionExpired)
        // The one that matters for a safety guard: if a failure left `isBusy` set,
        // the button would be dead forever and the user would have no way to open
        // the valve again.
        assertFalse("a failure must not weld the button shut", state.isBusy)
        assertFalse(state.isRunning)
        assertFalse("a failed action does not earn the gap", state.isStartBlocked(clock))

        // And the retry really does go out, immediately: the gap belongs to
        // actions that landed, and a refusal must stay retryable.
        repository.openAnswer = { Outcome.Success(Unit) }
        viewModel.toggleWater()
        advanceUntilIdle()
        assertEquals(2, repository.openCalls.size)
    }

    @Test
    fun `a failed open on a dead session flags the session and says so`() = runTest(mainDispatcher) {
        val viewModel = loadedViewModel(listOf(device("d1")))
        repository.openAnswer = { Outcome.Failure(AppError.Unauthorized) }

        viewModel.toggleWater()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.sessionExpired)
        assertEquals(AppError.Unauthorized.toChinese(), state.errorMessage)
        // Both halves, asserted together, because they are what the two consumers
        // read: the root chooses the sign-in form off `sessionExpired`, and the
        // screen prints `errorMessage`. There used to be a pair of events sent
        // alongside these (`Failed`, then `SessionExpired`) and a test asserting
        // the pair — but the only collector did `Unit` with both, so the events
        // were a second copy of a fact the state already carried. The *behaviour*
        // they stood for is still here and still the reason the flag matters: a
        // dead session discovered while opening the valve has to reach the host,
        // not just the panel that happens to be on screen.
        assertFalse(state.isBusy)
    }

    // ------------------------------------------------------------ one-shot UI

    @Test
    fun `dismissError clears the banner and nothing else`() = runTest(mainDispatcher) {
        val viewModel = loadedViewModel(listOf(device("d1"), device("d2", name = "直饮机", dtype = 5)))
        repository.openAnswer = { Outcome.Failure(AppError.RateLimited) }
        viewModel.toggleWater()
        advanceUntilIdle()
        assertEquals(AppError.RateLimited.toChinese(), viewModel.uiState.value.errorMessage)

        viewModel.dismissError()

        val state = viewModel.uiState.value
        assertNull(state.errorMessage)
        // The list, the chosen target and the running confirmation all survive: the
        // banner is the only thing being dismissed.
        assertEquals(listOf("d1", "d2"), state.devices.map { it.deviceId })
        assertEquals("d1", state.selected?.deviceId)
        assertFalse(state.isBusy)
    }

    @Test
    fun `an accepted start outranks a device list that still says 99`() = runTest(mainDispatcher) {
        // The bug this pins down, in the order it happened on the phone:
        //
        //   1. the device list arrives and says `gene.status = 99`, which this app
        //      reads as "the server did not say which way the valve is";
        //   2. the user presses 启动, the server answers `code: 0`, the button
        //      becomes 关闭;
        //   3. the list is read again and still says 99 — so the button went back
        //      to 启动;
        //   4. the user presses it again, and the server answers `-15 设备正在使用`
        //      about the session they opened themselves in step 2.
        //
        // `gene.status` is a lagging field and the action response is not, so the
        // action response wins. That is the entire job of `runningOverride`.
        val viewModel = loadedViewModel(listOf(device("d1", status = UNREPORTED_STATUS)))
        assertFalse("99 is not a running valve", viewModel.uiState.value.isRunning)

        viewModel.toggleWater()
        advanceUntilIdle()
        assertTrue("the accepted start is believed", viewModel.uiState.value.isRunning)
        assertEquals("关闭热水", viewModel.uiState.value.buttonLabel)
        assertFalse(
            "a device cannot be both waiting to settle and pouring water",
            viewModel.uiState.value.needsSettling,
        )

        // The re-read is the step that used to undo it. Same payload as step 1.
        repository.loadAnswer = { Outcome.Success(listOf(device("d1", status = UNREPORTED_STATUS))) }
        viewModel.refreshDevices()
        advanceUntilIdle()

        assertTrue("a refresh must not flip the button back", viewModel.uiState.value.isRunning)
        assertEquals("关闭热水", viewModel.uiState.value.buttonLabel)

        // And the way out is the button, not a second start.
        clock += ACTION_GAP + 1L
        repository.closeAnswer = { Outcome.Success(Unit) }
        viewModel.toggleWater()
        advanceUntilIdle()
        assertEquals(listOf("d1"), repository.closeCalls.map { it.device.deviceId })
        assertEquals("no second valve opening", 1, repository.openCalls.size)
    }

    @Test
    fun `an accepted stop outranks a device list that still says running`() = runTest(mainDispatcher) {
        // The mirror image, and the reason the override is a tri-state rather
        // than a one-way latch: after a stop the button must not keep offering
        // 关闭 for water that is already off, even if the list has not caught up.
        val viewModel = loadedViewModel(listOf(device("d1", status = RUNNING_STATUS)))
        assertTrue(viewModel.uiState.value.isRunning)

        viewModel.toggleWater()
        advanceUntilIdle()
        assertFalse("the accepted stop is believed", viewModel.uiState.value.isRunning)
        assertEquals("一键启动热水", viewModel.uiState.value.buttonLabel)

        repository.loadAnswer = { Outcome.Success(listOf(device("d1", status = RUNNING_STATUS))) }
        viewModel.refreshDevices()
        advanceUntilIdle()

        assertFalse("a refresh must not resurrect the valve", viewModel.uiState.value.isRunning)
        assertEquals("一键启动热水", viewModel.uiState.value.buttonLabel)
    }

    @Test
    fun `the gap is measured from the last start and does not stack`() = runTest(mainDispatcher) {
        val viewModel = loadedViewModel(listOf(device("d1")))
        repository.closeAnswer = { Outcome.Success(Unit) }

        viewModel.toggleWater()
        advanceUntilIdle()
        assertTrue("starting arms the gap", viewModel.uiState.value.isStartBlocked(clock))

        // Past the first gap, so a start is allowed again.
        clock += ACTION_GAP + 1L
        assertFalse(viewModel.uiState.value.isStartBlocked(clock))

        // Stop, then start. Both must go through: the stop is never held back,
        // and the start is past the gap. If either were dropped this assert
        // would see fewer than two calls.
        viewModel.toggleWater()
        advanceUntilIdle()
        clock += ACTION_GAP + 1L
        viewModel.toggleWater()
        advanceUntilIdle()

        assertEquals("one start, then a stop, then a start again", 2, repository.openCalls.size)
        assertEquals("the stop was not held back either", 1, repository.closeCalls.size)

        // The second start re-arms the gap from *now*, not from the first start
        // — otherwise the gaps would accumulate and the button would be shut for
        // longer the more the user used it.
        clock += ACTION_GAP - 1L
        assertTrue("re-armed from the second start", viewModel.uiState.value.isStartBlocked(clock))
        clock += 2L
        assertFalse(viewModel.uiState.value.isStartBlocked(clock))
    }

    @Test
    fun `the gap blocks a start and never a stop`() = runTest(mainDispatcher) {
        // The rule, stated as a property. A second start bills a second session;
        // a stop is idempotent and cannot cost anything a delay would save. So
        // the gap guards exactly one direction, and this test fails if it ever
        // guards both again — which is the shape the first version shipped with.
        val open = loadedViewModel(listOf(device("d1")))
        open.toggleWater()
        advanceUntilIdle()

        assertTrue("a start inside the gap is refused", open.uiState.value.isStartBlocked(clock))
        assertTrue("the valve the server opened is reported open", open.uiState.value.isRunning)
        assertEquals("一键启动热水 is wrong now", "关闭热水", open.uiState.value.buttonLabel)
        assertTrue("no second start", repository.openCalls.size == 1)

        // Same instant, opposite direction.
        repository.closeAnswer = { Outcome.Success(Unit) }
        open.toggleWater()
        advanceUntilIdle()
        assertEquals(
            "a stop inside the gap must go through",
            listOf("d1"),
            repository.closeCalls.map { it.device.deviceId },
        )
    }

    @Test
    fun `selecting a device repoints the button and is remembered`() = runTest(mainDispatcher) {
        val viewModel = loadedViewModel(listOf(device("d1"), device("d2", name = "直饮机", dtype = 5)))
        assertEquals("d1", viewModel.uiState.value.selected?.deviceId)

        viewModel.selectDevice(device("d2", name = "直饮机", dtype = 5))

        assertEquals("d2", viewModel.uiState.value.selected?.deviceId)
        assertEquals("the pick is written through to the store", "d2", preferences.selectedDeviceId)

        // The pick has to survive the process, not just this ViewModel. A second
        // construction is what a relaunch looks like, and a per-instance memory
        // would leave the button pointing at whatever `resolveSelection` fell back
        // to — for a two-device dorm account, possibly the neighbour's shower.
        val relaunched = HotWaterViewModel(repository, preferences, now = now)
        advanceUntilIdle()
        assertEquals(
            "the remembered device must survive a relaunch",
            "d2",
            relaunched.uiState.value.selected?.deviceId,
        )
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A ViewModel whose first load has already answered with [devices].
     *
     * Both fakes are set up *before* construction because the load is kicked off
     * from `init`: a test that stubbed the answer afterwards would be racing the
     * call it meant to script.
     */
    private suspend fun TestScope.loadedViewModel(devices: List<Device>): HotWaterViewModel {
        repository.loadAnswer = { Outcome.Success(devices) }
        val viewModel = HotWaterViewModel(repository, preferences, now = now)
        advanceUntilIdle()
        return viewModel
    }

    private fun device(
        id: String,
        dtype: Int = 6,
        name: String? = null,
        status: Int = Device.STATUS_IDLE,
    ): Device = Device(deviceId = id, deviceName = name, dtype = dtype, status = status)

    /**
     * A repository whose calls can be frozen mid-flight, which is the only way to
     * observe a pending state or to race a second tap against a first one.
     *
     * [explodeOnOpen] exists so a test can assert the *absence* of a request
     * rather than merely counting requests after the fact: if a second tap is ever
     * let through, the failure is reported as an error instead of a wrong number.
     */
    private class FakeHotWaterRepository : HuiShengHuoRepository {

        var loadAnswer: () -> Outcome<List<Device>> = { Outcome.Success(emptyList()) }
        var openAnswer: (Device) -> Outcome<Unit> = { Outcome.Success(Unit) }
        var closeAnswer: (Device) -> Outcome<Unit> = { Outcome.Success(Unit) }

        var loadGate: CompletableDeferred<Unit>? = null
        var openGate: CompletableDeferred<Unit>? = null
        var closeGate: CompletableDeferred<Unit>? = null

        /** Fails the test, loudly, if `openHotWater` is called at all. */
        var explodeOnOpen: String? = null

        val openCalls = mutableListOf<OpenCall>()
        val closeCalls = mutableListOf<CloseCall>()

        /** Counted on entry: a dropped call must be invisible, not merely slow. */
        var loadCalls = 0
            private set

        override suspend fun loadDevices(): Outcome<List<Device>> {
            loadCalls++
            loadGate?.await()
            return loadAnswer()
        }

        override suspend fun openHotWater(device: Device, args: String, ptype: Int): Outcome<Unit> {
            explodeOnOpen?.let { throw AssertionError(it) }
            openCalls += OpenCall(device = device, args = args, ptype = ptype)
            openGate?.await()
            return openAnswer(device)
        }

        override suspend fun closeHotWater(device: Device): Outcome<Unit> {
            closeCalls += CloseCall(device)
            closeGate?.await()
            return closeAnswer(device)
        }

        override suspend fun refreshStatus(device: Device): Outcome<Device> = Outcome.Success(device)

        data class OpenCall(val device: Device, val args: String, val ptype: Int)

        data class CloseCall(val device: Device)
    }

    private companion object {
        /** `gene.status` for a valve that is flowing; 0 means idle, null means unknown. */
        const val RUNNING_STATUS = 1

        /**
         * `gene.status = 99` (Device.kt), which is **not** a session statement.
         *
         * This constant used to be called `UNSETTLED_STATUS`, and the name was the
         * bug: it invited a test to assert that 99 raises the settling flag, which
         * is precisely what the real-device logs disproved. It is spelled as a
         * literal rather than read off [Device.STATUS_UNREPORTED] so that a change
         * to the constant shows up here as a failing test instead of silently
         * moving the fixture with it.
         */
        const val UNREPORTED_STATUS = 99

        /** Mirrors the ViewModel's private gap; see `the gap is measured from the last action`. */
        const val ACTION_GAP = 3_000L
    }
}
