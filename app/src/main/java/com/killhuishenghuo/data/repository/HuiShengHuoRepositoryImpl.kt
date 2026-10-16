package com.killhuishenghuo.data.repository

import com.killhuishenghuo.core.net.GatewayClient
import com.killhuishenghuo.data.model.AppError
import com.killhuishenghuo.data.model.Device
import com.killhuishenghuo.data.model.DeviceQr
import com.killhuishenghuo.data.model.Outcome
import com.killhuishenghuo.data.model.QrCandidate
import com.killhuishenghuo.data.model.toDevices
import com.killhuishenghuo.data.remote.ApiResultMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gateway-backed implementation.
 *
 * Every call is wrapped so a thrown `IOException` becomes an [Outcome.Failure]
 * instead of propagating into the ViewModel.
 *
 * `ApiResultMapper` is stateless and shared; the `json` parameter the old
 * version took is gone with it, because decoding is Retrofit's converter's job
 * and a second `Json` instance in the repository was a way for the two to
 * disagree.
 */
class HuiShengHuoRepositoryImpl(
    private val gateway: GatewayClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HuiShengHuoRepository {

    override suspend fun loadDevices(): Outcome<List<Device>> = withContext(ioDispatcher) {
        runCatching { ApiResultMapper.map(gateway.master()) }
            .getOrElse(ApiResultMapper::mapThrown)
            .let { outcome ->
                when (outcome) {
                    is Outcome.Success -> Outcome.Success(outcome.value.toDevices())
                    is Outcome.Failure -> outcome
                }
            }
    }

    override suspend fun openHotWater(
        device: Device,
        args: String,
        ptype: Int,
    ): Outcome<Unit> = withContext(ioDispatcher) {
        // No idempotency key: the gateway takes `cnt` (a repeat count) and no
        // request id, so a retried open is a second, real open. The ViewModel
        // is what prevents that, by dropping a second tap while one is in
        // flight rather than by trying to deduplicate server-side.
        // A start that is refused because the valve is *already open* is the
        // state this call wanted, so it goes through the tolerant mapper. The
        // close below deliberately does not: for a close, the same refusal means
        // the opposite of what was asked for.
        runCatching { ApiResultMapper.mapStartAction(gateway.start(device.deviceId, ptype, args)) }
            .getOrElse(ApiResultMapper::mapThrown)
    }

    override suspend fun closeHotWater(device: Device): Outcome<Unit> = withContext(ioDispatcher) {
        runCatching { ApiResultMapper.mapCloseAction(gateway.stop(device.deviceId)) }
            .getOrElse(ApiResultMapper::mapThrown)
    }

    override suspend fun refreshStatus(device: Device): Outcome<Device> = withContext(ioDispatcher) {
        runCatching { ApiResultMapper.map(gateway.status(device.deviceId)) }
            .getOrElse(ApiResultMapper::mapThrown)
            .let { outcome ->
                when (outcome) {
                    is Outcome.Success -> {
                        val node = outcome.value.device
                        if (node == null) {
                            Outcome.Failure(
                                com.killhuishenghuo.data.model.AppError.Unexpected("状态响应缺少 device 字段"),
                            )
                        } else {
                            Outcome.Success(
                                device.copy(
                                    status = node.gene?.status ?: node.status,
                                    maxSeconds = node.parts.firstNotNullOfOrNull { it.maxT },
                                ),
                            )
                        }
                    }

                    is Outcome.Failure -> outcome
                }
            }
    }

    /**
     * Scanned sticker -> a device this account can actually use.
     *
     * The order of the two attempts follows what the code looked like, and each
     * one is the other's fallback: a sticker carrying a bare device id is the
     * common case and saves a round trip, but a code that merely *looks* numeric
     * can still be a `qr/use` id, and a `qr/use` id that the server does not know
     * can still be a device id. Only the second failure of the pair is reported,
     * because it is the one that represents both interpretations.
     */
    override suspend fun bindScannedDevice(code: String): Outcome<Device> = withContext(ioDispatcher) {
        when (val candidate = DeviceQr.parse(code)) {
            QrCandidate.Unreadable -> Outcome.Failure(
                AppError.Unexpected("这个二维码里没有设备编号"),
            )

            is QrCandidate.DeviceId -> {
                val asDevice = favourite(candidate.id).mapToDevice(candidate.id)
                if (asDevice is Outcome.Success) return@withContext asDevice

                val asCode = resolveThenFavourite(candidate.id)
                if (asCode is Outcome.Success) asCode else asDevice
            }

            is QrCandidate.OpaqueId -> {
                val asCode = resolveThenFavourite(candidate.id)
                if (asCode is Outcome.Success) return@withContext asCode

                val asDevice = favourite(candidate.id).mapToDevice(candidate.id)
                if (asDevice is Outcome.Success) asDevice else asCode
            }
        }
    }

    /** `dev/favo` with `remove=0`: what puts the device into `favos[]`. */
    private suspend fun favourite(deviceId: String): Outcome<Unit> =
        runCatching { ApiResultMapper.mapAction(gateway.favourite(deviceId, remove = false)) }
            .getOrElse(ApiResultMapper::mapThrown)

    /** `qr/use` first, then `dev/favo` on whatever device id it named. */
    private suspend fun resolveThenFavourite(id: String): Outcome<Device> =
        when (val resolved = resolveViaQrUse(id)) {
            is Outcome.Success -> favourite(resolved.value).mapToDevice(resolved.value)
            is Outcome.Failure -> resolved
        }

    private suspend fun resolveViaQrUse(id: String): Outcome<String> =
        runCatching { ApiResultMapper.map(gateway.qrUse(id)) }
            .getOrElse(ApiResultMapper::mapThrown)
            .let { outcome ->
                when (outcome) {
                    is Outcome.Success -> {
                        val deviceId = outcome.value.device?.id.orEmpty()
                        if (deviceId.isBlank()) {
                            Outcome.Failure(AppError.Unexpected("服务器没有认出这个二维码"))
                        } else {
                            Outcome.Success(deviceId)
                        }
                    }

                    is Outcome.Failure -> outcome
                }
            }

    /**
     * The favourite call returned `code: 0` — now check the thing the user
     * actually asked for, which is that the device is in the list. `loadDevices`
     * is the same call the screen makes, so a success here means the screen will
     * show it on its next read rather than merely ought to.
     */
    private suspend fun Outcome<Unit>.mapToDevice(deviceId: String): Outcome<Device> = when (this) {
        is Outcome.Failure -> this
        is Outcome.Success -> when (val devices = loadDevices()) {
            is Outcome.Failure -> devices
            is Outcome.Success -> devices.value.firstOrNull { it.deviceId == deviceId }
                ?.let { Outcome.Success(it) }
                ?: Outcome.Failure(
                    AppError.Unexpected("服务器接受了这台设备，但列表里还没有它，请下拉刷新或稍后再看"),
                )
        }
    }
}
