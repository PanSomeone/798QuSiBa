package com.killhuishenghuo.data.repository

import com.killhuishenghuo.core.net.GatewayClient
import com.killhuishenghuo.data.model.Account
import com.killhuishenghuo.data.model.ApiEnvelope
import com.killhuishenghuo.data.model.AppError
import com.killhuishenghuo.data.model.Bm
import com.killhuishenghuo.data.model.Device
import com.killhuishenghuo.data.model.DeviceKind
import com.killhuishenghuo.data.model.DeviceStateNode
import com.killhuishenghuo.data.model.DeviceStatusData
import com.killhuishenghuo.data.model.Favo
import com.killhuishenghuo.data.model.Gene
import com.killhuishenghuo.data.model.LoginData
import com.killhuishenghuo.data.model.MasterData
import com.killhuishenghuo.data.model.Outcome
import com.killhuishenghuo.data.model.Part
import com.killhuishenghuo.data.model.QrDeviceRef
import com.killhuishenghuo.data.model.QrUseData
import com.killhuishenghuo.data.remote.LoginRequest
import com.killhuishenghuo.data.remote.SmsCodeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The repository's mapping, with the network replaced by constructed
 * [Response] objects.
 *
 * The point of using a hand-written fake rather than the MockWebServer is that
 * each failure layer can be forced *alone*: a DNS failure, a dead session and a
 * body that is not JSON produce three different sentences on screen, and a test
 * that can only reach one of them by luck is not a test of the other two.
 */
class HuiShengHuoRepositoryImplTest {

    private val gateway = FakeGateway()

    // `Dispatchers.Unconfined` rather than the default `Dispatchers.IO`: the fake
    // never suspends on real I/O, so a thread hop would only add a way for an
    // assertion to observe a state that has not been written yet.
    private fun repository(): HuiShengHuoRepository =
        HuiShengHuoRepositoryImpl(gateway = gateway, ioDispatcher = Dispatchers.Unconfined)

    // ------------------------------------------------------------ loadDevices

    @Test
    fun `loadDevices maps favos and drops rows without an id`() = runBlocking {
        gateway.masterAnswer = {
            envelope(
                MasterData(
                    account = Account(id = "a1", name = "张三"),
                    favos = listOf(
                        Favo(id = "d1", name = "淋浴器A", bm = Bm(dtype = 6), gene = Gene(status = 0)),
                        // A blank `did` cannot be sent as a device id, so the row
                        // is dropped rather than offered as an unopenable button.
                        Favo(id = "", name = "幽灵设备", bm = Bm(dtype = 5), gene = Gene(status = 0)),
                        Favo(id = "d2", name = "", bm = Bm(dtype = 5), gene = Gene(status = 1)),
                    ),
                ),
            )
        }

        val devices = repository().loadDevices().getOrNull()

        assertEquals(listOf("d1", "d2"), devices?.map { it.deviceId })
        assertEquals(listOf(DeviceKind.SHOWER, DeviceKind.WATER_CONTROL), devices?.map { it.kind })
        assertEquals(listOf(0, 1), devices?.map { it.status })
        assertFalse(devices!![0].isRunning)
        assertTrue(devices[1].isRunning)
        // A device the gateway never named still needs a label on screen.
        assertEquals("直饮机", devices[1].displayName)
    }

    @Test
    fun `loadDevices reports Offline when the host cannot be resolved`() = runBlocking {
        gateway.masterAnswer = { throw UnknownHostException("i.ilife798.com: no address") }

        val outcome = repository().loadDevices()

        assertEquals(AppError.Offline, outcome.errorOrNull())
        assertEquals("网络连不上，检查一下连接再试", outcome.errorOrNull()?.toChinese())
    }

    @Test
    fun `loadDevices reports Timeout when the call runs out of time`() = runBlocking {
        gateway.masterAnswer = { throw SocketTimeoutException("read timed out") }

        assertEquals(AppError.Timeout, repository().loadDevices().errorOrNull())
    }

    @Test
    fun `loadDevices reports Unauthorized on a dead session`() = runBlocking {
        gateway.masterAnswer = { envelope<MasterData>(code = -99, msg = "登录已过期") }

        val outcome = repository().loadDevices()

        assertEquals(AppError.Unauthorized, outcome.errorOrNull())
        // The screen keys off this exact sentence to decide it is a login problem.
        assertEquals("登录状态失效了，重新登录一次", outcome.errorOrNull()?.toChinese())
    }

    @Test
    fun `an anonymous master with a token attached is a dead session`() = runBlocking {
        // This is the bug the log caught on 2026-10-20: the token had died, so
        // `ui/app/master` answered `{"code":0,"data":{"ads":[]}}` — success, and
        // anonymous. The app believed it was signed in, showed no devices, and
        // invited the user to scan one; `dev/favo` then answered -99. A `code:0`
        // with no account, on an install that did send a token, is a rejection
        // dressed as an empty account.
        gateway.masterAnswer = { envelope(MasterData(account = null, favos = emptyList())) }

        val outcome =
            HuiShengHuoRepositoryImpl(
                gateway = gateway,
                ioDispatcher = Dispatchers.Unconfined,
                isSignedIn = { true },
            ).loadDevices()

        assertEquals(AppError.Unauthorized, outcome.errorOrNull())
    }

    @Test
    fun `an anonymous master without a token is just an empty account`() = runBlocking {
        // The screen is deliberately reachable signed out — the button then reads
        // 去设置 — and that path must keep working. Same body, different meaning,
        // and the only thing that can tell them apart is whether a token went out.
        gateway.masterAnswer = { envelope(MasterData(account = null, favos = emptyList())) }

        val outcome = repository().loadDevices()

        assertEquals(emptyList<Device>(), outcome.getOrNull())
    }

    @Test
    fun `loadDevices reports a missing payload rather than an empty list`() = runBlocking {
        // `code: 0` with no `data` is a shape the gateway really answers. Treating
        // it as "no devices" would silently show an empty account.
        gateway.masterAnswer = { envelope<MasterData>(code = 0) }

        val outcome = repository().loadDevices()

        assertTrue(outcome.errorOrNull() is AppError.Unexpected)
        assertEquals("响应缺少 data 字段", (outcome.errorOrNull() as AppError.Unexpected).detail)
    }

    @Test
    fun `a body that is not JSON never escapes as an exception`() = runBlocking {
        gateway.masterAnswer = { throw SerializationException("Expected start of the object but had < at offset 0") }

        val outcome = repository().loadDevices()

        assertEquals(AppError.Unexpected("响应格式无法解析"), outcome.errorOrNull())
    }

    // ----------------------------------------------------------- openHotWater

    @Test
    fun `openHotWater succeeds on a code-only body and forwards args and ptype`() = runBlocking {
        // `dev/start` answers `{"code":0}` with no `data` at all, so a mapper that
        // required a payload would report a successfully opened valve as a failure.
        gateway.startAnswer = { envelope<JsonElement>(code = 0) }
        val device = Device(deviceId = "d1", dtype = 6)
        val args = """{"mode":5,"pos":1}"""

        val outcome = repository().openHotWater(device = device, args = args, ptype = 21)

        assertEquals(Outcome.Success(Unit), outcome)
        assertEquals(1, gateway.startCalls.size)
        assertEquals(FakeGateway.StartCall(did = "d1", ptype = 21, args = args), gateway.startCalls.single())
    }

    @Test
    fun `openHotWater keeps the default wallet ptype`() = runBlocking {
        gateway.startAnswer = { envelope<JsonElement>(code = 0) }

        repository().openHotWater(Device(deviceId = "d1", dtype = 6))

        assertEquals(91, gateway.startCalls.single().ptype)
        assertEquals("", gateway.startCalls.single().args)
    }

    @Test
    fun `openHotWater reports Unauthorized and does not retry`() = runBlocking {
        gateway.startAnswer = { envelope<JsonElement>(code = -99) }

        val outcome = repository().openHotWater(Device(deviceId = "d1", dtype = 6))

        assertEquals(AppError.Unauthorized, outcome.errorOrNull())
        // One call, not two: a retry here would be a second real valve opening.
        assertEquals(1, gateway.startCalls.size)
    }

    @Test
    fun `openHotWater reports RateLimited for code -98`() = runBlocking {
        gateway.startAnswer = { envelope<JsonElement>(code = -98) }

        val outcome = repository().openHotWater(Device(deviceId = "d1", dtype = 6))

        assertEquals(AppError.RateLimited, outcome.errorOrNull())
        assertEquals("操作太频繁，被服务器限制了，等一会儿再点", outcome.errorOrNull()?.toChinese())
    }

    // ---------------------------------------------------------- closeHotWater

    @Test
    fun `closeHotWater sends the device id to stop`() = runBlocking {
        gateway.stopAnswer = { envelope<JsonElement>(code = 0) }

        val outcome = repository().closeHotWater(Device(deviceId = "d7", dtype = 6))

        assertEquals(Outcome.Success(Unit), outcome)
        assertEquals(listOf("d7"), gateway.stopCalls)
    }

    // ---------------------------------------------------------- refreshStatus

    @Test
    fun `refreshStatus reads the running flag from gene status`() = runBlocking {
        // `device.status` is the handshake flag; `gene.status` is the one that
        // tracks water flow, and the two disagree on a real device.
        gateway.statusAnswer = {
            envelope(DeviceStatusData(device = DeviceStateNode(status = 0, gene = Gene(status = 1))))
        }

        val device = Device(deviceId = "d1", dtype = 6, status = 0)
        val refreshed = repository().refreshStatus(device).getOrNull()

        assertEquals(listOf("d1"), gateway.statusCalls)
        assertEquals(1, refreshed?.status)
        assertTrue(refreshed!!.isRunning)
        // Everything the caller already knew is preserved; only live state is replaced.
        assertEquals("d1", refreshed.deviceId)
        assertEquals(6, refreshed.dtype)
    }

    @Test
    fun `refreshStatus falls back to device status without a gene block`() = runBlocking {
        gateway.statusAnswer = {
            envelope(DeviceStatusData(device = DeviceStateNode(status = 3, gene = null)))
        }

        val refreshed = repository().refreshStatus(Device(deviceId = "d1")).getOrNull()

        assertEquals(3, refreshed?.status)
    }

    @Test
    fun `refreshStatus takes maxSeconds from the first part that reports maxT`() = runBlocking {
        // The production code reads `data.device.parts[].maxT` — not a field of
        // `bm` — and takes the first non-null one, in list order.
        gateway.statusAnswer = {
            envelope(
                DeviceStatusData(
                    device = DeviceStateNode(
                        status = 0,
                        gene = Gene(status = 1),
                        parts = listOf(Part(mode = 1, maxT = null), Part(mode = 2, maxT = 300), Part(mode = 3, maxT = 600)),
                    ),
                ),
            )
        }

        val refreshed = repository().refreshStatus(Device(deviceId = "d1", maxSeconds = 99)).getOrNull()

        assertEquals(300, refreshed?.maxSeconds)
    }

    @Test
    fun `refreshStatus clears maxSeconds when no part reports one`() = runBlocking {
        gateway.statusAnswer = {
            envelope(DeviceStatusData(device = DeviceStateNode(status = 0, gene = Gene(status = 0))))
        }

        val refreshed = repository().refreshStatus(Device(deviceId = "d1", maxSeconds = 99)).getOrNull()

        assertNull(refreshed?.maxSeconds)
        assertFalse(refreshed!!.isRunning)
    }

    @Test
    fun `refreshStatus fails when the response carries no device`() = runBlocking {
        gateway.statusAnswer = { envelope(DeviceStatusData(device = null)) }

        val outcome = repository().refreshStatus(Device(deviceId = "d1"))

        assertEquals(AppError.Unexpected("状态响应缺少 device 字段"), outcome.errorOrNull())
    }

    @Test
    fun `refreshStatus reports Unauthorized on a dead session`() = runBlocking {
        gateway.statusAnswer = { envelope<DeviceStatusData>(code = -99) }

        assertEquals(AppError.Unauthorized, repository().refreshStatus(Device(deviceId = "d1")).errorOrNull())
    }

    // --------------------------------------------------------- bindScannedDevice

    /** A `/master` answer whose `favos[]` holds exactly [ids]. */
    private fun masterWith(vararg ids: String): () -> Response<ApiEnvelope<MasterData>> = {
        envelope(
            MasterData(
                account = Account(id = "a1", name = "13000000000"),
                favos = ids.map { Favo(id = it, name = "设备", bm = Bm(dtype = 6), gene = Gene(status = 0)) },
            ),
        )
    }

    @Test
    fun `a numeric sticker is favourited as a device id, without asking qr-use`() = runBlocking {
        gateway.masterAnswer = masterWith("865137068739782")

        val device = repository().bindScannedDevice("865137068739782").getOrNull()

        assertEquals("865137068739782", device?.deviceId)
        assertEquals(listOf(FakeGateway.FavouriteCall("865137068739782", false)), gateway.favouriteCalls)
        assertTrue("a code that already is a device id needs no exchange", gateway.qrUseCalls.isEmpty())
    }

    @Test
    fun `a code that identifies itself is exchanged before it is favourited`() = runBlocking {
        gateway.qrUseAnswer = { envelope(QrUseData(device = QrDeviceRef(id = "865137068739782"))) }
        gateway.masterAnswer = masterWith("865137068739782")

        val device = repository().bindScannedDevice("https://i.ilife798.com/q/1/AbCd1234").getOrNull()

        assertEquals("865137068739782", device?.deviceId)
        assertEquals(listOf("AbCd1234"), gateway.qrUseCalls)
        assertEquals(listOf(FakeGateway.FavouriteCall("865137068739782", false)), gateway.favouriteCalls)
    }

    @Test
    fun `a numeric code the server refuses as a device is tried as a qr id`() = runBlocking {
        // 两套解释互为兜底：数字串最常见的样子是设备号，但它也可能是一个要拿去
        // qr/use 换的 id，而这两种情况从二维码本身看不出来。第一次收藏被拒，
        // 换来的设备号再收藏一次就成功 —— 这正是兜底存在的意义。
        var attempt = 0
        gateway.favouriteAnswer = {
            attempt++
            if (attempt == 1) envelope<JsonElement>(code = -2, msg = "无效设备") else envelope<JsonElement>()
        }
        gateway.qrUseAnswer = { envelope(QrUseData(device = QrDeviceRef(id = "999888777666"))) }
        gateway.masterAnswer = masterWith("999888777666")

        val device = repository().bindScannedDevice("123456789012").getOrNull()

        assertEquals("999888777666", device?.deviceId)
        assertEquals(listOf("123456789012"), gateway.qrUseCalls)
        assertEquals(listOf("123456789012", "999888777666"), gateway.favouriteCalls.map { it.did })
    }

    @Test
    fun `success means the device is in the list, not that the server answered code zero`() = runBlocking {
        // A real account was found in exactly this state: `/master` answering,
        // `favos` empty. "The server accepted the favourite" and "the user can
        // open this device" are two different claims.
        gateway.masterAnswer = masterWith("some-other-device")

        val outcome = repository().bindScannedDevice("865137068739782")

        assertNull(outcome.getOrNull())
        assertTrue(outcome.errorOrNull() is AppError.Unexpected)
    }

    @Test
    fun `an unreadable sticker fails without calling the gateway at all`() = runBlocking {
        val outcome = repository().bindScannedDevice("随便一句话")

        assertNull(outcome.getOrNull())
        assertTrue(gateway.favouriteCalls.isEmpty())
        assertTrue(gateway.qrUseCalls.isEmpty())
    }

    @Test
    fun `a dead session during binding is reported as a session problem`() = runBlocking {
        gateway.favouriteAnswer = { envelope<JsonElement>(code = -99) }
        gateway.qrUseAnswer = { envelope<QrUseData>(code = -99) }

        assertEquals(AppError.Unauthorized, repository().bindScannedDevice("865137068739782").errorOrNull())
    }

    // ------------------------------------------------------------------ fakes

    /**
     * Answers are lambdas so a test can *throw* from the same place the real
     * call would, which is the only way to exercise the transport-failure layer.
     */
    private class FakeGateway : GatewayClient {

        var masterAnswer: () -> Response<ApiEnvelope<MasterData>> = { envelope(MasterData()) }
        var startAnswer: () -> Response<ApiEnvelope<JsonElement>> = { envelope<JsonElement>() }
        var stopAnswer: () -> Response<ApiEnvelope<JsonElement>> = { envelope<JsonElement>() }
        var statusAnswer: () -> Response<ApiEnvelope<DeviceStatusData>> = { envelope(DeviceStatusData()) }
        var loginAnswer: () -> Response<ApiEnvelope<LoginData>> = { envelope(LoginData()) }
        var smsAnswer: () -> Response<ApiEnvelope<JsonElement>> = { envelope<JsonElement>() }
        var captchaAnswer: () -> Response<ResponseBody> = { httpError(404) }
        var qrUseAnswer: () -> Response<ApiEnvelope<QrUseData>> = { envelope(QrUseData()) }
        var favouriteAnswer: () -> Response<ApiEnvelope<JsonElement>> = { envelope<JsonElement>() }

        val startCalls = mutableListOf<StartCall>()
        val stopCalls = mutableListOf<String>()
        val statusCalls = mutableListOf<String>()
        val qrUseCalls = mutableListOf<String>()
        val favouriteCalls = mutableListOf<FavouriteCall>()

        override suspend fun captcha(key: String): Response<ResponseBody> = captchaAnswer()

        override suspend fun sendSmsCode(req: SmsCodeRequest): Response<ApiEnvelope<JsonElement>> = smsAnswer()

        override suspend fun login(req: LoginRequest): Response<ApiEnvelope<LoginData>> = loginAnswer()

        override suspend fun master(): Response<ApiEnvelope<MasterData>> = masterAnswer()

        override suspend fun status(did: String): Response<ApiEnvelope<DeviceStatusData>> {
            statusCalls += did
            return statusAnswer()
        }

        override suspend fun start(did: String, ptype: Int, args: String): Response<ApiEnvelope<JsonElement>> {
            startCalls += StartCall(did = did, ptype = ptype, args = args)
            return startAnswer()
        }

        override suspend fun stop(did: String): Response<ApiEnvelope<JsonElement>> {
            stopCalls += did
            return stopAnswer()
        }

        override suspend fun qrUse(id: String): Response<ApiEnvelope<QrUseData>> {
            qrUseCalls += id
            return qrUseAnswer()
        }

        override suspend fun favourite(did: String, remove: Boolean): Response<ApiEnvelope<JsonElement>> {
            favouriteCalls += FavouriteCall(did = did, remove = remove)
            return favouriteAnswer()
        }

        data class StartCall(val did: String, val ptype: Int, val args: String)

        data class FavouriteCall(val did: String, val remove: Boolean)
    }

    private companion object {
        /** A successful envelope, with the payload optional because it often is. */
        fun <T : Any> envelope(data: T? = null, code: Int = 0, msg: String? = null): Response<ApiEnvelope<T>> =
            Response.success(ApiEnvelope(code = code, msg = msg, data = data))

        /**
         * A non-2xx response. Retrofit keeps the body as the *error* body rather
         * than throwing, so this is the layer the caller has to handle itself.
         */
        fun <T : Any> httpError(code: Int): Response<T> =
            Response.error(code, """{"msg":"nope"}""".toResponseBody("application/json".toMediaType()))
    }
}
