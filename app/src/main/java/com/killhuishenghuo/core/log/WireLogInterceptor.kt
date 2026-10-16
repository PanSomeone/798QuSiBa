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
            val type = body.contentType()
            when {
                bytes.isEmpty() -> Unit

                isTextual(type) -> AppLog.body("HTTP", bytes.decodeToString())

                // A picture is not a log line. `captcha/` answers with a PNG, and
                // decoding 1 KB of it into a string writes box-drawing noise and
                // **NUL bytes** into the buffer — which is what the exported
                // `.txt` actually contained: 43 NULs, and a file that ordinary
                // text tools (and the person trying to read the report) refuse to
                // open. The size and type keep the "we did get an image" fact,
                // which is the only part of it worth keeping.
                else -> AppLog.log("HTTP", "body: ${bytes.size} bytes of $type, not logged")
            }
            response.newBuilder()
                .body(bytes.toResponseBody(body.contentType()))
                .build()
        }.getOrElse { failure ->
            AppLog.error("HTTP", failure, "读取响应体失败，原样返回")
            response
        }
    }

    /**
     * Whether a body can be written into the log as text.
     *
     * A missing content type counts as text: the gateway omits it on some
     * answers, and dropping those would lose the JSON this log exists for. The
     * decision is deliberately about the *declared* type rather than a sniff of
     * the bytes — a body that says `application/json` and turns out to be
     * mangled is a bug worth seeing, not one worth hiding.
     */
    private fun isTextual(type: okhttp3.MediaType?): Boolean {
        if (type == null) return true
        if (type.type == "text") return true

        // `application/problem+json` and friends: the suffix after `+` is the
        // real format.
        return when (type.subtype.substringAfterLast('+')) {
            "json", "xml", "html", "javascript", "x-www-form-urlencoded" -> true
            else -> false
        }
    }

    private fun readBody(body: okhttp3.RequestBody): String? = runCatching {
        val buffer = Buffer()
        body.writeTo(buffer)
        buffer.readUtf8()
    }.getOrNull()
}
