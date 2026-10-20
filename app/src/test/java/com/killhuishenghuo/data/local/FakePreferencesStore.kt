package com.killhuishenghuo.data.local

/**
 * In-memory [PreferencesStore] for tests.
 *
 * Hand-written and backed by real fields for the same reason as [FakeSessionStore]:
 * the interesting assertion is what the store holds *afterwards* — a ViewModel that
 * stopped persisting the chosen device would still look correct on screen, and a
 * mock that returns whatever the test stubbed would agree with that bug.
 *
 * The defaults mirror `SharedPreferencesPreferencesStore` on a fresh install:
 * dark theme, onboarding unseen, no device chosen yet.
 */
class FakePreferencesStore(
    override var themeMode: ThemeMode = ThemeMode.DARK,
    override var onboardingDone: Boolean = false,
    override var selectedDeviceId: String? = null,
    override var noticeFeedJson: String? = null,
    override var noticeDismissedIds: Set<String> = emptySet(),
) : PreferencesStore
