package com.killhuishenghuo.core.net

/**
 * Where the three signature-header values come from.
 *
 * Kept as an interface so the signing interceptor can be tested with a
 * deterministic fake (fixed clock, fixed device identity, fixed silk_id).
 */
interface SigningHeaderSource {

    /** `X-Garen` as a decimal epoch-milliseconds string. */
    fun garen(): String

    /**
     * `X-Nami`. Implementations decide between the logged-out (17-char UUID
     * prefix) and logged-in (random string + `silk_id`) form.
     */
    fun nami(): String

    /**
     * `X-Ashe` for this request's [serverName] / [methodName] pair.
     */
    fun ashe(serverName: String, methodName: String): String
}
