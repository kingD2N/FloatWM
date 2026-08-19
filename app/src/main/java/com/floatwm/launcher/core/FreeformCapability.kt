package com.floatwm.launcher.core

import android.content.Context
import android.content.pm.PackageManager

/**
 * Best-effort, public-API-only detection of whether this device currently
 * exposes OS-native freeform/floating windows.
 *
 * PLATFORM NOTE -- read this before assuming Tier A "just works":
 * This checks the public system feature flag
 * `android.software.freeform_window_management`
 * (PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT). That flag is what the
 * "Force activities to be resizable" / "Enable freeform windows" toggles
 * under Settings > System > Developer options flip. Two important caveats,
 * both confirmed against current sources at the time this was written:
 *
 * 1. Google has been inconsistent about even *showing* that toggle on stock
 *    Pixel builds in Android 16 -- on some builds it's simply not in the
 *    Developer options UI, which means Tier A is unreachable on-device (no
 *    ADB allowed, per the constraint this build was written against). This
 *    is a real platform gap, not a bug in this app.
 * 2. Android 17's new system-wide "App Bubbles" windowing mode is a
 *    *different* mechanism (launcher long-press only, no invocation API for
 *    third-party apps as of Aug 2026) and is deliberately NOT what this
 *    class detects -- see the project README for why we can't drive it from
 *    here.
 *
 * When this returns true, [AppLaunchController] requests real freeform
 * bounds for new launches (Tier A). When false -- the common case on an
 * untouched stock device -- it falls back to a full-screen launch shadowed
 * by our own control strip (Tier B). Deliberately no reflection, hidden
 * APIs, or shell commands: per the "stock-only, zero ADB" constraint,
 * detection has to be something an ordinary app is allowed to do.
 */
object FreeformCapability {

    fun isFreeformSupported(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT
        )
    }
}
