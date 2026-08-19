package com.floatwm.launcher.core

import android.content.Intent
import android.graphics.drawable.Drawable

enum class SessionTier {
    /** Real, OS-drawn freeform window (system owns the chrome). */
    FREEFORM,

    /** Full-screen launch shadowed by our own persistent control strip. */
    FULLSCREEN_STRIP
}

enum class SessionState { EXPANDED, MINIMIZED }

/**
 * One launched-app "window" the service is tracking.
 *
 * MULTI-INSTANCE / RESTORE CAVEAT -- read before relying on exact-bubble
 * restore: sessionId is ours; it is NOT the underlying Android taskId,
 * because an ordinary app cannot obtain another app's taskId
 * (ActivityManager#getAppTasks only ever returns the *caller's own* tasks).
 * When the user re-taps a minimized bubble, OverlayService re-issues the
 * launch intent for that package rather than targeting a specific task --
 * Android reorders an existing matching task to the front on its own,
 * requiring no special permission. For an app that supports multiple
 * instances (its own launchMode allows it), this reliably restores *a*
 * window for that package -- usually the most recently used one -- but
 * cannot guarantee it is the *exact* instance a given bubble was minimized
 * from. That stronger guarantee needs task-management privilege this build
 * intentionally does not have.
 */
data class AppSession(
    val sessionId: String,
    val packageName: String,
    val label: CharSequence,
    val icon: Drawable,
    val launchIntent: Intent,
    val tier: SessionTier,
    var state: SessionState = SessionState.EXPANDED
)
