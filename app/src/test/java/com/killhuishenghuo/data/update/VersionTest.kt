package com.killhuishenghuo.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison that decides whether anyone is told to update.
 *
 * Both sides of it are hand-written somewhere: the release tag is typed into a
 * GitHub form, the `versionName` is typed into `build.gradle.kts`. A comparator
 * that disagrees with either of those is not a cosmetic problem — it either nags
 * about an update that does not exist, or stays silent about one that does.
 */
class VersionTest {

    @Test
    fun `a leading v is the same version`() {
        // The tag is `v1.2.4`; BuildConfig says `1.2.4`. They are one version.
        assertEquals(0, Version.compare("v1.2.4", "1.2.4"))
        assertEquals(0, Version.compare("1.2.4", "V1.2.4"))
    }

    @Test
    fun `comparison is numeric, not lexicographic`() {
        // The bug this exists for: "1.2.10" < "1.2.9" as a string, and every
        // tenth patch would then be invisible.
        assertTrue(Version.isNewer("1.2.10", "1.2.9"))
        assertFalse(Version.isNewer("1.2.9", "1.2.10"))
    }

    @Test
    fun `a missing trailing part counts as zero`() {
        assertEquals(0, Version.compare("1.3", "1.3.0"))
        assertTrue(Version.isNewer("1.3.1", "1.3"))
        assertFalse(Version.isNewer("1.3", "1.3.0"))
    }

    @Test
    fun `a suffix is dropped rather than making the tag unreadable`() {
        assertEquals(listOf(1, 2, 4), Version.parts("1.2.4-beta"))
        assertEquals(listOf(1, 2, 4), Version.parts("v1.2.4-rc1"))
    }

    @Test
    fun `something that is not a version is not newer, whatever it says`() {
        // A tag that reads `App` or `latest` must never be reported as an update:
        // the row would then tell the user to download something unnamed.
        assertFalse(Version.isNewer("App", "1.2.4"))
        assertFalse(Version.isNewer("latest", "1.2.4"))
        assertFalse(Version.isNewer("", "1.2.4"))
        assertNull(Version.compare("App", "1.2.4"))
        assertNull(Version.compare("1.2.4", "v"))
    }

    @Test
    fun `the same version is not an update`() {
        assertFalse(Version.isNewer("v1.2.4", "1.2.4"))
        assertFalse(Version.isNewer("1.2.4", "1.2.4"))
    }

    @Test
    fun `an older release is not an update`() {
        // The case that makes a dev build usable: running 1.3.0 while the newest
        // published release is v1.2.4 must read as "已是最新", not as a downgrade.
        assertFalse(Version.isNewer("v1.2.4", "1.3.0"))
    }
}
