package com.killhuishenghuo.data.model

import kotlinx.serialization.json.Json

/**
 * Serialises [DeviceStartArgs] into the exact string the gateway expects in its
 * `args` query parameter.
 *
 * The captured client hand-writes this string with format templates:
 *
 * ```
 * {"mode":<m>,"ext":[{"pos":<p>,"out":<o>},…]}
 * {"pos":<c>,"mode":<m>}      // channel device with a mode
 * {"pos":<c>,"mode":null}     // channel device without one
 * ```
 *
 * Two details are load-bearing and are the reason this is not just
 * `Json.encodeToString`:
 *
 *  1. **Key order is fixed** — `mode` before `ext`, `pos` before `out`. JSON
 *     objects are unordered by spec, but the point is to reproduce a byte
 *     sequence that is known to be accepted rather than to argue about it.
 *  2. **Absent keys are omitted, not null.** `{"mode":null}` is a meaningful
 *     value distinct from "no mode key at all", so [Json]'s default handling of
 *     nulls cannot be used as-is.
 */
internal object DeviceArgsWire {

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(args: DeviceStartArgs): String {
        val ext = args.ext.joinToString(separator = ",") { """{"pos":${it.pos},"out":${it.out}}""" }

        return when {
            // A channel device names its outlet first; the same shape carries
            // either a real mode or an explicit null one.
            args.pos != null && args.mode != null -> """{"pos":${args.pos},"mode":${args.mode}}"""
            args.pos != null -> """{"pos":${args.pos},"mode":null}"""

            // {"mode":m,"ext":[…]} — a mode device with its goods list.
            args.mode != null -> """{"mode":${args.mode},"ext":[$ext]}"""

            else -> "{}"
        }
    }

    /**
     * Kept so the object is not accidentally reduced to a string builder: any
     * future field addition should be reflected in [encode] deliberately.
     */
    @Suppress("unused")
    fun debugJson(args: DeviceStartArgs): String = json.encodeToString(DeviceStartArgs.serializer(), args)
}
