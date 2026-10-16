package com.killhuishenghuo.core.log

/**
 * Removes the two things in this app that must never leave the phone.
 *
 * The log this project asks a user to send back is a log of a signed-in session.
 * An `Authorization` header that survives the trip is a working credential for
 * whoever ends up reading the file, and a phone number is the part of an account
 * that cannot be reissued. Neither is needed to diagnose anything: which header
 * was sent, and that it had a value, is the whole of the useful information.
 *
 * **Redaction happens at the point of writing, not at the point of export.** A
 * buffer that holds live tokens and masks them on the way out is one refactor
 * away from writing them straight to a file, and a bug in the exporter is not a
 * thing anyone would notice.
 *
 * Pure functions with no Android dependency, so the rules can be asserted rather
 * than eyeballed — see `LogRedactorTest`.
 */
object LogRedactor {

    private const val MASK = "***"

    /**
     * Header and JSON field names differ in punctuation and agree in meaning:
     * `X-Auth-Token`, `x_auth_token` and `xAuthToken` are the same header, and a
     * table that only knows one spelling leaks the other two.
     */
    private fun normaliseKey(key: String): String =
        key.lowercase().filter { !it.isWhitespace() && it != '-' && it != '_' }

    /** Header names whose *value* is a credential, matched case-insensitively. */
    private val SECRET_HEADERS = setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "proxy-authorization",
        "x-auth-token",
    ).map(::normaliseKey).toSet()

    /**
     * JSON keys whose value is a credential or an identity.
     *
     * Written with an optional separator between the words, because the same
     * field arrives as `accessToken`, `access_token` and `access-token`
     * depending on who wrote the handler. Anchored, because the surrounding
     * quotes are what make it a whole key: `tokenExpiry` is not a token and
     * masking it would put a lie in the log.
     */
    private val SECRET_KEYS = listOf(
        "token",
        "authorization",
        "auth[_-]?token",
        "access[_-]?token",
        "proxy[_-]?authorization",
        "password",
        "passwd",
        "secret",
        "mobile",
        "phone",
        "phone[_-]?number",
        "tel",
    )

    /** `"token":"abc123"` → `"token":"***"`. */
    private val jsonSecret = Regex(
        "\"(" + SECRET_KEYS.joinToString("|") + ")\"" +
            "\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A mainland mobile number standing on its own.
     *
     * Bounded by non-digits rather than `\b`, because `\b` treats a letter as a
     * boundary: `mobile=13800138000` would have its tail masked and the whole
     * point of the rule is that the digits are gone as a unit.
     */
    private val barePhone = Regex("(?<![0-9])1[3-9][0-9]{9}(?![0-9])")

    /** `13800138000` → `138****8000`. */
    fun redactPhone(phone: String): String {
        val digits = phone.filter(Char::isDigit)
        if (digits.length < 7) return MASK
        return digits.take(3) + "****" + digits.takeLast(4)
    }

    /** A header value: the scheme stays, because "Bearer" is diagnostic. */
    fun redactHeaderValue(name: String, value: String): String =
        if (normaliseKey(name) in SECRET_HEADERS) redactCredential(value) else redactText(value)

    /**
     * Keeps a recognisable head and tail so two different tokens are still two
     * different strings in the log, which is how you tell "the token did not
     * change" from "the token was replaced".
     *
     * A `name=value` credential is handled by its own rule rather than this one.
     * Head-and-tail on `session=abc123def` yields `sess…3def`, which leaks half
     * the value to preserve a shape that is worth nothing: for a cookie the
     * interesting part is that a `session` cookie was present at all.
     *
     * Eight characters or fewer is masked whole: showing four of six is not a
     * redaction, it is half a password with an ellipsis in it.
     */
    fun redactCredential(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return trimmed

        val scheme = trimmed.substringBefore(' ', "")
        if (scheme.equals("Bearer", ignoreCase = true) || scheme.equals("Basic", ignoreCase = true)) {
            return "$scheme ${mask(trimmed.substringAfter(' '))}"
        }

        // `a=1; b=2` — a cookie, or anything shaped like one. The names are the
        // diagnostic part and the values are the credential, so they part ways.
        if ('=' in trimmed) {
            return trimmed.split(';').joinToString("; ") { pair ->
                val name = pair.substringBefore('=').trim()
                if (name.isEmpty()) MASK else "$name=$MASK"
            }
        }

        return mask(trimmed)
    }

    private fun mask(value: String): String = when {
        value.length <= 8 -> MASK
        else -> value.take(4) + "…" + value.takeLast(4)
    }

    /** Free text: JSON secrets, then any surviving phone number. */
    fun redactText(text: String): String =
        text
            .replace(jsonSecret) { match ->
                val value = match.groupValues[2]
                // A field the server sent as "" means "there is no token".
                // Masking it to *** would claim there was one.
                if (value.isEmpty()) match.value else "\"${match.groupValues[1]}\":\"$MASK\""
            }
            .replace(barePhone) { redactPhone(it.value) }
}
