package com.killhuishenghuo.data.model

/**
 * What the UI is allowed to know about failure.
 *
 * Sealed so the ViewModel can map a failure to a single Chinese sentence plus a
 * retry affordance, with no raw exception text leaking into the interface.
 */
sealed class AppError {

    /** No usable network connection. */
    data object Offline : AppError()

    /** The server answered, but with a business-level failure. */
    data class Server(
        val code: Int,
        val message: String?,
    ) : AppError()

    /**
     * `code: -99`. The gateway's one marker for "this session is dead"; it
     * answers it on every authenticated endpoint, including ones that would
     * otherwise work while logged out.
     */
    data object Unauthorized : AppError()

    /** The request never completed in time. */
    data object Timeout : AppError()

    /**
     * `code: -98`. The server's own rate limit, which it applies per action and
     * not per account — retrying immediately makes it worse, so the UI says so
     * rather than offering a retry button that cannot help.
     */
    data object RateLimited : AppError()

    /**
     * A device-control refusal whose cause is known.
     *
     * Separate from [Server] because the sentence is already written: these are
     * account conditions (arrears, no campus card, no auto-debit agreement)
     * where "服务器拒绝了：null" would be a dead end for the user, and the code
     * is kept only so it can still be shown alongside the cause.
     */
    data class Refused(
        val code: Int,
        val message: String,
    ) : AppError()

    /** Anything else — parse failures, TLS problems, unexpected shapes. */
    data class Unexpected(
        val detail: String?,
    ) : AppError()

    /** One sentence, in the language the rest of the interface uses. */
    fun toChinese(): String = when (this) {
        is Offline -> "网络连不上，检查一下连接再试"
        is Unauthorized -> "登录状态失效了，重新登录一次"
        is Timeout -> "服务器没有及时响应，稍后再试"
        is RateLimited -> "操作太频繁，被服务器限制了，等一会儿再点"
        is Refused -> message
        is Server -> message?.takeIf { it.isNotBlank() }
            ?.let { "服务器拒绝了：$it" }
            ?: "服务器拒绝了（错误码 $code）"
        is Unexpected -> detail?.takeIf { it.isNotBlank() } ?: "没预想到的问题"
    }
}
