package com.killhuishenghuo.data.repository

import com.killhuishenghuo.core.net.GatewayClient
import com.killhuishenghuo.data.model.Device
import com.killhuishenghuo.data.model.Outcome
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
}
