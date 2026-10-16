package com.killhuishenghuo.core.sign

/**
 * The signature the 惠生活798 REST gateway actually checks.
 *
 * This is **not** `X-Ashe`. `X-Ashe` belongs to the official app's private
 * `SilkwormFusion` RPC layer (see [ApiSigner], kept for reference) and is not
 * accepted by the gateway this client talks to. The recipe below is the one
 * read out of working clients of the same product, and it is confirmed by two
 * independent implementations that agree line for line.
 *
 * ## Recipe
 *
 * ```
 * sign = MD5( adId + timeBucket + tokenLast8 + uidLast8 + SALT )
 * ```
 *
 *  - `adId`   — the business/advert id the action belongs to.
 *  - `timeBucket` — `30 * (epochMillis / 30000)`, i.e. the current 30-second
 *    window expressed in tens of milliseconds. A signature is therefore valid
 *    for one 30-second bucket, which is why the timestamp must be read fresh
 *    for every request and never cached alongside the signature.
 *  - `tokenLast8` / `uidLast8` — the **last** eight characters of each; if the
 *    value is shorter than eight, all of it is used.
 *  - `SALT` — a fixed literal shipped inside the official app. It is reproduced
 *    here because the gateway rejects anything else; it is a client constant,
 *    not a per-user secret.
 *  - Output is lowercase hex.
 *
 * ## Scope
 *
 * The gateway only demands this signature on the points/check-in family
 * (`acc/score/score-send`), passed as a `sign` query parameter. The device
 * calls this app lives on — `ui/app/master`, `dev/start` — are authenticated by
 * the `Authorization` token alone. The signer is therefore implemented and
 * tested here in full, but the hot-water path does not need it.
 *
 * All functions are pure apart from [sign], which reads the clock; that is what
 * makes the arithmetic below testable against fixed timestamps.
 */
object GatewaySigner {

    /**
     * Client-side salt compiled into the official app. Reproduced verbatim so
     * the gateway accepts the signature — see the class note.
     */
    const val SALT: String = "aslkdvcniu34h9tgufh278wv2"

    /** Width of the time window a signature stays valid for. */
    const val WINDOW_MILLIS: Long = 30_000L

    /** Number of trailing characters taken from the token and the uid. */
    const val TAIL_LENGTH: Int = 8

    /**
     * Current 30-second window expressed in tens of milliseconds.
     *
     * Exposed separately from [sign] because it is the part worth asserting on:
     * a wrong bucket is indistinguishable from a wrong salt at the server.
     */
    fun timeBucket(epochMillis: Long): Long = (WINDOW_MILLIS / 10) * (epochMillis / WINDOW_MILLIS)

    /** Last [TAIL_LENGTH] characters of [value], or all of it when shorter. */
    fun tail(value: String): String =
        if (value.length >= TAIL_LENGTH) value.substring(value.length - TAIL_LENGTH) else value

    /**
     * The signature for [adId], valid for the window containing [epochMillis].
     */
    fun signAt(
        adId: String,
        token: String,
        uid: String,
        epochMillis: Long,
    ): String = ApiSigner.md5(
        adId + timeBucket(epochMillis) + tail(token) + tail(uid) + SALT,
    )

    /** [signAt] for the current instant. */
    fun sign(
        adId: String,
        token: String,
        uid: String,
        clock: () -> Long = System::currentTimeMillis,
    ): String = signAt(adId, token, uid, clock())
}
