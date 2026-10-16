package com.killhuishenghuo.data.repository

import com.killhuishenghuo.data.model.Device
import com.killhuishenghuo.data.model.Outcome

/**
 * Everything the hot-water screen needs from the network.
 *
 * The interface exists so the screen's ViewModel can be tested against a fake
 * that can assert the *arguments* of an open request — which for this app
 * matters more than the response, because a wrong `args` string silently does
 * nothing rather than failing loudly.
 *
 * Note which signing scheme is absent here: the device path is authenticated by
 * the `Authorization` token alone. `X-Ashe` (`core/sign/ApiSigner.kt`) belongs
 * to the official app's private RPC layer and is never sent.
 */
interface HuiShengHuoRepository {

    /** Bound devices from `ui/app/master`. */
    suspend fun loadDevices(): Outcome<List<Device>>

    /**
     * Opens the valve on [device].
     *
     * [args] comes from `DeviceStartArgs.toWire()`; it is a parameter rather
     * than being derived here so the ViewModel owns the "which mode / which
     * channel" decision.
     *
     * @param ptype funding source; defaults to the dormitory wallet.
     */
    suspend fun openHotWater(
        device: Device,
        args: String = "",
        ptype: Int = DEFAULT_PTYPE,
    ): Outcome<Unit>

    /** Closes the valve on [device]. */
    suspend fun closeHotWater(device: Device): Outcome<Unit>

    /** Re-reads one device's live state. */
    suspend fun refreshStatus(device: Device): Outcome<Device>

    companion object {
        /** `ptype` 91 = dormitory wallet balance. See `ApiConstants.PTYPE_WALLET`. */
        const val DEFAULT_PTYPE = 91
    }
}
