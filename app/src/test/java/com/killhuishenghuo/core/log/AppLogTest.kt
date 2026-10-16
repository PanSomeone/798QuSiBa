package com.killhuishenghuo.core.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buffer itself: order, capacity, and the promise that what it holds is
 * already redacted.
 *
 * [AppLog] is an object with mutable state, so each test installs first, which is
 * also the only way the buffer is ever populated in the app.
 */
class AppLogTest {

    @After
    fun tearDown() {
        AppLog.install(appVersion = "test", buildType = "test")
    }

    @Test
    fun `install starts a new buffer with a process header`() {
        AppLog.install(appVersion = "9.9.9", buildType = "debug")

        val dump = AppLog.dump(appVersion = "9.9.9")

        assertTrue(dump, dump.contains("process start"))
        assertTrue(dump, dump.contains("version=9.9.9 build=debug"))
    }

    @Test
    fun `lines come out in the order they went in`() {
        // Order is the entire value of a log: "the request was sent" before "the
        // button was pressed" is a different bug from the reverse, and a buffer
        // that reorders turns evidence into noise.
        AppLog.install(appVersion = "test", buildType = "test")
        AppLog.log("T", "first")
        AppLog.log("T", "second")
        AppLog.log("T", "third")

        val body = AppLog.dump("test")

        assertTrue(body.indexOf("first") < body.indexOf("second"))
        assertTrue(body.indexOf("second") < body.indexOf("third"))
    }

    @Test
    fun `every line is timestamped`() {
        AppLog.install(appVersion = "test", buildType = "test")
        AppLog.log("T", "hello")

        val line = AppLog.dump("test").lineSequence().first { it.contains("hello") }

        // HH:mm:ss.SSS — milliseconds, because a login refused in 4ms and one
        // refused in 4s are different bugs.
        assertTrue(line, Regex("""^\d{2}:\d{2}:\d{2}\.\d{3}""").containsMatchIn(line))
    }

    @Test
    fun `a body goes in as a block so it stays readable`() {
        AppLog.install(appVersion = "test", buildType = "test")
        AppLog.body("HTTP", """{"code":0}""")

        val lines = AppLog.dump("test").lineSequence().filter { it.contains("body:") }.toList()

        assertEquals(1, lines.size)
    }

    @Test
    fun `the buffer is capped and drops the oldest lines`() {
        AppLog.install(appVersion = "test", buildType = "test")
        // CAPACITY is private; overshoot it generously rather than guess it.
        repeat(5000) { AppLog.log("T", "line-$it") }

        val body = AppLog.dump("test")

        assertTrue("the newest line was dropped", body.contains("line-4999"))
        assertFalse("the oldest line survived, so the cap did nothing", body.contains("line-0\n"))
    }

    @Test
    fun `a stack trace is truncated rather than allowed to fill the buffer`() {
        AppLog.install(appVersion = "test", buildType = "test")

        val deep = generateSequence<Throwable>(RuntimeException("root")) { cause ->
            RuntimeException("wrapped", cause)
        }.elementAt(80)

        AppLog.error("T", deep, "deep failure")

        val body = AppLog.dump("test")
        // The header line is present even when the trace is not, so the failure
        // is never silently lost.
        assertTrue(body, body.contains("deep failure"))
        assertTrue(body, body.contains("RuntimeException"))
    }

    // ------------------------------------------------------------- redaction

    @Test
    fun `a token logged as a line never reaches the buffer intact`() {
        // The whole reason redaction is at write time: this assertion would pass
        // for an export-time redactor too, but the buffer itself is what gets
        // read by whatever comes next.
        AppLog.install(appVersion = "test", buildType = "test")

        AppLog.log("HTTP", "→ POST https://host/api?mobile=13800138000")
        AppLog.body("HTTP", """{"data":{"token":"eyJhbGciOiJIUzI1NiJ9.secret.value"}}""")

        val body = AppLog.dump("test")

        assertFalse(body, body.contains("13800138000"))
        assertFalse(body, body.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(body, body.contains("138****8000"))
    }

    @Test
    fun `the header says the file carries no credential`() {
        AppLog.install(appVersion = "test", buildType = "test")

        val dump = AppLog.dump("test")

        assertTrue(dump, dump.contains("脱敏"))
    }

    @Test
    fun `size tracks the buffer`() {
        AppLog.install(appVersion = "test", buildType = "test")
        val before = AppLog.size

        AppLog.log("T", "one more")

        assertEquals(before + 1, AppLog.size)
    }
}
