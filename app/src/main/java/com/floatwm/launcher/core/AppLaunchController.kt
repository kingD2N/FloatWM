package com.floatwm.launcher.core

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.WindowManager
import com.floatwm.launcher.util.LaunchableApp
import java.util.UUID

/**
 * Decides HOW to launch a tapped app and returns the resulting [AppSession],
 * or null if it could not be launched at all (e.g. the app was uninstalled
 * between the grid being built and the tap landing).
 *
 * TIER A (FREEFORM): used only when [FreeformCapability.isFreeformSupported]
 * is true for this device, i.e. the user has enabled the on-device freeform
 * developer option (naming/availability vary by build -- see README). The
 * launch carries an ActivityOptions.setLaunchBounds hint; if the system
 * honors it, the target app opens in a real, OS-drawn floating window whose
 * title bar, move, resize, minimize, and close are all system-owned from
 * that point on. OverlayService deliberately does not try to draw its own
 * chrome on top of that window -- see the README for why.
 *
 * TIER B (FULLSCREEN_STRIP): the guaranteed-available fallback used on any
 * device where freeform isn't on. The app launches as a normal full-screen
 * activity, and OverlayService shows a persistent, app-owned control strip
 * as the closest achievable analog of a custom title bar. See the README
 * for the exact, stated limitations of this tier.
 *
 * FLAG_ACTIVITY_MULTIPLE_TASK is applied on every fresh launch from the
 * picker (both tiers) so that re-tapping the same app creates a genuinely
 * separate instance where the target app's own launchMode allows it --
 * this is the "multiple windows where possible" behavior. It is
 * deliberately NOT reused when *restoring* a minimized session; see
 * OverlayService.restoreSession and the caveat on [AppSession].
 */
class AppLaunchController(private val context: Context) {

    fun launch(app: LaunchableApp): AppSession? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

        val tier = if (FreeformCapability.isFreeformSupported(context)) {
            launchFreeform(launchIntent)
            SessionTier.FREEFORM
        } else {
            context.startActivity(launchIntent)
            SessionTier.FULLSCREEN_STRIP
        }

        return AppSession(
            sessionId = UUID.randomUUID().toString(),
            packageName = app.packageName,
            label = app.label,
            icon = app.icon,
            launchIntent = launchIntent,
            tier = tier
        )
    }

    private fun launchFreeform(intent: Intent) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val screen = wm.currentWindowMetrics.bounds
        val w = (screen.width() * 0.6f).toInt()
        val h = (screen.height() * 0.6f).toInt()
        val left = (screen.width() - w) / 2
        val top = (screen.height() - h) / 4
        val bounds = Rect(left, top, left + w, top + h)

        val options = ActivityOptions.makeBasic().apply { setLaunchBounds(bounds) }
        context.startActivity(intent, options.toBundle())
    }
}
