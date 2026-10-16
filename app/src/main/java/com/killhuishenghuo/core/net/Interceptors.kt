package com.killhuishenghuo.core.net

import com.killhuishenghuo.core.net.ApiConstants.HEADER_APPLICATION_TYPE
import com.killhuishenghuo.core.net.ApiConstants.HEADER_AUTHORIZATION
import com.killhuishenghuo.core.net.ApiConstants.HEADER_VERSION_CODE
import com.killhuishenghuo.core.net.ApiConstants.USER_AGENT
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Everything the gateway requires on *every* call, authenticated or not.
 *
 * The constants here were read from working clients of the same product: the
 * token goes in a bare `Authorization` header (no `Bearer` prefix), the app
 * version is announced twice — once inside `user-agent` and once as
 * `VersionCode` — and the capability set has to match the *token's scope*.
 *
 * That last one is why `ApplicationType` is resolved from the path instead of
 * being stamped with one value: the gateway scopes tokens, `dev/start` accepts
 * only the device scope, and sending an account-scoped advertisement on it is
 * the documented way to be refused with `code == -21`. A caller can still
 * override the header explicitly, which the contract tests use to pin the
 * mapping.
 */
class ClientHeaderInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val applicationType = original.header(HEADER_APPLICATION_TYPE)
            ?: ApiConstants.applicationTypeFor(original.url.encodedPath)

        val request = original.newBuilder()
            .header("user-agent", USER_AGENT)
            .header(HEADER_VERSION_CODE, ApiConstants.APP_VERSION_CODE)
            .header(HEADER_APPLICATION_TYPE, applicationType)
            .build()

        return chain.proceed(request)
    }
}

/**
 * Adds the session token when one exists.
 *
 * The gateway answers `code: -99` rather than `401` when a session has died, so
 * this interceptor deliberately does *not* try to detect that here — it happens
 * once, centrally, in [com.killhuishenghuo.data.remote.ApiResultMapper].
 */
class AuthInterceptor(
    private val tokenProvider: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Never overwrite an explicit token: the login screen sends a token it
        // has just obtained but not yet persisted.
        if (request.header(HEADER_AUTHORIZATION) != null) return chain.proceed(request)

        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header(HEADER_AUTHORIZATION, token)
                .build(),
        )
    }
}

/**
 * Turns a `-99` business code into an authorization failure.
 *
 * Deliberately *not* an interceptor. Doing it here would mean reading the
 * response body inside the chain, which in OkHttp requires peeking a
 * non-rewindable source — a diagnostic that can silently break every response
 * it was added to protect. [com.killhuishenghuo.data.remote.ApiResultMapper]
 * already decodes the body once, so the check lives there, where the bytes are
 * being consumed anyway.
 */
