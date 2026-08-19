package com.floatwm.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.floatwm.launcher.core.AppLaunchController
import com.floatwm.launcher.core.AppSession
import com.floatwm.launcher.core.SessionState
import com.floatwm.launcher.core.SessionTier
import com.floatwm.launcher.ui.AppGridAdapter
import com.floatwm.launcher.ui.OverlayWindow
import com.floatwm.launcher.util.AppRepository
import com.floatwm.launcher.util.LaunchableApp
import com.floatwm.launcher.util.dpToPx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns every overlay window this app shows and the one foreground-service
 * lifecycle backing all of it. There is exactly one of each "singleton"
 * window (main bubble, trash-drop zone, picker) and a variable number of
 * per-launched-app windows tracked in the two maps below.
 *
 * See AppLaunchController for the Tier A (real freeform) vs Tier B
 * (full-screen + control strip) split; this class is what reacts to each
 * tier's result once launch() returns.
 */
class OverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var windowManager: WindowManager
    private lateinit var themedInflater: LayoutInflater
    private lateinit var launchController: AppLaunchController

    private var mainBubble: OverlayWindow? = null
    private var trashZone: OverlayWindow? = null
    private var picker: OverlayWindow? = null

    /** sessionId -> control strip window (Tier B only; Tier A is system-owned). */
    private val controlStrips = mutableMapOf<String, OverlayWindow>()

    /** sessionId -> minimized bubble window (Tier B only). */
    private val sessionBubbles = mutableMapOf<String, OverlayWindow>()

    /** Every session we know about, expanded or minimized, either tier. */
    private val sessions = mutableMapOf<String, AppSession>()

    private var freeformNoticeShown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        themedInflater = LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_FloatWM))
        launchController = AppLaunchController(this)

        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        showMainBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            closeEverything()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------------
    // Notification / foreground service plumbing
    // ---------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_MIN
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

        val stopIntent = Intent(this, OverlayService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_apps)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(stopPendingIntent)
            .build()
    }

    // ---------------------------------------------------------------------
    // Main bubble (the one entry point; dragging it onto the trash zone is
    // the "closing the main bubble kills all services" gesture -- tapping
    // the persistent notification is the second, always-discoverable route
    // to the same closeEverything() call).
    // ---------------------------------------------------------------------

    private fun showMainBubble() {
        val bubbleView = themedInflater.inflate(R.layout.view_bubble, null, false)
        bubbleView.findViewById<ImageView>(R.id.bubbleIcon)
            .setImageDrawable(packageManager.getApplicationIcon(applicationInfo))

        val size = dpToPx(this, 56)
        val window = OverlayWindow(this, bubbleView, size, size).apply {
            params.x = dpToPx(this@OverlayService, 16)
            params.y = dpToPx(this@OverlayService, 200)
        }
        window.show()
        mainBubble = window

        window.makeDraggable(
            handle = bubbleView,
            onTap = { togglePicker() },
            onDragStart = { showTrashZone() },
            onDrag = { rawX, rawY -> updateTrashZoneHighlight(rawX, rawY) },
            onDragEnd = { rawX, rawY ->
                val overTrash = isOverTrashZone(rawX, rawY)
                hideTrashZone()
                if (overTrash) closeEverything()
            }
        )
    }

    private fun showTrashZone() {
        val existing = trashZone
        if (existing != null) {
            existing.show()
            return
        }
        val view = themedInflater.inflate(R.layout.view_bubble, null, false)
        view.findViewById<ImageView>(R.id.bubbleIcon).setImageResource(R.drawable.ic_close)
        val size = dpToPx(this, 64)
        val screen = windowManager.currentWindowMetrics.bounds
        val window = OverlayWindow(this, view, size, size).apply {
            params.x = (screen.width() - size) / 2
            params.y = screen.height() - dpToPx(this@OverlayService, 140)
        }
        window.show()
        trashZone = window
    }

    private fun trashZoneBounds(): Rect? {
        val win = trashZone ?: return null
        val left = win.params.x
        val top = win.params.y
        return Rect(left, top, left + win.params.width, top + win.params.height)
    }

    private fun isOverTrashZone(rawX: Float, rawY: Float): Boolean {
        val bounds = trashZoneBounds() ?: return false
        return bounds.contains(rawX.toInt(), rawY.toInt())
    }

    private fun updateTrashZoneHighlight(rawX: Float, rawY: Float) {
        val view = trashZone?.rootView ?: return
        val scale = if (isOverTrashZone(rawX, rawY)) 1.3f else 1f
        view.scaleX = scale
        view.scaleY = scale
    }

    private fun hideTrashZone() {
        trashZone?.hide()
    }

    // ---------------------------------------------------------------------
    // Picker window -- fully our own window, so close/minimize/resize/drag
    // here are real, not a stated-limitation fallback.
    // ---------------------------------------------------------------------

    private fun togglePicker() {
        val existing = picker
        when {
            existing != null && existing.isShowing() -> existing.hide()
            existing != null -> existing.show()
            else -> buildPicker()
        }
    }

    private fun buildPicker() {
        val view = themedInflater.inflate(R.layout.window_picker, null, false)
        val screen = windowManager.currentWindowMetrics.bounds
        val width = (screen.width() * 0.8f).toInt()
        val height = (screen.height() * 0.6f).toInt()

        val window = OverlayWindow(this, view, width, height).apply {
            params.x = (screen.width() - width) / 2
            params.y = (screen.height() - height) / 3
        }

        window.makeDraggable(handle = view.findViewById(R.id.titleBar))
        window.makeResizable(
            handle = view.findViewById(R.id.resizeHandle),
            minWidthPx = dpToPx(this, 240),
            minHeightPx = dpToPx(this, 320)
        )

        view.findViewById<View>(R.id.btnMinimize).setOnClickListener { window.hide() }
        view.findViewById<View>(R.id.btnClose).setOnClickListener { window.hide() }

        val grid = view.findViewById<RecyclerView>(R.id.appGrid)
        grid.layoutManager = GridLayoutManager(this, 4)
        val adapter = AppGridAdapter { app -> onAppTapped(app) }
        grid.adapter = adapter

        window.show()
        picker = window

        serviceScope.launch {
            val apps = AppRepository.loadLaunchableApps(this@OverlayService, packageName)
            adapter.submit(apps)
        }
    }

    // ---------------------------------------------------------------------
    // Launching apps
    // ---------------------------------------------------------------------

    private fun onAppTapped(app: LaunchableApp) {
        val session = launchController.launch(app) ?: return
        sessions[session.sessionId] = session
        picker?.hide()

        when (session.tier) {
            SessionTier.FREEFORM -> {
                if (!freeformNoticeShown) {
                    freeformNoticeShown = true
                    Toast.makeText(
                        this,
                        getString(R.string.freeform_notice),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            SessionTier.FULLSCREEN_STRIP -> showControlStrip(session)
        }
    }

    private fun showControlStrip(session: AppSession) {
        val view = themedInflater.inflate(R.layout.window_control_strip, null, false)
        view.findViewById<ImageView>(R.id.stripIcon).setImageDrawable(session.icon)
        view.findViewById<TextView>(R.id.stripLabel).text = session.label

        val window = OverlayWindow(
            this, view,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            params.x = dpToPx(this@OverlayService, 16)
            params.y = dpToPx(this@OverlayService, 60)
        }

        // The whole strip is the drag handle; the two buttons still work
        // normally because they consume their own touch stream first (see
        // OverlayWindow.makeDraggable kdoc).
        window.makeDraggable(handle = view)

        view.findViewById<View>(R.id.stripMinimize).setOnClickListener { minimizeSession(session) }
        view.findViewById<View>(R.id.stripClose).setOnClickListener { closeSession(session) }

        window.show()
        controlStrips[session.sessionId] = window
    }

    private fun minimizeSession(session: AppSession) {
        goHome()
        controlStrips.remove(session.sessionId)?.hide()
        session.state = SessionState.MINIMIZED
        showSessionBubble(session)
    }

    private fun showSessionBubble(session: AppSession) {
        val view = themedInflater.inflate(R.layout.view_bubble, null, false)
        view.findViewById<ImageView>(R.id.bubbleIcon).setImageDrawable(session.icon)

        val size = dpToPx(this, 48)
        val stackIndex = sessionBubbles.size
        val window = OverlayWindow(this, view, size, size).apply {
            params.x = dpToPx(this@OverlayService, 16)
            params.y = dpToPx(this@OverlayService, 270) + stackIndex * (size + dpToPx(this@OverlayService, 12))
        }

        window.makeDraggable(
            handle = view,
            onTap = { restoreSession(session) },
            onDragStart = { showTrashZone() },
            onDrag = { rawX, rawY -> updateTrashZoneHighlight(rawX, rawY) },
            onDragEnd = { rawX, rawY ->
                val overTrash = isOverTrashZone(rawX, rawY)
                hideTrashZone()
                if (overTrash) closeSession(session)
            }
        )
        window.show()
        sessionBubbles[session.sessionId] = window
    }

    private fun restoreSession(session: AppSession) {
        sessionBubbles.remove(session.sessionId)?.hide()
        // Deliberately a *fresh* intent without FLAG_ACTIVITY_MULTIPLE_TASK:
        // re-adding that flag here would spawn a brand new instance instead
        // of reordering the existing task to the front. See the caveat on
        // AppSession for what "restore" can and can't guarantee when more
        // than one instance of the same package exists.
        packageManager.getLaunchIntentForPackage(session.packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
        session.state = SessionState.EXPANDED
        showControlStrip(session)
    }

    private fun closeSession(session: AppSession) {
        goHome()
        controlStrips.remove(session.sessionId)?.hide()
        sessionBubbles.remove(session.sessionId)?.hide()
        sessions.remove(session.sessionId)
    }

    /**
     * Sends the current foreground task to the background -- the same
     * effect as the user pressing Home. This is the only thing an
     * unprivileged app can do to "close" or "minimize" another app's
     * window; it backgrounds the task, it does not stop the target app's
     * process (Android reserves that for the system/root). See README.
     */
    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---------------------------------------------------------------------
    // Teardown
    // ---------------------------------------------------------------------

    private fun closeEverything() {
        goHome()
        controlStrips.values.forEach { it.hide() }
        controlStrips.clear()
        sessionBubbles.values.forEach { it.hide() }
        sessionBubbles.clear()
        sessions.clear()
        picker?.hide()
        trashZone?.hide()
        mainBubble?.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.coroutineContext[Job]?.cancel()
        // Safety net in case teardown is reached via a path other than
        // closeEverything() (e.g. the system killing the service directly);
        // avoids leaking WindowManager tokens.
        mainBubble?.hide()
        trashZone?.hide()
        picker?.hide()
        controlStrips.values.forEach { it.hide() }
        sessionBubbles.values.forEach { it.hide() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "floatwm_overlay"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.floatwm.launcher.ACTION_STOP"
    }
}
