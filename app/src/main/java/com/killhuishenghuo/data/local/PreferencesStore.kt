package com.killhuishenghuo.data.local

/**
 * The preferences that outlive a session.
 *
 * Deliberately not part of [SessionStore]. A session is a credential that the
 * server can revoke; a preference is a choice the user made about the app
 * itself. Logging out clears the first and must never touch the second, and
 * keeping them in separate types makes that a compile-time property rather
 * than a rule someone has to remember.
 */
interface PreferencesStore {

    /**
     * Which palette to paint.
     *
     * Defaults to [ThemeMode.DARK]. This is a control surface for a physical
     * thing that gets used at night; the dark palette is the designed one and
     * light is the accommodation, not the other way round.
     */
    var themeMode: ThemeMode

    /**
     * False until the user has been through the first-run screen.
     *
     * Stored rather than inferred from "has no token", because a signed-out
     * user who has already read the disclaimer must not be shown it again.
     */
    var onboardingDone: Boolean

    /**
     * Which device the one-tap button acts on, or null for "not chosen yet".
     *
     * The main screen has exactly one control, so *which* device it controls is
     * a preference rather than a per-tap decision. Stored as an id rather than an
     * index because device order comes from the server and can change between
     * launches — an index would silently repoint the button at a different
     * physical valve.
     *
     * Null is a real state: with one device the app binds to it automatically,
     * and with several it has to ask.
     */
    var selectedDeviceId: String?

    /**
     * The last announcement feed that parsed, kept as the raw JSON.
     *
     * Raw rather than parsed because parsing belongs to whoever reads it, and because
     * a future field must not require a migration just to keep showing an old notice.
     * The reason to keep it at all: the announcement is fetched from a static file, and
     * a launch with no network should still show what the server said last time instead
     * of going silent — silence looks like "there is no announcement", which would be a
     * different statement from "we could not check".
     */
    var noticeFeedJson: String?

    /**
     * The id of the announcement the user already dismissed.
     *
     * An id, not a boolean and not a timestamp: dismissing today's notice must not
     * swallow tomorrow's. Unknown ids are ignored on read, so the file can be edited
     * to publish a genuinely new notice to people who dismissed an old one.
     */
    var noticeDismissedId: String?
}

/**
 * The three states a theme switch can be in.
 *
 * [SYSTEM] exists because a phone that switches to light at sunrise should be
 * allowed to take the app with it. It is not the default: a fixed dark default
 * means the first launch looks the same at 7am and at 2am, which is the whole
 * point of choosing dark.
 */
enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM;

    companion object {
        fun of(raw: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DARK
    }
}
