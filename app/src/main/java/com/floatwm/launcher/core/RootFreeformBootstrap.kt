package com.floatwm.launcher.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root-only enhancement. NOT part of the original zero-ADB/non-root design
 * (see README) -- this is specifically for setups like a Magisk-rooted
 * AxionOS device, where su is available and the Developer Options toggle
 * for freeform may be hidden on this particular build (see the platform
 * note in FreeformCapability). Every non-root code path is untouched: if
 * su isn't available, this quietly does nothing and the app behaves exactly
 * as it did before, falling back to Tier B same as always.
 *
 * What it does, once, ever (tracked in its own SharedPreferences so it
 * doesn't re-run every launch):
 *   settings put global enable_freeform_support 1
 *   settings put global force_resizable_activities 1
 * These are the same two Settings.Global flags Nougat's original "Force
 * activities to be resizable" Developer Options switch flips, and they are
 * still what backs FEATURE_FREEFORM_WINDOW_MANAGEMENT on current Android
 * builds even where Google has removed the direct UI toggle.
 *
 * Both flags require System UI to restart before PackageManager reflects
 * the change. A full reboot would work but is a bad first impression for
 * an app that's supposed to just work after one tap; `killall
 * com.android.systemui` is the standard rooted-device shortcut for the
 * same effect -- Android relaunches that persistent process automatically,
 * the same way it would if SystemUI crashed on its own. Expect one brief
 * status-bar/quick-settings flicker, once, ever.
 */
object RootFreeformBootstrap {

    private const val PREFS = "floatwm_root_bootstrap"
    private const val KEY_APPLIED = "freeform_flags_applied_v1"

    /**
     * @return true only the one time this call is what actually flipped the
     * flags (so the caller can show a one-time notice) -- false both when
     * root isn't available AND when the flags were already applied by an
     * earlier launch. If you need "is freeform enabled right now" instead,
     * that's [FreeformCapability.isFreeformSupported], not this function.
     */
    suspend fun ensureFreeformEnabled(context: Context): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_APPLIED, false)) return@withContext false
        if (!RootShell.isRootAvailable()) return@withContext false

        val applied = RootShell.runAsRoot(
            "settings put global enable_freeform_support 1",
            "settings put global force_resizable_activities 1"
        )
        if (applied) {
            prefs.edit().putBoolean(KEY_APPLIED, true).apply()
            RootShell.runAsRoot("killall com.android.systemui")
        }
        applied
    }
}
