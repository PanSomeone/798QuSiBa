package com.killhuishenghuo.data.model

import com.killhuishenghuo.core.net.ApiConstants
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Hardware class, from `bm.dtype`.
 *
 * The mapping is the one the captured clients ship (`dtypeLabel`). Only the
 * first three are water devices, and only types 5/6/8 are the "no mode, no
 * channel" kind that a dormitory shower uses — those start immediately with an
 * empty `args`, which is exactly what this app does.
 */
enum class DeviceKind(val dtype: Int, val label: String) {
    /** 水表 / 淋浴器 — the dormitory shower this app exists for. */
    SHOWER(6, "淋浴器"),

    /** 水控 / 直饮机. */
    WATER_CONTROL(5, "直饮机"),

    /** 净水 / 热水主机. */
    WATER_HEATER(9, "热水主机"),

    /** Anything else the account may have bound. */
    OTHER(-1, "其他设备"),
    ;

    /** True for every class that dispenses water and can be opened. */
    val isWaterDevice: Boolean get() = this == SHOWER || this == WATER_CONTROL || this == WATER_HEATER

    /**
     * True when the device takes neither a mode nor a channel, so `args` stays
     * empty and the valve opens on its own.
     *
     * Deliberately asks the raw [dtype] and not the enum entry. Type 8 has no
     * entry of its own — [of] maps it to [OTHER], whose `dtype` is `-1` — so
     * deriving this from the entry made `DeviceKind.of(8).isPassageOnly` false
     * and quietly contradicted the class comment above. The captured clients key
     * the same decision off the raw value, which is the only place the fact lives.
     */
    val isPassageOnly: Boolean get() = dtype == 5 || dtype == 6 || dtype == 8

    companion object {
        fun of(dtype: Int): DeviceKind = entries.firstOrNull { it.dtype == dtype } ?: OTHER
    }
}

/**
 * One bound device, normalised out of `ui/app/master`'s `favos[]`.
 *
 * [deviceId] is the `did` every device call takes; it is never blank for a
 * device that came back from the server, and a blank id is treated as "do not
 * offer this row" by the UI rather than being sent as an empty parameter.
 */
@Serializable
data class Device(
    @SerialName("id") val deviceId: String = "",
    @SerialName("name") val deviceName: String? = null,
    /** Live flag: the server says water is flowing right now. */
    @SerialName("status") val status: Int? = null,
    @SerialName("dtype") val dtype: Int = 0,
    /** Dispensing ceiling in seconds, when the device reports one. */
    @SerialName("maxSeconds") val maxSeconds: Int? = null,
) {
    val kind: DeviceKind get() = DeviceKind.of(dtype)

    /** Best-effort display title; never blank. */
    val displayName: String
        get() = deviceName?.takeIf { it.isNotBlank() }
            ?: kind.label.takeIf { it.isNotBlank() }
            ?: "未命名设备"

    /**
     * True when the server says water is running.
     *
     * Two codes mean "not running", not one. `0` is idle; **`99` is the other one,
     * and treating it as running was a real bug** (observed on a real device:
     * `gene.status=99` from `ui/app/master`).
     *
     * `99` is the device's resting value: what `ui/app/master` reports before
     * anything has happened. Real logs show it in both directions, which is what
     * makes it useless as a state:
     *
     * ```
     * Devices  loaded=1 selected=865137068739782 name=荟园13栋134 status=99
     * Button   toggle device=865137068739782 isRunning=true  status=99   ← about to close
     * ...
     * Button   toggle device=865137068739782 isRunning=false status=99   ← about to start, and it worked
     * ```
     *
     * So it is **not** a claim that a session is open — an earlier version read it
     * that way and had the screen announce "上次没结算" to a user who had just
     * logged in and never started anything. It is not a claim that the device is
     * idle either. It is the server declining to answer, and the app should stop
     * putting words in its mouth: see [hasStatus].
     *
     * `null` also means the server never told us, and the UI renders both the same
     * way rather than guessing.
     */
    val isRunning: Boolean get() = status != null && status != STATUS_IDLE && status != STATUS_UNREPORTED

    /** True when the server actually reported a state. */
    val hasStatus: Boolean get() = status != null && status != STATUS_UNREPORTED

    /**
     * Second line of the row: what kind of hardware this is, so a dormitory
     * with both a shower and a water dispenser is unambiguous.
     */
    val subtitle: String get() = kind.label

    companion object {
        /** `gene.status` for a device that is doing nothing. */
        const val STATUS_IDLE = 0

        /**
         * `gene.status` for a device the server has said nothing about.
         *
         * Observed as `99` on a real device at login and still `99` after a
         * successful `dev/start`, so it does not track the valve. It used to be
         * called `STATUS_UNSETTLED`, on the theory that it was the
         * `-2 设备运行中,请在设备上点击结算` refusal seen from the other endpoint.
         * **The logs refute that**: `-2` arrives while the device reports `0`, and
         * that refusal says 运行中 — a session that is *open and running*, not a
         * valve that is shut with an unsettled one.
         *
         * Whether an unsettled session exists is therefore something only the
         * server can say, and it says it by refusing a request. The app records
         * that when it happens instead of inventing it from this number.
         */
        const val STATUS_UNREPORTED = 99

        /** Builds the UI model from the wire shape. */
        fun from(favo: Favo): Device = Device(
            deviceId = favo.id,
            deviceName = favo.name,
            status = favo.gene?.status,
            dtype = favo.bm?.dtype ?: 0,
        )
    }
}

/** Convenience: the device list out of `ui/app/master`. */
fun MasterData.toDevices(): List<Device> = favos
    .filter { it.id.isNotBlank() }
    .map(Device::from)

/**
 * Request payload of a device action.
 *
 * Built as an explicit form model rather than string-templated at the call
 * site, because the gateway parses `args` as JSON and a stray quote would come
 * back as an opaque "parameter error".
 */
@Serializable
data class DeviceStartArgs(
    @SerialName("mode") val mode: Int? = null,
    @SerialName("pos") val pos: Int? = null,
    @SerialName("ext") val ext: List<ExtArg> = emptyList(),
) {
    /**
     * The wire form. Empty string means "no arguments", which is what a
     * dormitory shower expects.
     */
    fun toWire(): String = when {
        mode == null && pos == null && ext.isEmpty() -> ApiConstants.ARGS_EMPTY
        else -> DeviceArgsWire.encode(this)
    }
}

@Serializable
data class ExtArg(
    @SerialName("pos") val pos: Int = 0,
    @SerialName("out") val out: Int = 0,
)
