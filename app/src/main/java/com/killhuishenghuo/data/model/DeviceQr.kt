package com.killhuishenghuo.data.model

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * What a scanned code turned out to be.
 *
 * Two shapes, and the difference decides the order of the two calls that follow:
 * a code that *is* a device id goes straight to `dev/favo`, while a code that
 * names only itself has to be exchanged at `qr/use` first. Getting the order
 * wrong is not fatal — [com.killhuishenghuo.data.repository.HuiShengHuoRepository]
 * tries the other one when the first is refused — but it decides which call the
 * log shows as refused, and a log where every scan contains one refusal is a log
 * nobody will read twice.
 */
sealed interface QrCandidate {

    /** The code was the device id: the `did` every device call takes. */
    data class DeviceId(val id: String) : QrCandidate

    /** The code identified itself; `qr/use` has to turn it into a device id. */
    data class OpaqueId(val id: String) : QrCandidate

    /** Nothing usable was in it — an empty scan, a wifi password, a sentence. */
    data object Unreadable : QrCandidate
}

/**
 * The payload of the sticker on the machine.
 *
 * Read off two working clients of the same product rather than from a real
 * sticker, because nobody here has one yet — one parses these codes in a
 * multiplatform Kotlin client, the other in an Android widget app, and they
 * agree on the shapes and disagree on the details. Where they agree, this
 * follows; where they disagree, this takes the interpretation that keeps the
 * **raw text** recoverable, which is why the settings screen puts the scanned
 * string in an editable field as well: a parser that guesses wrong is
 * recoverable when the user can see and fix the guess, and unrecoverable when it
 * is hidden behind a button.
 *
 * The forms, as seen in those clients:
 *
 * ```
 * 865137068739782                          the device id itself
 * https://<host>/q/<n>/<token>             a token, exchanged at qr/use
 * https://<host>/q/<n>/bm?/<token>         the same, in the "bm" spelling
 * https://<host>/q/<n>/<kind>?id=<token>   the same, with the token named
 * ```
 *
 * Only the first is a device id. `/q/<n>/` is a route, so `q`, the number and
 * the trailing `bm` are all skipped when looking for the payload.
 */
object DeviceQr {

    /**
     * Bounds on what may be an id.
     *
     * The lower bound is what keeps a route's own numbers out: `/q/1/bm` must not
     * yield `1`. The upper bound is the length at which the string stops being an
     * id and starts being the rest of a URL.
     */
    private const val MIN_LENGTH = 6
    private const val MAX_LENGTH = 32

    private val digits = Regex("^\\d+$")
    private val idShape = Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$")

    /** Schemes this gateway mints links under. `ilife://` is in the captured clients. */
    private val schemes = setOf("http", "https", "ilife")

    /**
     * Segments that name the route rather than the device.
     *
     * Skipping these is what makes `/q/1/bm` yield the *token* instead of `bm`,
     * which would otherwise be handed to `qr/use` as an id and refused.
     */
    private val routeWords = setOf(
        "q", "qr", "bm", "b", "s", "billing", "vip", "wallet",
        "dev", "device", "devices", "did", "bind", "binding",
    )

    fun parse(raw: String): QrCandidate {
        val text = raw.trim()
        if (text.isEmpty()) return QrCandidate.Unreadable

        // A bare number is the device id, and this is the one case worth being
        // sure about: it is what the widget app's users scan.
        if (isDeviceId(text)) return QrCandidate.DeviceId(text)

        val uri = runCatching { URI(text) }.getOrNull() ?: return QrCandidate.Unreadable
        if (uri.scheme?.lowercase() !in schemes) return QrCandidate.Unreadable

        // `?id=<x>` is the only form that says what it is carrying.
        queryValue(uri.rawQuery, "id")?.let { return QrCandidate.OpaqueId(it) }

        val fromPath = uri.rawPath.orEmpty()
            .split('/')
            .map(::decode)
            .filter { it.lowercase() !in routeWords && isPlausibleId(it) }
            .lastOrNull()
        if (fromPath != null) {
            return if (isDeviceId(fromPath)) QrCandidate.DeviceId(fromPath) else QrCandidate.OpaqueId(fromPath)
        }

        // `?/<token>`: the query is not a query, it is the payload. Seen in the
        // `bm` spelling above, where the client that captures these codes does
        // not even split it.
        val loose = decode(uri.rawQuery.orEmpty().trim('/'))
        if (isPlausibleId(loose)) return QrCandidate.OpaqueId(loose)

        return QrCandidate.Unreadable
    }

    /** A string that is nothing but digits, and long enough to be an id. */
    private fun isDeviceId(value: String): Boolean =
        value.length in MIN_LENGTH..MAX_LENGTH && digits.matches(value)

    /**
     * A string that could be an id of either kind.
     *
     * Deliberately loose: everything that reaches this point is handed to a
     * server that knows the answer, and a wrong guess costs one refused call,
     * while a strict pattern costs the user a scan that silently does nothing.
     */
    private fun isPlausibleId(value: String): Boolean =
        value.length in MIN_LENGTH..MAX_LENGTH && idShape.matches(value)

    /** The value of `key` in a `k=v&k=v` query, or null. Not a URL parser: no `+`, no repeats. */
    private fun queryValue(rawQuery: String?, key: String): String? =
        rawQuery.orEmpty()
            .split('&')
            .mapNotNull { part ->
                val name = part.substringBefore('=', "")
                if (name.lowercase() != key) null else decode(part.substringAfter('=', "")).trim()
            }
            .firstOrNull { it.isNotEmpty() }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}
