package com.killhuishenghuo.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** [SessionStore] persisted in a private `SharedPreferences` file. */
class SharedPreferencesSessionStore(context: Context) : SessionStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_TOKEN, value) }

    override var uid: String?
        get() = prefs.getString(KEY_UID, null)
        set(value) = prefs.edit { putString(KEY_UID, value) }

    /**
     * Generated on first read and written back immediately, so the value is
     * stable for the life of the install even if the process dies right after
     * the first login request is built.
     */
    override val clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, null)
            ?: SessionStore.newClientId().also { prefs.edit { putString(KEY_CLIENT_ID, it) } }

    override var anonymousNamiSeed: String?
        get() = prefs.getString(KEY_NAMI_SEED, null)
        set(value) = prefs.edit { putString(KEY_NAMI_SEED, value) }

    override var silkId: String?
        get() = prefs.getString(KEY_SILK_ID, null)
        set(value) = prefs.edit { putString(KEY_SILK_ID, value) }

    override var accountName: String?
        get() = prefs.getString(KEY_ACCOUNT_NAME, null)
        set(value) = prefs.edit { putString(KEY_ACCOUNT_NAME, value) }

    private companion object {
        const val PREFS_NAME = "kill_huishenghuo_session"
        const val KEY_TOKEN = "token"
        const val KEY_UID = "uid"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_NAMI_SEED = "anonymous_nami_seed"
        const val KEY_SILK_ID = "silk_id"
        const val KEY_ACCOUNT_NAME = "account_name"
    }
}
