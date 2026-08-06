package com.tgwsproxy.vpn

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The absent-vs-empty distinction behind "selected apps only".
 *
 * Worth its own suite because the two states are one `getStringSet` default apart in the source and
 * mean opposite things to the user: never-opened-the-picker must fall back to the curated defaults,
 * while unchecked-everything must be honoured as written. Collapsing them either way is silent —
 * the tunnel still comes up — and the failure only shows as traffic going somewhere the user didn't
 * ask for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TargetPackagesTest {

    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences(DesyncVpnService.PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun anUntouchedPickerFallsBackToTheCuratedDefaults() {
        assertEquals(
            DesyncVpnService.TARGET_APPS.toSet(),
            DesyncVpnService.targetPackages(prefs),
        )
    }

    /**
     * The reason the default is not written to prefs at install time. A build that adds an app to
     * TARGET_APPS has to reach users who already ran the previous one; if the old default had been
     * persisted, they would be pinned to whatever the list looked like when they installed.
     */
    @Test
    fun defaultsAreNotFrozenIntoPrefsByReadingThem() {
        DesyncVpnService.targetPackages(prefs)

        assertTrue(
            "reading the default must not persist it",
            prefs.getStringSet(DesyncVpnService.KEY_TARGET_USER, null) == null,
        )
    }

    @Test
    fun anExplicitSelectionReplacesTheDefaultsWholesale() {
        prefs.edit()
            .putStringSet(DesyncVpnService.KEY_TARGET_USER, setOf("com.example.one"))
            .commit()

        assertEquals(setOf("com.example.one"), DesyncVpnService.targetPackages(prefs))
    }

    /**
     * An explicitly empty set is a real answer, not a missing one. buildTunnel leans on this: its
     * all-apps fallback is gated on the set being non-empty, so if empty read back as the defaults
     * here, unchecking everything would route the curated list instead of nothing.
     */
    @Test
    fun uncheckingEverythingIsHonouredRatherThanTreatedAsUnset() {
        prefs.edit().putStringSet(DesyncVpnService.KEY_TARGET_USER, emptySet()).commit()

        assertEquals(emptySet<String>(), DesyncVpnService.targetPackages(prefs))
    }

    /**
     * The service builds the tunnel from this and the settings UI draws its checkmarks from it, so a
     * caller that mutated the result could desync the two. Kotlin's read-only Set is not a guarantee
     * at runtime; the copy in targetPackages is, and this is what pins it.
     */
    @Test
    fun theReturnedSetIsNotTheLiveOneInsidePrefs() {
        val saved = setOf("com.example.one", "com.example.two")
        prefs.edit().putStringSet(DesyncVpnService.KEY_TARGET_USER, saved.toMutableSet()).commit()

        // Two entries on purpose: a single-element `toSet()` hands back an immutable singleton, which
        // would pass this by throwing rather than by being a copy — and prove nothing about the
        // general case. At this size the copy is a real LinkedHashSet, so a missing one would let the
        // write below reach whatever prefs handed out.
        val first = DesyncVpnService.targetPackages(prefs)
        try {
            (first as MutableSet<String>).add("com.example.three")
        } catch (_: UnsupportedOperationException) {
            // Also fine: an unmodifiable view satisfies the same invariant.
        }

        assertEquals(saved, DesyncVpnService.targetPackages(prefs))
    }
}
