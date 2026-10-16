package com.killhuishenghuo.data.model

import com.killhuishenghuo.core.net.NetworkModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the `args` query parameter byte for byte.
 *
 * The gateway parses `args` as JSON, so most of these cases would also pass
 * against a parsed comparison. The raw-string assertions are deliberate: key
 * order and the difference between `"mode":null` and an absent `mode` are
 * exactly what the hand-written encoder exists for, and a round trip through a
 * `Map` would throw both away.
 */
class DeviceArgsWireTest {

    private companion object {
        val json: Json = NetworkModule.json
    }

    @Test
    fun `a device that needs no arguments sends an empty string`() {
        // The dormitory shower path: `args=""` is what makes the valve open on
        // its own, and it is a different request from `args="{}"`.
        assertEquals("", DeviceStartArgs().toWire())
    }

    @Test
    fun `a mode without extensions declares an empty ext list`() {
        assertEquals("""{"mode":3,"ext":[]}""", DeviceStartArgs(mode = 3).toWire())
    }

    @Test
    fun `a channel without a mode sends an explicit null mode`() {
        // `explicitNulls = false` in the shared Json would drop this key; the
        // gateway distinguishes "no mode" from "no mode key at all".
        val wire = DeviceStartArgs(pos = 2, mode = null).toWire()

        assertEquals("""{"pos":2,"mode":null}""", wire)
        assertTrue("the literal null mode must survive", wire.contains("\"mode\":null"))
    }

    @Test
    fun `a channel with a mode sends both keys`() {
        assertEquals("""{"pos":2,"mode":5}""", DeviceStartArgs(pos = 2, mode = 5).toWire())
    }

    @Test
    fun `a mode device carries its goods list`() {
        val wire = DeviceStartArgs(mode = 3, ext = listOf(ExtArg(pos = 1, out = 2))).toWire()

        assertEquals("""{"mode":3,"ext":[{"pos":1,"out":2}]}""", wire)
    }

    @Test
    fun `extension entries keep their order and are comma joined`() {
        val wire = DeviceStartArgs(
            mode = 7,
            ext = listOf(ExtArg(pos = 1, out = 2), ExtArg(pos = 3, out = 4)),
        ).toWire()

        assertEquals("""{"mode":7,"ext":[{"pos":1,"out":2},{"pos":3,"out":4}]}""", wire)
    }

    /** A channel device names its outlet first, so `pos` wins over `mode`+`ext`. */
    @Test
    fun `pos takes precedence over mode and ext`() {
        val wire = DeviceStartArgs(pos = 2, mode = 5, ext = listOf(ExtArg(pos = 1, out = 2))).toWire()

        assertEquals("""{"pos":2,"mode":5}""", wire)
        assertTrue("a channel device must not carry an ext list", !wire.contains("ext"))
    }

    @Test
    fun `mode precedes ext and pos precedes out in the emitted bytes`() {
        val extended = DeviceStartArgs(mode = 3, ext = listOf(ExtArg(pos = 1, out = 2))).toWire()
        val channel = DeviceStartArgs(pos = 2, mode = 5).toWire()

        assertTrue(extended, extended.indexOf("\"mode\"") < extended.indexOf("\"ext\""))
        assertTrue(extended, extended.indexOf("\"pos\"") < extended.indexOf("\"out\""))
        assertTrue(channel, channel.indexOf("\"pos\"") < channel.indexOf("\"mode\""))
    }

    /** The string is not just well formed for a reader — it must parse as JSON. */
    @Test
    fun `every emitted shape parses as json with the expected values`() {
        val extended = json.parseToJsonElement(
            DeviceStartArgs(mode = 3, ext = listOf(ExtArg(pos = 1, out = 2))).toWire(),
        ).jsonObject

        assertEquals(3, extended.getValue("mode").jsonPrimitive.int)
        val firstExt = extended.getValue("ext").jsonArray.first().jsonObject
        assertEquals(1, firstExt.getValue("pos").jsonPrimitive.int)
        assertEquals(2, firstExt.getValue("out").jsonPrimitive.int)

        val channel = json.parseToJsonElement(DeviceStartArgs(pos = 2, mode = null).toWire()).jsonObject

        assertEquals(2, channel.getValue("pos").jsonPrimitive.int)
        assertTrue("mode must be present as a JSON null", channel.getValue("mode").toString() == "null")
    }
}
