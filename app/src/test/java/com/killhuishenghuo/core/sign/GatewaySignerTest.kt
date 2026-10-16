package com.killhuishenghuo.core.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the signature the REST gateway really checks.
 *
 * The two expected values below were computed independently of this code (MD5
 * over the concatenation spelled out in the recipe), so they constrain the
 * *scheme* and not merely the current output. Both the bucket and the digest
 * are asserted because a wrong bucket and a wrong salt are indistinguishable
 * from the server's answer — it rejects with one opaque parameter error.
 */
class GatewaySignerTest {

    private companion object {
        const val AD_ID = "ad12345"
        const val TOKEN = "abcdefgh12345678"
        const val UID = "u0000000xyz98765"
        const val EPOCH_MILLIS = 1700000000000L

        /** Independently verified: `3000 * (EPOCH_MILLIS / 30000)`. */
        const val BUCKET = 169999998000L

        /** Independently verified MD5 of `AD_ID + BUCKET + "12345678" + "xyz98765" + SALT`. */
        const val SIGNATURE = "334be9e6fce7d7632ee98341435b196f"
    }

    @Test
    fun `signAt reproduces an externally verified signature`() {
        assertEquals(SIGNATURE, GatewaySigner.signAt(AD_ID, TOKEN, UID, EPOCH_MILLIS))
    }

    @Test
    fun `timeBucket is the 30 second window expressed in tens of milliseconds`() {
        assertEquals(BUCKET, GatewaySigner.timeBucket(EPOCH_MILLIS))
    }

    /**
     * The bucket is what makes a signature expire. If it moved per millisecond
     * every request would be signed for a window the server never saw; if it
     * never moved a stale signature would stay replayable.
     */
    @Test
    fun `timeBucket is constant within a window and steps by one slice afterwards`() {
        val sameBucket = GatewaySigner.timeBucket(EPOCH_MILLIS + 1_000L)
        val nextWindow = GatewaySigner.timeBucket(EPOCH_MILLIS + GatewaySigner.WINDOW_MILLIS)

        assertEquals(BUCKET, sameBucket)
        assertEquals(GatewaySigner.WINDOW_MILLIS / 10, nextWindow - BUCKET)
    }

    @Test
    fun `tail takes the last eight characters and survives short values`() {
        assertEquals("12345678", GatewaySigner.tail(TOKEN))
        assertEquals("xyz98765", GatewaySigner.tail(UID))
        assertEquals("short", GatewaySigner.tail("short"))
        assertEquals("", GatewaySigner.tail(""))
        assertEquals("12345678", GatewaySigner.tail("12345678"))
    }

    @Test
    fun `different inputs produce different signatures`() {
        assertNotEquals(SIGNATURE, GatewaySigner.signAt("ad99999", TOKEN, UID, EPOCH_MILLIS))
        // Both replacements change the *tail*, which is all the recipe reads: a
        // different prefix with the same last eight characters must still sign
        // identically, and that is asserted where the tail is tested.
        assertNotEquals(SIGNATURE, GatewaySigner.signAt(AD_ID, "abcdefgh87654321", UID, EPOCH_MILLIS))
        assertNotEquals(SIGNATURE, GatewaySigner.signAt(AD_ID, TOKEN, "u0000000aaa11111", EPOCH_MILLIS))
        assertNotEquals(SIGNATURE, GatewaySigner.signAt(AD_ID, TOKEN, UID, EPOCH_MILLIS + GatewaySigner.WINDOW_MILLIS))
    }

    /**
     * The recipe reads only the last eight characters of the token, so two
     * tokens sharing a tail are interchangeable at the gateway. Pinning it here
     * keeps a future "use the whole token" change from looking harmless.
     */
    @Test
    fun `only the tail of the token and the uid reaches the digest`() {
        assertEquals(
            GatewaySigner.signAt(AD_ID, TOKEN, UID, EPOCH_MILLIS),
            GatewaySigner.signAt(AD_ID, "zzzzzzzz12345678", UID, EPOCH_MILLIS),
        )
        assertEquals(
            GatewaySigner.signAt(AD_ID, TOKEN, UID, EPOCH_MILLIS),
            GatewaySigner.signAt(AD_ID, TOKEN, "other-id-xyz98765", EPOCH_MILLIS),
        )
    }

    @Test
    fun `sign reads the injected clock`() {
        assertEquals(SIGNATURE, GatewaySigner.sign(AD_ID, TOKEN, UID) { EPOCH_MILLIS })
    }
}
