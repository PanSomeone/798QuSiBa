package com.killhuishenghuo.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redaction rules.
 *
 * These are asserted rather than eyeballed because the failure mode is silent
 * and asymmetric: a rule that is too loose leaks a credential into a file the
 * user is about to paste into a chat window, and a rule that is too tight just
 * loses a little colour from a log nobody will miss. Nobody reviewing the output
 * can tell which happened, so the rules have to be written down.
 */
class LogRedactorTest {

    // ------------------------------------------------------------- credentials

    @Test
    fun `an Authorization header keeps its scheme and loses its token`() {
        val result = LogRedactor.redactHeaderValue("Authorization", "Bearer abcdefghijklmnop")

        assertEquals("Bearer abcd…mnop", result)
        assertFalse("the body of the token survived", result.contains("efghijkl"))
    }

    @Test
    fun `a short credential is masked whole`() {
        // Four characters of an eight-character secret is half the secret, and
        // the head/tail trick only earns its place when there is a tail to show.
        assertEquals("***", LogRedactor.redactHeaderValue("Cookie", "abc123"))
    }

    @Test
    fun `a name-equals-value credential keeps the name and drops the value`() {
        // Head-and-tail here would give `sess…on=1`, which leaks half the cookie
        // to preserve a shape worth nothing. The name is the diagnostic part.
        assertEquals("session=***", LogRedactor.redactHeaderValue("Set-Cookie", "session=1"))
        assertEquals("session=***", LogRedactor.redactHeaderValue("Cookie", "session=abc123def456"))
    }

    @Test
    fun `a cookie keeps its attributes`() {
        // Which attributes were set is a real question when a session is not
        // sticking, and none of them are secret.
        val result = LogRedactor.redactHeaderValue("Set-Cookie", "session=abc123def; Path=/; HttpOnly")

        assertEquals("session=***; Path=***; HttpOnly=***", result)
        assertFalse(result, result.contains("abc123def"))
    }

    @Test
    fun `header names are matched without regard to case`() {
        assertEquals("Bearer abcd…mnop", LogRedactor.redactHeaderValue("AUTHORIZATION", "Bearer abcdefghijklmnop"))
        assertEquals("session=***", LogRedactor.redactHeaderValue("Set-Cookie", "session=1"))
    }

    @Test
    fun `header names are matched without regard to punctuation`() {
        // The same credential arrives as X-Auth-Token, x_auth_token or
        // xAuthToken depending on who wrote the client. A table that knows one
        // spelling leaks the other two, and the leak is silent.
        assertEquals("session=***", LogRedactor.redactHeaderValue("X-Auth-Token", "session=1"))
        assertEquals("session=***", LogRedactor.redactHeaderValue("x_auth_token", "session=1"))
        assertEquals("session=***", LogRedactor.redactHeaderValue("XAuthToken", "session=1"))
    }

    @Test
    fun `an ordinary header is left alone`() {
        val result = LogRedactor.redactHeaderValue("Accept", "application/json")

        assertEquals("application/json", result)
    }

    // ------------------------------------------------------------ phone numbers

    @Test
    fun `a phone number keeps a recognisable head and tail`() {
        assertEquals("138****8000", LogRedactor.redactPhone("13800138000"))
    }

    @Test
    fun `a phone number inside a query string is masked`() {
        val redacted = LogRedactor.redactText("POST /api/v1/acc/login/code?mobile=13800138000&type=1")

        assertFalse(redacted.contains("13800138000"))
        assertTrue(redacted, redacted.contains("138****8000"))
        // The parameter name is not a secret and is exactly what makes the line
        // worth reading.
        assertTrue(redacted, redacted.contains("mobile="))
    }

    @Test
    fun `digits that are not a phone number survive`() {
        val line = "dev/start did=884422 ptype=1 args={\"seconds\":600} ts=1731000000"

        assertEquals(line, LogRedactor.redactText(line))
    }

    @Test
    fun `a longer digit run is not half-masked`() {
        // The negative lookarounds exist for this: a nineteen-digit order id is
        // not a phone number with a prefix, and masking its middle would corrupt
        // an identifier that might be the one thing worth grepping for.
        val orderId = "2024101513800138000123"

        assertEquals(orderId, LogRedactor.redactText(orderId))
    }

    // -------------------------------------------------------------- json bodies

    @Test
    fun `a token inside a json body is masked`() {
        val body = """{"code":0,"data":{"token":"eyJhbGciOiJIUzI1NiJ9.long.value","uid":"1"}}"""

        val redacted = LogRedactor.redactText(body)

        assertFalse(redacted, redacted.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(redacted, redacted.contains("\"token\":\"***\""))
        // Everything around it is untouched, or the log stops being a log.
        assertTrue(redacted, redacted.contains("\"uid\":\"1\""))
    }

    @Test
    fun `a mobile field inside a json body is masked even when it is a number`() {
        val redacted = LogRedactor.redactText("""{"mobile":"13800138000"}""")

        assertEquals("""{"mobile":"***"}""", redacted)
    }

    @Test
    fun `whitespace inside a json secret is tolerated`() {
        val redacted = LogRedactor.redactText("""{ "token" : "abcdefghijklmnop" }""")

        assertFalse(redacted, redacted.contains("abcdefghijklmnop"))
    }

    @Test
    fun `a key that merely contains a secret word is not treated as one`() {
        // "tokenExpiry" is not a token. Masking it would be a small lie in the
        // log, and small lies in logs are how a debugging session goes wrong.
        val body = """{"tokenExpiry":1731000000,"refreshToken":""}"""

        val redacted = LogRedactor.redactText(body)

        assertTrue(redacted, redacted.contains("tokenExpiry"))
        assertTrue(redacted, redacted.contains("1731000000"))
    }

    @Test
    fun `a camelCase token key is masked`() {
        val body = """{"accessToken":"eyJhbGciOiJIUzI1NiJ9.long.value"}"""

        val redacted = LogRedactor.redactText(body)

        assertFalse(redacted, redacted.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun `an emptied field stays emptied`() {
        // A field the server sent as "" means "there is no token". Masking it to
        // "***" would claim there was one.
        assertEquals("""{"accessToken":""}""", LogRedactor.redactText("""{"accessToken":""}"""))
    }

    @Test
    fun `emptiness is preserved rather than turned into a mask`() {
        assertEquals("", LogRedactor.redactHeaderValue("Authorization", ""))
        assertEquals("foo=", LogRedactor.redactText("foo="))
    }
}
