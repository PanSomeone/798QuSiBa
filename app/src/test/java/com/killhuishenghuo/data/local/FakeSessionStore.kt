package com.killhuishenghuo.data.local

/**
 * In-memory [SessionStore] for tests.
 *
 * Deliberately not a mock: the interesting assertions in the auth tests are about
 * what the store holds *afterwards*, and a hand-written fake that keeps the
 * values in real fields cannot be accidentally stubbed into agreeing with a bug.
 */
class FakeSessionStore(
    override var token: String? = null,
    override var uid: String? = null,
    override var accountName: String? = null,
    override var anonymousNamiSeed: String? = null,
    override var silkId: String? = null,
    /**
     * Stable per-install `cid`. A real install generates this once and keeps it,
     * so a test that let it change per call would not notice a login body that
     * stopped reading it from the session.
     */
    override val clientId: String = "test-client-id",
) : SessionStore
