package com.killhuishenghuo.core.sign

import com.killhuishenghuo.core.net.ApiConstants
import com.killhuishenghuo.core.net.SigningHeaderSource
import com.killhuishenghuo.data.local.SessionStore

/**
 * Produces the three signature headers from the persisted session.
 *
 * The logged-out identity is generated once per install and then reused, so the
 * server does not see a new device on every launch. The logged-in identity is
 * regenerated per request, because it mixes in random characters.
 */
class DeviceIdentity(
    private val session: SessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val uuidFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    private val randomString: (Int) -> String = ApiSigner::randomString,
) : SigningHeaderSource {

    override fun garen(): String = ApiSigner.generateGaren(clock)

    override fun nami(): String {
        val silkId = session.silkId
        return if (!silkId.isNullOrBlank()) {
            ApiSigner.generateNamiForLoggedIn(
                silkId = silkId,
                insertIndex = ApiConstants.SILK_ID_INSERT_INDEX,
                random = randomString(ApiSigner.NAMI_LOGGED_IN_SOURCE_LENGTH),
            )
        } else {
            ApiSigner.generateNami(uuid = stableAnonymousUuid())
        }
    }

    override fun ashe(serverName: String, methodName: String): String =
        ApiSigner.generateAshe(
            serverName = serverName,
            methodName = methodName,
            garen = garen(),
            nami = nami(),
        )

    /** Path used by the interceptor, which must sign exactly the values it sends. */
    fun ashe(serverName: String, methodName: String, garen: String, nami: String): String =
        ApiSigner.generateAshe(serverName, methodName, garen, nami)

    private fun stableAnonymousUuid(): String {
        val existing = session.anonymousNamiSeed
        if (!existing.isNullOrBlank()) return existing
        val fresh = uuidFactory()
        session.anonymousNamiSeed = fresh
        return fresh
    }
}
