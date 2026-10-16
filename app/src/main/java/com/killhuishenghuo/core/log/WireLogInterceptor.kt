package com.killhuishenghuo.core.log

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/**
 * The wire, in both directions, as the log's most useful third.
 *
 * `HttpLoggingInterceptor` is already in this project, at `Level.HEADERS`, and it
 * was the wrong tool for one specific reason: **this gateway reports a failure
 * as a success.** A refusal arrives as `HTTP 200`, so a log of status codes says
 * nothing happened and a log of bodies says what the server actually said. The
 * body is the whole point.
 *
 * `Level.BODY` on the stock interceptor is still wrong, and that is the second
 * reason for this class: it writes to `logcat`, which nobody testing this app can
 * read, and it does not redact. This one writes to [AppLog] — the buffer the user
 * can export from the phone — and runs every header value and body through
 * [LogRedactor] before it is stored.
 *
 * Bodies are read through `peekBody` so the response is not consumed, and a
 * failure to read one is logged rather than thrown: a logging interceptor that
 * can break a request is worse than no logging interceptor.
 */
class WireLogInterceptor(
    private val enabled: () -> Boolean = { true },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!enabled()) return chain.proceed(chain.request())

        val request = chain.request()
        val started = System.nanoTime()
        AppLog.log("HTTP", "→ ${request.method} ${request.url}")

        request.body?.let { body ->
            readBody(body)?.takeIf { it.isNotBlank() }?.let { AppLog.body("HTTP", it) }
        }

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            AppLog.error("HTTP", e, "请求失败 ${request.method} ${request.url}")
            throw e
        }

        val millis = (System.nanoTime() - started) / 1_000_000
        AppLog.log("HTTP", "← ${response.code} ${response.message} (${millis}ms) ${request.url.encodedPath}")

        val body = response.body ?: return response

        // The body is *read and put back*, not peeked.
        //
        // The version this replaced took `source().buffer.snapshot()`, which
        // reads as correct and silently logs nothing: a network response body
        // is unbuffered when an interceptor sees it, so the snapshot is empty
        // and the `isNotBlank` guard drops it. The first log exported from the
        // phone had **only request bodies** for exactly this reason — every
        // response, including the one carrying the device list, was missing.
        //
        // `peekBody` is not available on this OkHttp's `ResponseBody`, so the
        // body is materialised once and handed back as a fresh one. The caller
        // still gets the whole thing; it just gets it from a string instead of
        // from the socket. Gson reads the body in full anyway, so nothing is
        // buffered that was not going to be.
        return runCatching {
            val bytes = body.bytes()
            if (bytes.isNotEmpty()) AppLog.body("HTTP", bytes.decodeToString())
            response.newBuilder()
                .body(bytes.toResponseBody(body.contentType()))
                .build()
        }.getOrElse { failure ->
            AppLog.error("HTTP", failure, "读取响应体失败，原样返回")
            response
        }
    }

    private fun readBody(body: okhttp3.RequestBody): String? = runCatching {
        val buffer = Buffer()
        body.writeTo(buffer)
        buffer.readUtf8()
    }.getOrNull()
}
