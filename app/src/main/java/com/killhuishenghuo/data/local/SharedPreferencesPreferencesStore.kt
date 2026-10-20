package com.killhuishenghuo.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * [PreferencesStore] persisted in a private `SharedPreferences` file.
 *
 * A separate file from the session store on purpose: clearing app data for a
 * login problem should not also cost the user their theme, and the two have no
 * reason to share a commit.
 */
class SharedPreferencesPreferencesStore(context: Context) : PreferencesStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var themeMode: ThemeMode
        get() = ThemeMode.of(prefs.getString(KEY_THEME, null))
        set(value) = prefs.edit { putString(KEY_THEME, value.name) }

    override var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDED, value) }

    override var selectedDeviceId: String?
        get() = prefs.getString(KEY_DEVICE, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit {
            // `remove` rather than storing an empty string: the reader treats a
            // blank id as absent anyway, and leaving `""` behind would make the
            // stored state disagree with what the app reports.
            if (value.isNullOrBlank()) remove(KEY_DEVICE) else putString(KEY_DEVICE, value)
        }

    override var noticeFeedJson: String?
        get() = prefs.getString(KEY_NOTICE_FEED, null)
        set(value) = prefs.edit { putString(KEY_NOTICE_FEED, value) }

    override var noticeDismissedId: String?
        get() = prefs.getString(KEY_NOTICE_DISMISSED, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove(KEY_NOTICE_DISMISSED) else putString(KEY_NOTICE_DISMISSED, value)
        }

    private companion object {
        const val PREFS_NAME = "kill_huishenghuo_prefs"
        const val KEY_THEME = "theme_mode"
        const val KEY_ONBOARDED = "onboarding_done"
        const val KEY_DEVICE = "selected_device_id"
        const val KEY_NOTICE_FEED = "notice_feed_json"
        const val KEY_NOTICE_DISMISSED = "notice_dismissed_id"
    }
}
