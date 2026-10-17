package com.killhuishenghuo.data.update

/**
 * Version strings, compared the way this project writes them.
 *
 * Two shapes reach the comparison and they must agree: `BuildConfig.VERSION_NAME`
 * is `1.2.4`, and the release tag that names the same build is `v1.2.4`. A
 * comparison that treated those as different strings would either nag forever or
 * never nag at all.
 */
object Version {

    /**
     * The numeric parts of [raw], or null when it is not a version.
     *
     * Null rather than an exception because of what the caller does with it: the
     * only question asked here is "should the user be told about an update", and
     * "I could not read the tag" must never be answered with "yes" — a notice
     * that cannot be acted on is worse than no notice.
     *
     * Trailing text is dropped rather than rejected, so `1.2.4-beta` compares as
     * `1.2.4`. The tag on this project's releases is plain, but a pre-release
     * suffix is the obvious next thing to write there.
     */
    fun parts(raw: String): List<Int>? {
        val text = raw.trim().removePrefix("v").removePrefix("V")
        if (text.isEmpty()) return null

        val numbers = text.split('.').mapNotNull { piece ->
            piece.takeWhile(Char::isDigit).toIntOrNull()
        }
        return numbers.ifEmpty { null }
    }

    /**
     * [candidate] against [current]: positive when candidate is newer, negative
     * when older, zero when equal — and **null when either is not a version**.
     *
     * Null rather than zero on purpose. Zero means "equal", and a caller that
     * cannot tell "same version" from "unreadable tag" will eventually ship the
     * wrong one of those two as a message.
     */
    fun compare(candidate: String, current: String): Int? {
        val a = parts(candidate) ?: return null
        val b = parts(current) ?: return null
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    /**
     * True when [candidate] is a later version than [current].
     *
     * Missing trailing parts count as zero, so `1.3` and `1.3.0` are the same
     * version — which matters because one of them is a hand-written tag and the
     * other is a `versionName`. An unreadable tag is never "newer": the caller
     * uses this to decide whether to *tell someone to update*, and a notice that
     * cannot be acted on is worse than no notice.
     */
    fun isNewer(candidate: String, current: String): Boolean =
        (compare(candidate, current) ?: 0) > 0
}
