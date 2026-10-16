package com.killhuishenghuo.data.remote

import com.killhuishenghuo.LastRefusal
import com.killhuishenghuo.core.net.ApiConstants
import com.killhuishenghuo.data.model.ApiEnvelope
import com.killhuishenghuo.data.model.AppError
import com.killhuishenghuo.data.model.Outcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Maps a Retrofit [Response] onto [Outcome].
 *
 * Four distinct layers can fail and each produces a different sentence:
 *   1. transport — an [IOException] thrown by the call, mapped in [mapThrown].
 *   2. HTTP status — a non-2xx response.
 *   3. business status — a 2xx response whose envelope `code` is not `0`.
 *   4. missing payload — `code: 0` with no `data`, which the gateway really
 *      does return for `dev/start` (success is reported by the code alone).
 */
object ApiResultMapper {

    /** For calls that carry a payload: succeeds only when `data` is present. */
    fun <T : Any> map(response: Response<out ApiEnvelope<T>>): Outcome<T> {
        envelopeError(response)?.let { return Outcome.Failure(it) }

        // Safe: envelopeError returns non-null for exactly the cases where the
        // body or its data is absent, so both are present by this point.
        val data = response.body()?.data
            ?: return Outcome.Failure(AppError.Unexpected("响应缺少 data 字段"))

        return Outcome.Success(data)
    }

    /**
     * For action calls where the business code *is* the answer.
     *
     * `dev/start` answers `{"code":0}` with no `data` at all, so requiring a
     * payload here would report a successfully opened valve as a failure.
     *
     * **No tolerance for "already in that state"** — see [mapStartAction] for the
     * one call that has it. The default has to be the strict one, because the
     * tolerance is only correct in the direction that wants the valve open, and a
     * default that guesses the direction gets it wrong half the time.
     */
    fun mapAction(response: Response<out ApiEnvelope<*>>): Outcome<Unit> {
        envelopeError(response)?.let { return Outcome.Failure(it) }
        return Outcome.Success(Unit)
    }

    /**
     * [mapAction], for a request that wanted the valve **open**.
     *
     * This gateway refuses `dev/start` for a valve that is already open *with a
     * message saying so*, and it is a business failure (2xx, `code != 0`), so the
     * plain path turns it into "服务器拒绝了：设备正在运行" — a rejection prefix
     * bolted onto a sentence that is not a rejection. The user reads that as "it
     * didn't work", on a device where the thing they asked for is already true.
     * The desired state holds, so the outcome should say so; what was actually
     * wrong is the screen's idea of the device, and that is the ViewModel's to
     * correct.
     *
     * **Only ever for opening.** The tolerance means "you already have what you
     * asked for", and on a close the refusal means the exact opposite — the valve
     * is still open. A single untyped `mapAction` applied this to both directions
     * and shipped: on an already-shut valve a refused `dev/end` was reported as a
     * successful close, so the app claimed to have closed something it had never
     * opened, and the refusal that said otherwise reached the log and nothing
     * else. Splitting the function is what makes that mistake unavailable rather
     * than merely discouraged.
     */
    fun mapStartAction(response: Response<out ApiEnvelope<*>>): Outcome<Unit> {
        val error = envelopeError(response)
        val swallowed = error != null && isAlreadyOpen(error)
        LastAction.record(swallowed, (error as? AppError.Refused)?.code ?: 0)
        if (error != null && !swallowed) return Outcome.Failure(error)
        return Outcome.Success(Unit)
    }

    /**
     * [mapAction] for a request that wanted the valve **shut**.
     *
     * The same tolerance, and the reason it had to stop being the same function.
     * A refused close that comes back `-2 设备运行中,请在设备上点击结算` is not a
     * failure — the valve is off and the account's session is not — but it is not
     * a plain success either, because the user has something to do at the machine
     * before the next start will be accepted. [LastAction] carries that
     * distinction; the screen is what decides what to say about it.
     */
    fun mapCloseAction(response: Response<out ApiEnvelope<*>>): Outcome<Unit> {
        val error = envelopeError(response)
        // Narrower than the start path on purpose. Only `-2` is accepted here,
        // and the difference is a real one that the first cut of this function
        // got wrong by reusing `isAlreadyOpen`:
        //
        //   -2 设备运行中,请在设备上点击结算  → the valve is off, and the account
        //      has a session open on it. Reporting "stopped" is true; the part the
        //      user needs is that something must be settled at the machine, which
        //      LastAction carries up.
        //   -15 当前设备正在使用              → someone **else** is using it. The
        //      valve is open and this close did not shut it. Reporting success
        //      here was the shipped bug that made the app claim to have closed
        //      something it had never opened.
        val swallowed = error is AppError.Refused &&
            error.code == ApiConstants.CODE_SETTLE_ON_DEVICE
        LastAction.record(swallowed, (error as? AppError.Refused)?.code ?: 0)
        if (error != null && !swallowed) return Outcome.Failure(error)
        return Outcome.Success(Unit)
    }

    /**
     * True when a refusal actually means the valve is already open.
     *
     * Two shapes qualify, and they qualify for the same reason — the state the
     * request asked for is the state the device is in:
     *
     * - `设备运行中,请在设备上点击结算` (`-2`), which is what this device answers
     *   for a session that is still open and has to be settled at the machine.
     * - any message that says a running device is in the way, matched on text
     *   because **no code was ever observed for the plain "already running"
     *   case** — the captured clients do not document one, and that refusal was
     *   found by using the app rather than by reading a reference. A code match
     *   there would be a guess dressed as knowledge.
     *
     * Both error shapes are accepted, because a refusal with a known cause is
     * already an [AppError.Refused] by the time it gets here.
     *
     * Deliberately narrow: it has to mention a running *device*, so that an
     * unrelated refusal that happens to contain 运行 — a server-side job, an
     * upstream fault — is not silently swallowed as success.
     */
    private fun isAlreadyOpen(error: AppError): Boolean {
        if (error is AppError.Refused && error.code == ApiConstants.CODE_SETTLE_ON_DEVICE) return true

        val message = when (error) {
            is AppError.Server -> error.message
            is AppError.Refused -> error.message
            else -> null
        } ?: return false

        if (!message.contains("运行")) return false
        return message.contains("设备") || message.contains("already")
    }

    /**
     * What [mapAction] swallowed on the last call, for the caller to read.
     *
     * `mapAction` exists to turn an "it is already like that" refusal into
     * success, and that is the right call — but the caller asked a question and
     * deserves the answer, not just "fine". The close case is where it shows: a
     * `-2` back from `/dev/end` means the session is **still open and has to be
     * settled at the machine**, which is not the same thing as "stopped", and a
     * screen that reports plain success leaves the user with no idea why the next
     * start will be refused.
     *
     * Two fields rather than one because the callers need different amounts of
     * detail: `closeHotWater` only needs [alreadyOpen], while the log wants the
     * code so a refusal can be quoted rather than paraphrased. A mutable
     * module-level slot is not pretty, but the alternative is teaching every
     * layer between here and the screen about a refusal that has no other effect —
     * and this whole file already exists to keep that translation in one place.
     */
    internal object LastAction {
        var alreadyOpen: Boolean = false
        var code: Int = 0

        fun record(swallowed: Boolean, code: Int = 0) {
            alreadyOpen = swallowed
            this.code = code
        }
    }

    /** Maps an image response (`captcha/`) onto bytes. */
    fun mapBytes(response: Response<ResponseBody>): Outcome<ByteArray> {
        if (!response.isSuccessful) return Outcome.Failure(AppError.Server(response.code(), null))

        val body = response.body()
            ?: return Outcome.Failure(AppError.Unexpected("验证码响应为空"))

        return runCatching { body.bytes() }.fold(
            onSuccess = { bytes ->
                if (bytes.isEmpty()) {
                    Outcome.Failure(AppError.Unexpected("验证码图片为空"))
                } else {
                    Outcome.Success(bytes)
                }
            },
            onFailure = { Outcome.Failure(toAppError(it)) },
        )
    }

    /** Maps an exception thrown by the Retrofit call itself. */
    fun mapThrown(throwable: Throwable): Outcome<Nothing> = Outcome.Failure(toAppError(throwable))

    fun toAppError(throwable: Throwable): AppError = when (throwable) {
        is UnknownHostException -> AppError.Offline
        is SocketTimeoutException -> AppError.Timeout
        is SerializationException -> AppError.Unexpected("响应格式无法解析")
        is IOException -> AppError.Offline
        else -> AppError.Unexpected(throwable.message?.take(160))
    }

    // ------------------------------------------------------------------ core

    /**
     * `null` when the response is a usable success.
     *
     * Takes `Response<ApiEnvelope<Any?>>` rather than a star projection because
     * Retrofit's `Response<T>` is invariant; [ApiEnvelope] being covariant in
     * `T` is what lets a typed response widen into this parameter.
     */
    private fun envelopeError(response: Response<out ApiEnvelope<Any?>>): AppError? {
        if (!response.isSuccessful) {
            return httpError(response.code(), response.errorBody())
        }

        val envelope = response.body()
            ?: return AppError.Unexpected("服务器返回了空响应体（HTTP ${response.code()}）")

        if (envelope.isSuccess) return null

        // Recorded here, for **every** refusal and not only the unrecognised ones.
        // It used to sit in the `else` branch, which meant the codes the app knows
        // best — the ones with a sentence of their own — were the only ones that
        // never reached the report. What matters when a user says "it refused" is
        // the gateway's own code and words, and the app knowing the code is a
        // reason to keep it, not a reason to drop it.
        LastRefusal.record(envelope.code, envelope.message)

        return when {
            envelope.isSessionExpired -> AppError.Unauthorized
            envelope.isRateLimited -> AppError.RateLimited

            // A refusal that has a known cause gets the cause, not the number.
            // These are the whole answer to "why did nothing happen": the
            // gateway refuses to open a valve for account reasons, and the
            // server's own message for them is often absent or unhelpful.
            envelope.code in KNOWN_REFUSALS -> AppError.Refused(
                code = envelope.code,
                message = KNOWN_REFUSALS.getValue(envelope.code),
            )

            else -> AppError.Server(envelope.code, envelope.message)
        }
    }

    /**
     * Device-control refusals whose meaning was read out of a working client of
     * the same product rather than guessed.
     *
     * Kept as one table because the sentence is the point — a code alone asks
     * the user to go look it up somewhere this app does not provide.
     */
    private val KNOWN_REFUSALS: Map<Int, String> = mapOf(
        ApiConstants.CODE_PAYMENT_REQUIRED to "余额不足或这一单还没付钱。",
        ApiConstants.CODE_ARREARS to "这个账号欠费了。",
        ApiConstants.CODE_NO_AGREEMENT to "还没有签约自动扣费。",
        ApiConstants.CODE_AGREEMENT_EXPIRED to "自动扣费签约过期了。",
        ApiConstants.CODE_NO_CARD to "这个账号还没绑定校园卡。",
        ApiConstants.CODE_DEVICE_NOT_READY to "设备还没准备好，等一下再试。",
        ApiConstants.CODE_WRONG_SCOPE to "登录的权限不对，需要设备控制登录。",
        // Both of these were observed on a real device, and both mean the machine
        // is not available to *this* account. The wording avoids the word 拒绝:
        // the server did not reject the request, it reported the device's state,
        // and there is nothing to retry.
        ApiConstants.CODE_DEVICE_IN_USE to "这台设备有人正在用，等它空下来再试。",
        ApiConstants.CODE_SETTLE_ON_DEVICE to "上一次的使用还没结算，需要在设备上点一下结算。",
    )

    private fun httpError(code: Int, errorBody: ResponseBody?): AppError {
        if (code == HTTP_UNAUTHORIZED) return AppError.Unauthorized

        val raw = runCatching { errorBody?.string() }.getOrNull()

        // The gateway answers JSON on failures too, so a bare status code is a
        // worse message than whatever the server bothered to write.
        val message = raw
            ?.takeIf { it.isNotBlank() }
            ?.let { body ->
                runCatching { JSON.decodeFromString(EnvelopeError.serializer(), body) }.getOrNull()
                    ?.message
            }

        return AppError.Server(code, message)
    }

    private const val HTTP_UNAUTHORIZED = 401

    /** Minimal parser for an error body; the real envelope needs a type argument. */
    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @kotlinx.serialization.Serializable
    private data class EnvelopeError(
        @kotlinx.serialization.SerialName("msg") val msg: String? = null,
        @kotlinx.serialization.SerialName("message") val messageField: String? = null,
    ) {
        val message: String? get() = msg ?: messageField
    }
}
