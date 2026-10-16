package com.killhuishenghuo.data.remote

import com.killhuishenghuo.core.net.ApiConstants
import com.killhuishenghuo.data.model.ApiEnvelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The gateway, as this client uses it.
 *
 * Methods that only need the envelope's `code` are typed
 * `ApiEnvelope<JsonElement>`: the real payloads vary (`data` is an object on
 * `master`, absent on `dev/start`, an array elsewhere), and a typed body would
 * have to guess. Reading the code and ignoring `data` is honest about what is
 * actually known.
 *
 * `Authorization` and `ApplicationType` are attached by
 * [com.killhuishenghuo.core.net.AuthInterceptor], not declared per method, so
 * there is exactly one place that decides what an authenticated request looks
 * like.
 */
interface HuiShengHuoApi {

    /**
     * 图形验证码. `s` is a 10-character key the caller invents; `r` is a cache
     * buster. Verified live: answers `200 image/jpeg`.
     */
    @GET(ApiConstants.PATH_CAPTCHA)
    suspend fun captcha(
        @Query("s") key: String,
        @Query("r") timestamp: Long,
    ): Response<ResponseBody>

    /** 发送短信验证码. Body is [SmsCodeRequest]; `s` echoes the captcha text. */
    @POST(ApiConstants.PATH_SMS_CODE)
    suspend fun sendSmsCode(
        @Body body: SmsCodeRequest,
    ): Response<ApiEnvelope<kotlinx.serialization.json.JsonElement>>

    /** 登录. Success carries `data.al.token` and `data.al.uid`. */
    @POST(ApiConstants.PATH_LOGIN)
    suspend fun login(
        @Body body: LoginRequest,
    ): Response<ApiEnvelope<com.killhuishenghuo.data.model.LoginData>>

    /**
     * 设备列表 + 账号. `data.favos[]` is the bound device list.
     *
     * Typed loosely because an unauthenticated call legitimately answers
     * `{"code":0,"data":{"ads":[]}}` with no account at all.
     */
    @GET(ApiConstants.PATH_MASTER)
    suspend fun master(): Response<ApiEnvelope<com.killhuishenghuo.data.model.MasterData>>

    /** 设备实时状态. `gene.status` is the running flag. */
    @GET(ApiConstants.PATH_DEVICE_STATUS)
    suspend fun deviceStatus(
        @Query(ApiConstants.PARAM_DID) did: String,
        @Query(ApiConstants.PARAM_MORE) more: Boolean = false,
    ): Response<ApiEnvelope<com.killhuishenghuo.data.model.DeviceStatusData>>

    /**
     * 开热水 — the whole point of this app.
     *
     * Note the parameter defaults, which are the captured client's: a dormitory
     * shower needs `args` to be *present but empty*, and `ptype=91` charges the
     * dormitory wallet.
     */
    @GET(ApiConstants.PATH_DEVICE_START)
    suspend fun startDevice(
        @Query(ApiConstants.PARAM_DID) did: String,
        @Query(ApiConstants.PARAM_UPGRADE) upgrade: Boolean = true,
        @Query(ApiConstants.PARAM_PTYPE) ptype: Int = ApiConstants.PTYPE_WALLET,
        @Query(ApiConstants.PARAM_ARGS) args: String = ApiConstants.ARGS_EMPTY,
        @Query(ApiConstants.PARAM_RCP) rcp: Boolean = false,
        @Query(ApiConstants.PARAM_CNT) cnt: Int = 1,
    ): Response<ApiEnvelope<kotlinx.serialization.json.JsonElement>>

    /** 关热水. */
    @GET(ApiConstants.PATH_DEVICE_END)
    suspend fun stopDevice(
        @Query(ApiConstants.PARAM_DID) did: String,
        @Query(ApiConstants.PARAM_RCP) rcp: Boolean = false,
    ): Response<ApiEnvelope<kotlinx.serialization.json.JsonElement>>

    /**
     * 扫码. Exchanges the id inside a machine's QR sticker for the device it belongs to.
     *
     * Needed only for codes that identify themselves; a code that already is a
     * device id skips this call entirely. See
     * [com.killhuishenghuo.data.model.DeviceQr].
     */
    @GET(ApiConstants.PATH_QR_USE)
    suspend fun qrUse(
        @Query(ApiConstants.PARAM_ID) id: String,
    ): Response<ApiEnvelope<com.killhuishenghuo.data.model.QrUseData>>

    /**
     * 添加设备. `remove=0` puts the device into the account's `favos[]`, which is
     * the only device list this client can read.
     */
    @GET(ApiConstants.PATH_DEVICE_FAVO)
    suspend fun favouriteDevice(
        @Query(ApiConstants.PARAM_DID) did: String,
        @Query(ApiConstants.PARAM_REMOVE) remove: String = ApiConstants.FAVO_ADD,
    ): Response<ApiEnvelope<kotlinx.serialization.json.JsonElement>>
}

/**
 * `acc/login/code` body: phone, captcha text, captcha key.
 *
 * Field names are positional in the captured client's own serialiser
 * (`SmsCodeRequest(un, authCode, s)`) and are reproduced exactly.
 */
@Serializable
data class SmsCodeRequest(
    @SerialName("un") val phone: String,
    @SerialName("authCode") val captchaText: String,
    @SerialName("s") val captchaKey: String,
)

/**
 * `acc/login` body.
 *
 * [clientId] is the captured client's `cid`. It is compiled into the official
 * app as a build secret and its meaning is unconfirmed — see the README's
 * 待补充清单. This client generates a stable per-install value instead of
 * shipping a borrowed one, and the login screen allows overriding it.
 */
@Serializable
data class LoginRequest(
    @SerialName("openCode") val openCode: String = "",
    @SerialName("authCode") val smsCode: String,
    @SerialName("un") val phone: String,
    @SerialName("cid") val clientId: String,
)
