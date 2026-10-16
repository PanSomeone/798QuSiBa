package com.killhuishenghuo.data.model

import com.killhuishenghuo.core.net.ApiConstants
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The envelope every gateway response arrives in.
 *
 * Verified live: an unauthenticated `GET ui/app/master` answered
 * `{"code":0,"data":{"ads":[]},"time":1792081702558}`. The error key is `msg`,
 * not `message` — but several endpoints in the captured clients read `msg` for
 * failures and the envelope carries no message on success, so both spellings
 * are accepted and [message] prefers `msg`.
 */
@Serializable
data class ApiEnvelope<out T>(
    @SerialName("code") val code: Int = UNKNOWN_CODE,
    @SerialName("msg") val msg: String? = null,
    @SerialName("message") val messageField: String? = null,
    @SerialName("data") val data: T? = null,
    @SerialName("time") val time: Long? = null,
) {
    val isSuccess: Boolean get() = code == ApiConstants.CODE_SUCCESS

    val isSessionExpired: Boolean get() = code == ApiConstants.CODE_SESSION_EXPIRED

    val isRateLimited: Boolean get() = code == ApiConstants.CODE_RATE_LIMITED

    /** Best available human-readable failure text. */
    val message: String? get() = msg ?: messageField

    companion object {
        const val UNKNOWN_CODE = Int.MIN_VALUE
    }
}

/**
 * Login payload. `data.al` holds the token pair; `data.acc` may additionally be
 * present but carries nothing this client needs.
 */
@Serializable
data class LoginData(
    @SerialName("al") val al: LoginToken? = null,
)

@Serializable
data class LoginToken(
    @SerialName("token") val token: String = "",
    @SerialName("uid") val uid: String = "",
)

/**
 * The account and its bound devices, as returned by `ui/app/master`.
 *
 * `favos` is the device list. Verified live that an unauthenticated call
 * returns `data` with `ads` only and no `favos`, which is why every field here
 * has a default.
 */
@Serializable
data class MasterData(
    @SerialName("account") val account: Account? = null,
    @SerialName("favos") val favos: List<Favo> = emptyList(),
)

@Serializable
data class Account(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String? = null,
    @SerialName("pn") val phone: String? = null,
    @SerialName("img") val img: String? = null,
)

/**
 * One bound device.
 *
 * `id` is the `did` every device call takes. `bm.dtype` is the hardware class
 * (see [com.killhuishenghuo.data.model.DeviceKind]) and `gene.status` is the
 * running flag.
 */
@Serializable
data class Favo(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String? = null,
    @SerialName("bm") val bm: Bm? = null,
    @SerialName("gene") val gene: Gene? = null,
)

@Serializable
data class Bm(
    @SerialName("dtype") val dtype: Int = 0,
    /** Some devices carry a readable type label already. */
    @SerialName("dtypeLabel") val dtypeLabel: String? = null,
)

@Serializable
data class Gene(
    @SerialName("status") val status: Int = 0,
    /** Remaining seconds, or an end timestamp — units differ per device. */
    @SerialName("time") val time: Long? = null,
)

/**
 * Live state from `ui/app/dev/status`.
 *
 * `device.status` is the handshake flag and `device.gene.status` is the one
 * that tracks water flow: the captured client treats `gene.status` as the
 * running indicator, and so does this one.
 */
@Serializable
data class DeviceStatusData(
    @SerialName("device") val device: DeviceStateNode? = null,
)

/**
 * What `qr/use` answers: the device a scanned code belongs to.
 *
 * Only `dev.id` is used — that is the `did` every device call takes. `qr.type`
 * is read for the log and **deliberately not gated on**: a working client of the
 * same product accepts two type values (device, door lock) and refuses the rest,
 * but those two constants were read off its source rather than observed on the
 * gateway, and refusing a legitimate sticker because its type number is not in a
 * list this client guessed would be a worse failure than trying a call the
 * server can refuse on its own.
 */
@Serializable
data class QrUseData(
    @SerialName("dev") val device: QrDeviceRef? = null,
    @SerialName("qr") val qr: QrInfo? = null,
)

@Serializable
data class QrDeviceRef(
    @SerialName("id") val id: String = "",
)

@Serializable
data class QrInfo(
    @SerialName("type") val type: Int? = null,
)

@Serializable
data class DeviceStateNode(
    @SerialName("status") val status: Int = 0,
    @SerialName("gene") val gene: Gene? = null,
    @SerialName("bm") val bm: Bm? = null,
    /** Selectable modes for devices that have them. */
    @SerialName("parts") val parts: List<Part> = emptyList(),
)

@Serializable
data class Part(
    @SerialName("mode") val mode: Int = -1,
    @SerialName("name") val name: String? = null,
    @SerialName("rate") val rate: Double? = null,
    /** Maximum dispensing seconds. */
    @SerialName("maxT") val maxT: Int? = null,
)
