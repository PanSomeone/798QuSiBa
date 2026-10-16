package com.killhuishenghuo.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The QR sticker on the machine, as read by two working clients of the same
 * product rather than by this one — nobody here has a sticker to scan yet.
 *
 * That is the reason the assertions below are about **which interpretation** a
 * string gets and not about a payload captured from a real machine: the parser's
 * job is to pick the likelier of two shapes, the repository tries the other one
 * when the server refuses, and the settings screen shows the raw text in an
 * editable field so a wrong guess is visible and fixable. A parser asserted
 * against invented payloads would be a test of the invention.
 */
class DeviceQrTest {

    @Test
    fun `a bare number is the device id`() {
        // The real id from this project's own device log: 15 digits.
        assertEquals(QrCandidate.DeviceId("865137068739782"), DeviceQr.parse("865137068739782"))
        assertEquals(QrCandidate.DeviceId("865137068739782"), DeviceQr.parse("  865137068739782\n"))
    }

    @Test
    fun `a number too short to be an id is not one`() {
        // `/q/1/bm` must not yield `1`: a route's own numbers are not payload.
        assertEquals(QrCandidate.Unreadable, DeviceQr.parse("1"))
        assertEquals(QrCandidate.Unreadable, DeviceQr.parse("12345"))
    }

    @Test
    fun `a link carrying an explicit id hands over that id`() {
        assertEquals(
            QrCandidate.OpaqueId("AbCd1234"),
            DeviceQr.parse("https://i.ilife798.com/q/1/billing?id=AbCd1234"),
        )
    }

    @Test
    fun `a link whose last segment is the payload hands over the segment`() {
        assertEquals(
            QrCandidate.OpaqueId("AbCd1234"),
            DeviceQr.parse("https://i.ilife798.com/q/1/AbCd1234"),
        )
        // The route's own words and numbers are skipped, not handed to the server.
        assertEquals(
            QrCandidate.OpaqueId("AbCd1234"),
            DeviceQr.parse("https://i.ilife798.com/q/1/bm/AbCd1234"),
        )
    }

    @Test
    fun `a numeric last segment is a device id, not an opaque one`() {
        assertEquals(
            QrCandidate.DeviceId("865137068739782"),
            DeviceQr.parse("https://i.ilife798.com/q/1/865137068739782"),
        )
    }

    @Test
    fun `the bm spelling keeps its token`() {
        // One captured client reads this form with `substringAfter("q/1/")`, which
        // hands `bm?/AbC123xyz` to the server as an id. Splitting it properly is
        // the whole difference between a scan that works and one the server
        // refuses for a reason nobody can see.
        assertEquals(
            QrCandidate.OpaqueId("AbC123xyz"),
            DeviceQr.parse("https://i.ilife798.com/q/1/bm?/AbC123xyz"),
        )
    }

    @Test
    fun `percent-encoded payloads are decoded before they are sent`() {
        assertEquals(
            QrCandidate.OpaqueId("AbCd1234"),
            DeviceQr.parse("https://i.ilife798.com/q/1/AbCd%31%32%33%34"),
        )
    }

    @Test
    fun `things that are not device codes are unreadable`() {
        assertEquals(QrCandidate.Unreadable, DeviceQr.parse(""))
        assertEquals(QrCandidate.Unreadable, DeviceQr.parse("   "))
        assertEquals(QrCandidate.Unreadable, DeviceQr.parse("WIFI:S:MyNet;T:WPA;P:hunter2;;"))
        assertEquals(QrCandidate.Unreadable, DeviceQr.parse("https://example.com/"))
        assertEquals(QrCandidate.Unreadable, DeviceQr.parse("https://example.com/q/1/bm"))
    }

    @Test
    fun `the ilife scheme is a link too`() {
        // The captured clients mint `ilife://` links; treating only http(s) as a
        // link would make those scans fall through to "unreadable".
        assertEquals(
            QrCandidate.OpaqueId("AbCd1234"),
            DeviceQr.parse("ilife://open/q/1/AbCd1234"),
        )
    }
}
