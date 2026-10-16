package com.killhuishenghuo.core.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the private `X-Garen` / `X-Nami` / `X-Ashe` scheme.
 *
 * The digests below were verified against .NET's
 * `System.Security.Cryptography.MD5`, an implementation that shares no code
 * with this one. That matters: a signature test whose expected bytes come from
 * the implementation under test proves only that the code still does what it
 * did last week, not that it matches the upstream scheme.
 */
class ApiSignerTest {

    private companion object {
        const val GAREN = "1700000000000"
        const val NAMI = "0123456789abcdefg"
        const val ASHE = "dcc0dfd0dc183584b063f60574e638f3"
    }

    @Test
    fun `md5 matches known digests`() {
        assertEquals("2b49abd66cdde0e5d112ec070f2b4c2b", ApiSigner.md5("silkwormfusion.fusionservice.getfeedpromotions"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", ApiSigner.md5("abc"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", ApiSigner.md5(""))
    }

    @Test
    fun `generateAshe reproduces an externally verified digest`() {
        assertEquals(
            ASHE,
            ApiSigner.generateAshe(
                serverName = "SilkwormFusion",
                methodName = "FusionService.GetFeedPromotions",
                garen = GAREN,
                nami = NAMI,
            ),
        )
    }

    /**
     * The inner hash is taken over the lowercased pair; an upstream client that
     * sends the mixed-case pair must therefore produce the same signature.
     */
    @Test
    fun `generateAshe lowercases the servername and methodname pair`() {
        val mixedCase = ApiSigner.generateAshe(
            serverName = "SILKWORMFUSION",
            methodName = "FUSIONSERVICE.GETFEEDPROMOTIONS",
            garen = GAREN,
            nami = NAMI,
        )

        assertEquals(ASHE, mixedCase)
    }

    /**
     * Both header values go into one digest, so the order they are concatenated
     * in is not observable locally — only the server can reject a wrong order.
     * Asserting the reversed order is *different* is what pins that the two are
     * at least not interchangeable.
     */
    @Test
    fun `generateAshe concatenates garen before nami`() {
        val swapped = ApiSigner.generateAshe(
            serverName = "SilkwormFusion",
            methodName = "FusionService.GetFeedPromotions",
            garen = NAMI,
            nami = GAREN,
        )

        assertNotEquals(ASHE, swapped)
    }

    @Test
    fun `generateGaren renders the injected clock as decimal epoch millis`() {
        assertEquals("1700000000000", ApiSigner.generateGaren { 1700000000000L })
        assertEquals("0", ApiSigner.generateGaren { 0L })
    }

    @Test
    fun `generateNami strips dashes and truncates to seventeen characters`() {
        assertEquals("1234567890abcdef1", ApiSigner.generateNami("12345678-90ab-cdef-1234-567890abcdef"))
    }

    @Test
    fun `generateNamiForLoggedIn splices the silk id at the requested index`() {
        val random = "0123456789abcdef"

        val spliced = ApiSigner.generateNamiForLoggedIn(silkId = "SILK1", insertIndex = 5, random = random)

        assertEquals(random.length + "SILK1".length, spliced.length)
        assertEquals("01234SILK1", spliced.substring(0, 10))
        assertEquals("01234SILK156789abcdef", spliced)
    }

    /**
     * The real splice offset is unconfirmed (see TODO-3), so an out-of-range
     * index must degrade to an end of the string rather than abort a request.
     */
    @Test
    fun `generateNamiForLoggedIn coerces an out of range index`() {
        val random = "0123456789abcdef"

        val negative = ApiSigner.generateNamiForLoggedIn(silkId = "S", insertIndex = -1, random = random)
        val beyondEnd = ApiSigner.generateNamiForLoggedIn(silkId = "S", insertIndex = 999, random = random)

        assertEquals("S0123456789abcdef", negative)
        assertEquals("0123456789abcdefS", beyondEnd)
        assertEquals("S".length + random.length, negative.length)
        assertEquals("S".length + random.length, beyondEnd.length)
    }

    @Test
    fun `generateNamiForLoggedIn rejects an empty silk id`() {
        try {
            ApiSigner.generateNamiForLoggedIn(silkId = "", insertIndex = 4, random = "0123456789abcdef")
            fail("an empty silkId must not silently produce an unsigned identity")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("silkId"))
        }
    }

    @Test
    fun `md5 output is always lowercase hex`() {
        val digests = listOf(
            ApiSigner.md5(""),
            ApiSigner.md5("a"),
            ApiSigner.md5("The quick brown fox jumps over the lazy dog"),
            ApiSigner.md5("水控"),
        )

        for (digest in digests) {
            assertEquals(32, digest.length)
            assertTrue("$digest is not lowercase hex", digest.all { it in "0123456789abcdef" })
        }
    }
}
