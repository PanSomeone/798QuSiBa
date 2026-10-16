package com.killhuishenghuo

import com.killhuishenghuo.core.log.AppLog

/**
 * The last thing the gateway refused, in its own words.
 *
 * Exists because of the thing this project keeps running into: the screen shows
 * a *rendered* failure, and the rendered failure is not evidence. The verbatim
 * code and message are what can actually be looked up, and without them a bug
 * report is a paraphrase of a paraphrase.
 *
 * The reason it is needed at all is that the two screens have two ViewModels: a
 * refused `dev/start` lands in the hot-water one, while the feedback form lives
 * in settings and can only see the auth errors. So the failure the report most
 * needs to carry was the one it could not reach.
 *
 * Now that [AppLog] records every response body, this is no longer the only copy
 * — but it is the one-line version, and the copy-paste report is where a user
 * looks first. Kept as a convenience over the log, not as the record.
 */
internal object LastRefusal {
    data class Entry(val code: Int, val message: String?)

    var entry: Entry? = null

    fun record(code: Int, message: String?) {
        entry = Entry(code, message)
        AppLog.log("Refusal", "code=$code message=${message ?: "(无消息)"}")
    }

    fun clear() {
        entry = null
    }

    /** One line for the clipboard report, or null when nothing has failed. */
    fun format(): String? = entry?.let { "${it.code} / ${it.message ?: "(无消息)"}" }
}
