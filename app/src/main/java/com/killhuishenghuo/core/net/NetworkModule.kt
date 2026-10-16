package com.killhuishenghuo.core.net

import com.killhuishenghuo.BuildConfig
import com.killhuishenghuo.core.log.WireLogInterceptor
import com.killhuishenghuo.data.local.SessionStore
import com.killhuishenghuo.data.remote.HuiShengHuoApi
import com.killhuishenghuo.data.remote.LoginRequest
import com.killhuishenghuo.data.remote.SmsCodeRequest
import com.killhuishenghuo.data.model.ApiEnvelope
import com.killhuishenghuo.data.model.LoginData
import com.killhuishenghuo.data.model.LoginToken
import com.killhuishenghuo.data.model.MasterData
import com.killhuishenghuo.data.model.DeviceStatusData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled dependency graph.
 *
 * The project deliberately avoids a DI framework: there are exactly three
 * objects with a lifetime, and wiring them by hand keeps the request path
 * readable end to end.
 */
object NetworkModule {

    /**
     * `ignoreUnknownKeys` is required, not a nicety: the gateway returns fields
     * this client does not model (`ads`, `ep`, `subs`, …) on almost every
     * response, and a strict parser would reject all of them.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * The gateway root, including the `/api/v1/` prefix — Retrofit resolves
     * every `@GET("captcha/")` against it.
     *
     * Overridable at build time via `-PhuishuiBaseUrl=https://other.host/api/v1/`;
     * see app/build.gradle.kts.
     */
    val baseUrl: String get() = BuildConfig.BASE_URL

    fun okHttpClient(
        tokenProvider: () -> String?,
        /** Kept for tests that build a client without the log. */
        enableLogging: Boolean = true,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(ClientHeaderInterceptor())
        .addInterceptor(AuthInterceptor(tokenProvider))
        // Replaces `HttpLoggingInterceptor` at Level.HEADERS, which was here for
        // two reasons and wrong for both. It wrote to logcat, which the person
        // testing this app cannot read; and HEADERS is the wrong level for a
        // gateway that reports refusals as `HTTP 200` with the reason in the
        // body. This one goes to [AppLog] — exportable from settings — and
        // redacts as it writes.
        .apply {
            if (enableLogging) addInterceptor(WireLogInterceptor())
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun retrofit(client: OkHttpClient, baseUrl: String = this.baseUrl): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    fun api(retrofit: Retrofit): HuiShengHuoApi = retrofit.create(HuiShengHuoApi::class.java)

    /** Builds the whole stack. */
    fun create(session: SessionStore): Graph {
        val client = okHttpClient(tokenProvider = { session.token })
        val api = api(retrofit(client))
        return Graph(api = api, client = client, session = session)
    }

    data class Graph(
        val api: HuiShengHuoApi,
        val client: OkHttpClient,
        val session: SessionStore,
    )
}

/**
 * The three calls that are *not* device calls, kept next to the graph so the
 * whole wire surface of this app is two files: this one and [HuiShengHuoApi].
 *
 * Every function here returns the raw [Response] rather than an [Outcome],
 * because mapping is [com.killhuishenghuo.data.remote.ApiResultMapper]'s job and
 * doing it in two places is how the two drift apart.
 */
interface GatewayClient {

    suspend fun captcha(key: String): Response<ResponseBody>

    suspend fun sendSmsCode(req: SmsCodeRequest): Response<ApiEnvelope<JsonElement>>

    suspend fun login(req: LoginRequest): Response<ApiEnvelope<LoginData>>

    suspend fun master(): Response<ApiEnvelope<MasterData>>

    suspend fun status(did: String): Response<ApiEnvelope<DeviceStatusData>>

    suspend fun start(did: String, ptype: Int, args: String): Response<ApiEnvelope<JsonElement>>

    suspend fun stop(did: String): Response<ApiEnvelope<JsonElement>>
}

/** [GatewayClient] over the Retrofit interface. */
class RetrofitGatewayClient(
    private val api: HuiShengHuoApi,
) : GatewayClient {

    override suspend fun captcha(key: String): Response<ResponseBody> =
        api.captcha(key = key, timestamp = System.currentTimeMillis())

    override suspend fun sendSmsCode(req: SmsCodeRequest): Response<ApiEnvelope<JsonElement>> =
        api.sendSmsCode(req)

    override suspend fun login(req: LoginRequest): Response<ApiEnvelope<LoginData>> =
        api.login(req)

    override suspend fun master(): Response<ApiEnvelope<MasterData>> = api.master()

    override suspend fun status(did: String): Response<ApiEnvelope<DeviceStatusData>> =
        api.deviceStatus(did = did)

    override suspend fun start(
        did: String,
        ptype: Int,
        args: String,
    ): Response<ApiEnvelope<JsonElement>> =
        api.startDevice(did = did, ptype = ptype, args = args)

    override suspend fun stop(did: String): Response<ApiEnvelope<JsonElement>> =
        api.stopDevice(did = did)
}

/** Unused import guard: keeps [LoginToken] referenced for readers of the graph. */
private typealias LoginTokenAlias = LoginToken
