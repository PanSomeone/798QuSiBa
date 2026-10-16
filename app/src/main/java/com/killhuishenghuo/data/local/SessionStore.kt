package com.killhuishenghuo.data.local

import java.util.UUID

/**
 * Everything that must survive a process restart.
 *
 * Backed by [SharedPreferencesSessionStore] in production and by an in-memory
 * fake in tests.
 */
interface SessionStore {

    /**
     * Gateway session token, sent verbatim as the `Authorization` header.
     * Present only while signed in.
     */
    var token: String?

    /**
     * The account id the gateway calls `uid`, from `data.account.id`.
     *
     * Not required to list devices, but it is half of the input to
     * [com.killhuishenghuo.core.sign.GatewaySigner], so it is persisted rather
     * than re-fetched whenever the points path is used.
     */
    var uid: String?

    /**
     * `cid`, sent in the login body. Compiled into the official app as a build
     * secret and its meaning is unconfirmed, so this client generates a stable
     * per-install value instead of borrowing one. Persisted so a re-login from
     * the same install looks like the same client.
     */
    val clientId: String

    /**
     * Random per-install identity seed, reused for every logged-out
     * `X-Nami`. Persisted so the server does not see a new device each launch.
     *
     * Only used by the unused `X-Ashe` path — see `core/sign/ApiSigner.kt`.
     */
    var anonymousNamiSeed: String?

    /** `silk_id`. Part of the private RPC scheme; not used by the REST gateway. */
    var silkId: String?

    /**
     * Display label for the signed-in account, from `data.account.name` (or the
     * phone number when the gateway omits a name).
     *
     * Cached rather than re-fetched because the header that shows it renders
     * *before* the device list arrives — a launch that rediscovered the name
     * would flash an empty identity strip on every start.
     */
    var accountName: String?

    /** Signed in when a token exists. */
    val isLoggedIn: Boolean get() = !token.isNullOrBlank()

    companion object {
        /** Stable fallback when a backend has never been asked for a client id. */
        fun newClientId(): String = UUID.randomUUID().toString().replace("-", "").take(24)
    }
}
