package com.killhuishenghuo.data.model

import com.killhuishenghuo.core.net.NetworkModule
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the device model that the whole hot-water path reads: which hardware
 * class a `dtype` is, whether the valve can be opened with empty `args`, and
 * how a missing server field degrades.
 */
class DeviceTest {

    private companion object {
        val json: Json = NetworkModule.json
    }

    @Test
    fun `dtype maps to the hardware class the captured clients use`() {
        assertEquals(DeviceKind.WATER_CONTROL, DeviceKind.of(5))
        assertEquals(DeviceKind.SHOWER, DeviceKind.of(6))
        assertEquals(DeviceKind.WATER_HEATER, DeviceKind.of(9))
    }

    @Test
    fun `an unknown dtype is a device we cannot start blindly`() {
        for (dtype in listOf(0, -1, 7, 999)) {
            assertEquals("dtype $dtype", DeviceKind.OTHER, DeviceKind.of(dtype))
        }
    }

    @Test
    fun `only the passage devices start with empty args`() {
        assertTrue(DeviceKind.WATER_CONTROL.isPassageOnly)
        assertTrue(DeviceKind.SHOWER.isPassageOnly)
        assertFalse(DeviceKind.WATER_HEATER.isPassageOnly)
        assertFalse(DeviceKind.OTHER.isPassageOnly)
    }

    /**
     * The dtype-8 fact lives on the raw server value, and the only value in the
     * app that carries raw dtype 8 is `DeviceKind.of(8)`, which has no entry of
     * its own and answers `OTHER`. So the pass-only answer for 8 is not
     * reachable through the class at all, and this test says exactly that
     * instead of pretending otherwise.
     *
     * An `EIGHT(8, …)` entry flips both assertions — delete this test when that
     * entry lands, because it will then be describing a fallback that no longer
     * happens.
     */
    @Test
    fun `the pass only rule for dtype 8 is unreachable through the class lookup`() {
        val forEight = DeviceKind.of(8)

        assertEquals("8 still has no entry of its own", DeviceKind.OTHER, forEight)
        assertEquals("-1 is the fallback's own dtype, not the server's 8", -1, forEight.dtype)
        assertFalse("OTHER is not a passage class", forEight.isPassageOnly)
    }

    @Test
    fun `every class except OTHER dispenses water`() {
        assertTrue(DeviceKind.SHOWER.isWaterDevice)
        assertTrue(DeviceKind.WATER_CONTROL.isWaterDevice)
        assertTrue(DeviceKind.WATER_HEATER.isWaterDevice)
        assertFalse(DeviceKind.OTHER.isWaterDevice)
    }

    @Test
    fun `displayName falls back to the class label and is never blank`() {
        assertEquals("宿舍淋浴", Device(deviceId = "d1", deviceName = "宿舍淋浴", dtype = 6).displayName)
        assertEquals("淋浴器", Device(deviceId = "d1", deviceName = null, dtype = 6).displayName)
        assertEquals("淋浴器", Device(deviceId = "d1", deviceName = "   ", dtype = 6).displayName)
        assertEquals("其他设备", Device(deviceId = "d1", deviceName = "", dtype = 0).displayName)
    }

    @Test
    fun `isRunning treats only a non-zero status as flowing`() {
        assertFalse(Device(deviceId = "d1", status = 0).isRunning)
        assertTrue(Device(deviceId = "d1", status = 1).isRunning)
        assertFalse(Device(deviceId = "d1", status = null).isRunning)
    }

    /**
     * `99` is a real value seen on a real device, and it means **not running**.
     *
     * This test exists because the opposite rule shipped: `status != 0` is a
     * natural reading of "non-zero means on", and it made the button offer
     * 关闭热水 for a shut valve, so every press was refused.
     *
     * The second half is the correction that came later, and it is the more
     * important one to keep: 99 is **not a statement about a session** either. It
     * showed up in the logs both while the valve was open and while it was shut,
     * and an earlier version read it as "an unsettled session exists", which put
     * 上次没结算 on the screen of a user who had just logged in and started
     * nothing. So it is classified as "the server has not said", and
     * [Device.hasStatus] reports that honestly.
     */
    @Test
    fun `status 99 is the other not-running code, not a running one`() {
        val device = Device(deviceId = "d1", status = Device.STATUS_UNREPORTED)

        assertFalse("99 must not read as running", device.isRunning)
        assertFalse("and it is not an answer either", device.hasStatus)
    }

    @Test
    fun `every status the server has been seen to send is classified`() {
        // The observed values, pinned by name: a new code that reads as running by
        // default is exactly the bug this replaces.
        val running = listOf(1, 2, 3)
        val notRunning = listOf(Device.STATUS_IDLE, Device.STATUS_UNREPORTED)

        for (status in running) {
            assertTrue("status $status", Device(deviceId = "d1", status = status).isRunning)
        }
        for (status in notRunning) {
            assertFalse("status $status", Device(deviceId = "d1", status = status).isRunning)
        }
    }

    /** `null` and `0` must stay distinguishable — the UI words them differently. */
    @Test
    fun `hasStatus separates unknown from idle`() {
        assertTrue(Device(deviceId = "d1", status = 0).hasStatus)
        assertFalse(Device(deviceId = "d1", status = null).hasStatus)
        assertFalse(Device(deviceId = "d1").isRunning)
    }

    /**
     * The `99` observation, as it came off the wire.
     *
     * The name is the real one from the exported log, kept verbatim: it is the
     * only evidence for how this device type reports, and shortening it here
     * would lose the thing being documented.
     */
    @Test
    fun `the observed master row decodes as a shut device`() {
        val favo = json.decodeFromString(
            Favo.serializer(),
            """{"id":"865137068739782","name":"荟园13栋1134寝室","bm":{"dtype":6},"gene":{"status":99}}""",
        )

        val device = Device.from(favo)

        assertEquals("荟园13栋1134寝室", device.displayName)
        assertEquals(DeviceKind.SHOWER, device.kind)
        assertFalse("the valve was shut while the server answered 99", device.isRunning)
    }

    @Test
    fun `toDevices drops blank ids and preserves the server order`() {
        val master = MasterData(
            favos = listOf(
                Favo(id = "d2", name = "second"),
                Favo(id = "", name = "no id"),
                Favo(id = "d1", name = "first"),
            ),
        )

        val devices = master.toDevices()

        assertEquals(2, devices.size)
        assertEquals(listOf("d2", "d1"), devices.map { it.deviceId })
    }

    @Test
    fun `toDevices on an empty master is empty rather than null`() {
        assertEquals(emptyList<Device>(), MasterData().toDevices())
    }

    @Test
    fun `from reads bm dtype and gene status`() {
        val device = Device.from(Favo(id = "d1", name = "淋浴", bm = Bm(dtype = 6), gene = Gene(status = 1)))

        assertEquals("d1", device.deviceId)
        assertEquals(6, device.dtype)
        assertEquals(DeviceKind.SHOWER, device.kind)
        assertTrue(device.isRunning)
    }

    @Test
    fun `from tolerates a missing bm and gene`() {
        val device = Device.from(Favo(id = "d1", bm = null, gene = null))

        assertEquals(0, device.dtype)
        assertNull(device.status)
        assertFalse(device.isRunning)
        assertFalse(device.hasStatus)
        assertEquals(DeviceKind.OTHER, device.kind)
    }

    /** The wire shape is the contract; a rename in the model must show up here. */
    @Test
    fun `Favo decodes the gateway field names`() {
        val favo = json.decodeFromString(
            Favo.serializer(),
            """{"id":"d1","name":"淋浴","bm":{"dtype":6},"gene":{"status":1}}""",
        )

        assertEquals("d1", favo.id)
        assertNotNull(favo.bm)
        assertEquals(6, favo.bm?.dtype)
        assertEquals(1, favo.gene?.status)
    }
}
