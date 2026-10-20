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

    override var noticeDismissedIds: Set<String>
        get() {
            // Before this was a set it was a single id under its own key. Anyone who
            // dismissed a notice on 1.3.5 or earlier still has that key, and seeing
            // the same notice again after updating would read as "the button is
            // broken". Union instead of a one-shot migration: the read is free, and
            // there is no ordering in which a user sees a dismissal come back.
            val legacy = prefs.getString(KEY_NOTICE_DISMISSED_LEGACY, null)?.takeIf { it.isNotBlank() }
            val stored = prefs.getStringSet(KEY_NOTICE_DISMISSED, emptySet()).orEmpty()
            return if (legacy == null) stored else stored + legacy
        }
        set(value) = prefs.edit {
            // `putStringSet` copies, so a caller holding the returned set cannot mutate
            // what is on disk behind our back.
            putStringSet(KEY_NOTICE_DISMISSED, value)
            remove(KEY_NOTICE_DISMISSED_LEGACY)
        }

    private companion object {
        const val PREFS_NAME = "kill_huishenghuo_prefs"
        const val KEY_THEME = "theme_mode"
        const val KEY_ONBOARDED = "onboarding_done"
        const val KEY_DEVICE = "selected_device_id"
        const val KEY_NOTICE_FEED = "notice_feed_json"
        const val KEY_NOTICE_DISMISSED = "notice_dismissed_ids"
        const val KEY_NOTICE_DISMISSED_LEGACY = "notice_dismissed_id"
    }
}
