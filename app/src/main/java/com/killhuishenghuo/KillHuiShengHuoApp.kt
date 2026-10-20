package com.killhuishenghuo

import android.app.Application
import com.killhuishenghuo.core.log.AppLog
import com.killhuishenghuo.core.net.GatewayClient
import com.killhuishenghuo.core.net.NetworkModule
import com.killhuishenghuo.core.net.RetrofitGatewayClient
import com.killhuishenghuo.data.local.PreferencesStore
import com.killhuishenghuo.data.local.SharedPreferencesPreferencesStore
import com.killhuishenghuo.data.local.SharedPreferencesSessionStore
import com.killhuishenghuo.data.local.SessionStore
import com.killhuishenghuo.data.repository.AuthRepository
import com.killhuishenghuo.data.repository.AuthRepositoryImpl
import com.killhuishenghuo.data.repository.HuiShengHuoRepository
import com.killhuishenghuo.data.repository.HuiShengHuoRepositoryImpl

/**
 * Owns the objects that outlive a screen: the session, the preferences, the
 * OkHttp stack that carries the session's token, and the two repositories on top.
 *
 * Held here rather than in the Activity so a configuration change — or an
 * Activity recreation while a request is in flight — cannot rebuild the client
 * with a half-written session.
 *
 * Everything is `by lazy`: a launch that lands on first-run setup must not pay
 * for an OkHttp client it will not use until the user signs in.
 */
class KillHuiShengHuoApp : Application() {

    /**
     * First thing in the process, before any `by lazy` can log.
     *
     * The log has to start here rather than in the Activity: an Activity can be
     * recreated many times in one process, and "the app was restarted" is the
     * single most useful line in the file — it explains a whole class of bug
     * report, and it is unrecoverable if the buffer is cleared on rotation.
     */
    override fun onCreate() {
        super.onCreate()
        AppLog.install(appVersion = BuildConfig.VERSION_NAME, buildType = BuildConfig.BUILD_TYPE)
        AppLog.log("App", "session=${if (session.token.isNullOrBlank()) "无" else "有"} account=${session.accountName ?: "无"}")
        AppLog.log("App", "baseUrl=${NetworkModule.baseUrl}")
        AppLog.log("App", "theme=${preferences.themeMode}")
    }

    val session: SessionStore by lazy { SharedPreferencesSessionStore(this) }

    val preferences: PreferencesStore by lazy { SharedPreferencesPreferencesStore(this) }

    val graph: NetworkModule.Graph by lazy { NetworkModule.create(session) }

    val gateway: GatewayClient by lazy { RetrofitGatewayClient(graph.api) }

    val repository: HuiShengHuoRepository by lazy {
        HuiShengHuoRepositoryImpl(
            gateway = gateway,
            // Read at call time, not captured: signing out and back in swaps the
            // token under a repository that outlives both.
            isSignedIn = { session.isLoggedIn },
        )
    }

    val authRepository: AuthRepository by lazy {
        AuthRepositoryImpl(gateway = gateway, session = session)
    }
}
