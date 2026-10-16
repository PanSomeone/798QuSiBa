package com.killhuishenghuo.core.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything that happened in this process, in order, kept in memory until the
 * user sends it.
 *
 * This exists because of a wall the project hit twice: the only record of a
 * failed request was a sentence rendered on a screen, and a sentence is not
 * evidence. When the gateway answers `{"code":1201,"message":"设备正在运行"}` and
 * the screen shows "服务器拒绝了", the useful half of that exchange is gone by the
 * time anyone can look at it. Asking a user to run `adb logcat` is not an offer
 * this project can make — the person testing it is holding a phone and a
 * dormitory room, not a workstation.
 *
 * So: an in-memory ring buffer, written from the moment [install] runs in
 * `Application.onCreate`, with one button in settings that hands the whole thing
 * over as a file.
 *
 * Three decisions worth stating, because each of them is a tradeoff against
 * losing something the next bug will need:
 *
 * - **In memory, not on disk, except at export.** A log file written on every
 *   request means I/O on the request path and a file that outlives the bug.
 *   The buffer is capped instead, and the cap is generous enough to hold several
 *   app launches' worth of a session.
 * - **Redacted as it is written, never on the way out.** See [LogRedactor]. A
 *   buffer holding live tokens that masks them at export is one refactor from a
 *   file holding live tokens.
 * - **Timestamps are wall-clock with milliseconds.** A login that took four
 *   seconds and a login that was refused instantly are different bugs, and
 *   `logcat`'s default second resolution hides exactly that.
 *
 * Not thread-safe by locking a single monitor per call, which is enough: the
 * writers are the OkHttp dispatcher and the main thread, and neither holds the
 * lock for longer than an append.
 */
object AppLog {

    /**
     * Big enough for a full session of a few requests and the UI events between
     * them; small enough that the array never matters. A log that scrolls the
     * beginning off is still useful — the beginning is the part that has been
     * read by the time anyone exports.
     */
    private const val CAPACITY = 4000

    /** Per-entry cap for a stack trace. A recursive failure can be megabytes. */
    private const val TRACE_CAP = 2000

    /**
     * Per-entry cap for a response body.
     *
     * Sized so `ui/app/master` arrives whole: that payload carries the device
     * array, and the device array carries `gene.status`, which is the field this
     * whole log exists to look at. At 1500 it was cut off mid-object, so the one
     * answer it was captured for fell on the wrong side of the ellipsis.
     */
    private const val BODY_CAP = 4000

    private val lines = ArrayDeque<String>(CAPACITY)
    private val lock = Any()

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var startedAtMillis: Long = System.currentTimeMillis()

    /**
     * Called once, first thing, from `Application.onCreate`.
     *
     * Resets the buffer rather than appending: this is the beginning of *this*
     * process, and a log that silently continues from a previous run makes every
     * timestamp ambiguous. The process boundary is also the most useful line in
     * the file, because "the app was restarted" is a fact that explains a whole
     * class of bug reports.
     */
    fun install(appVersion: String, buildType: String) {
        synchronized(lock) {
            lines.clear()
            startedAtMillis = System.currentTimeMillis()
        }
        log("AppLog", "process start · version=$appVersion build=$buildType")
        log("AppLog", "android=${android.os.Build.VERSION.RELEASE} (api ${android.os.Build.VERSION.SDK_INT}) " +
            "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }

    /** One line. [tag] is short and greppable; [message] is already redacted. */
    fun log(tag: String, message: String) {
        val safe = LogRedactor.redactText(message)
        val stamp = synchronized(lock) { clock.format(Date()) }
        append("$stamp  ${tag.padEnd(12)}  $safe")
    }

    /** An event with a redacted textual detail. */
    fun event(tag: String, message: String) = log(tag, message)

    /**
     * A throwable. The class and message go on the header line so the trace is
     * skippable; the trace itself is truncated, because a stack overflow would
     * otherwise push everything else out of the buffer.
     */
    fun error(tag: String, throwable: Throwable, context: String? = null) {
        val where = throwable.stackTrace.firstOrNull()?.let { " at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
        log(tag, buildString {
            context?.let { append(it).append(" · ") }
            append(throwable::class.java.simpleName)
            append(": ").append(throwable.message ?: "(无消息)")
            where?.let { append(it) }
        })
        val trace = throwable.stackTraceToString()
        if (trace.isNotBlank()) {
            appendBlock(trace.take(TRACE_CAP))
        }
    }

    /** A response body, capped. Redaction happens in [appendBlock]. */
    fun body(tag: String, text: String) {
        if (text.isBlank()) {
            log(tag, "body: (empty)")
            return
        }
        val capped = if (text.length > BODY_CAP) {
            text.take(BODY_CAP) + " …(截断，共 ${text.length} 字符)"
        } else {
            text
        }
        appendBlock("$tag  body: $capped")
    }

    private fun appendBlock(text: String) {
        val safe = LogRedactor.redactText(text)
        val stamp = synchronized(lock) { clock.format(Date()) }
        safe.lineSequence().forEach { append("$stamp  ${" ".padEnd(12)}  $it") }
    }

    private fun append(line: String) {
        synchronized(lock) {
            if (lines.size >= CAPACITY) lines.removeFirst()
            lines.addLast(line)
        }
    }

    /** How many lines are held, for the settings row. */
    val size: Int get() = synchronized(lock) { lines.size }

    /** Milliseconds since this process started, for the header. */
    val uptimeMillis: Long get() = System.currentTimeMillis() - startedAtMillis

    /**
     * The whole buffer with a header, for the export file.
     *
     * The header repeats the facts that make the body readable without the
     * reader having to hold any of them in their head: when this process
     * started, and how long it has been up.
     */
    fun dump(appVersion: String): String = synchronized(lock) {
        buildString {
            appendLine("# 惠生活去死吧 · 运行日志")
            appendLine("# 版本: $appVersion   行数: ${lines.size}")
            appendLine("# 导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("# 进程已运行: ${uptimeMillis / 1000} 秒")
            appendLine("# token 与手机号已脱敏；本文件不含可用凭据")
            appendLine()
            lines.forEach { appendLine(it) }
        }
    }
}
