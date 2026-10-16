package com.killhuishenghuo.data.model

/**
 * The result type every repository call returns.
 *
 * Deliberately not `kotlin.Result`: the failure side must be the closed
 * [AppError] set so the UI can render a specific sentence for each case.
 */
sealed interface Outcome<out T> {

    data class Success<T>(val value: T) : Outcome<T>

    data class Failure(val error: AppError) : Outcome<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): AppError? = (this as? Failure)?.error
}

/** Transform a successful value, leaving a failure untouched. */
inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

/** Run [block] only on success. */
inline fun <T> Outcome<T>.onSuccess(block: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) block(value)
    return this
}

/** Run [block] only on failure. */
inline fun <T> Outcome<T>.onFailure(block: (AppError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) block(error)
    return this
}
