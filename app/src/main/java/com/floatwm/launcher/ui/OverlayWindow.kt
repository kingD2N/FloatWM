package com.floatwm.launcher.ui

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max

/**
 * Thin wrapper around a single WindowManager-hosted overlay view. Centralizes
 * add/update/remove and the drag/resize touch math so the picker window,
 * bubbles, and control strip don't each reimplement it.
 *
 * Every window created here uses TYPE_APPLICATION_OVERLAY (API 26+) and
 * FLAG_NOT_FOCUSABLE, so it never steals keyboard focus or input from
 * whatever app is running underneath -- required for a well-behaved
 * "chat head"-style overlay that doesn't disrupt the app behind it.
 */
class OverlayWindow(
    private val context: Context,
    val rootView: View,
    initialWidth: Int,
    initialHeight: Int
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    val params: WindowManager.LayoutParams = WindowManager.LayoutParams(
        initialWidth,
        initialHeight,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 200
    }

    private var attached = false

    fun show() {
        if (attached) return
        try {
            windowManager.addView(rootView, params)
            attached = true
        } catch (e: SecurityException) {
            // Overlay permission was revoked mid-session; fail quietly rather
            // than crashing the service. See README known-limitations.
            Log.w(TAG, "Cannot show overlay window -- SYSTEM_ALERT_WINDOW missing/revoked", e)
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Bad window token while showing overlay window", e)
        }
    }

    fun hide() {
        if (!attached) return
        try {
            windowManager.removeView(rootView)
        } catch (e: IllegalArgumentException) {
            // View was already detached (e.g. service torn down concurrently).
        } finally {
            attached = false
        }
    }

    fun isShowing(): Boolean = attached

    private fun updateLayout() {
        if (attached) windowManager.updateViewLayout(rootView, params)
    }

    private fun resizeTo(widthPx: Int, heightPx: Int, minWidthPx: Int, minHeightPx: Int) {
        params.width = max(widthPx, minWidthPx)
        params.height = max(heightPx, minHeightPx)
        updateLayout()
    }

    /**
     * Installs drag-to-move behavior driven by touches on [handle] (e.g. a
     * title bar, or the whole bubble view). [onTap] fires for a touch
     * sequence that moved less than the system's touch-slop -- i.e. a tap
     * rather than a drag. [onDragStart]/[onDrag]/[onDragEnd] let a caller
     * show a drop target and react to hover/drop (used for the main bubble's
     * drag-to-dismiss-everything zone).
     *
     * Buttons placed inside [handle] (e.g. the minimize/close ImageButtons in
     * a title bar) keep working normally: a clickable child consumes its own
     * touch stream before it would reach this listener, so tapping a button
     * does not also trigger a drag.
     */
    fun makeDraggable(
        handle: View,
        onTap: (() -> Unit)? = null,
        onDragStart: (() -> Unit)? = null,
        onDrag: ((rawX: Float, rawY: Float) -> Unit)? = null,
        onDragEnd: ((rawX: Float, rawY: Float) -> Unit)? = null
    ) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        onDragStart?.invoke()
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        updateLayout()
                        onDrag?.invoke(event.rawX, event.rawY)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        onDragEnd?.invoke(event.rawX, event.rawY)
                    } else {
                        onTap?.invoke()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Installs bottom-right corner resize behavior on [handle]. Width/height
     * grow or shrink with the drag delta, clamped between
     * [minWidthPx]/[minHeightPx] and the current display's bounds.
     */
    fun makeResizable(handle: View, minWidthPx: Int, minHeightPx: Int) {
        val screenBounds = windowManager.currentWindowMetrics.bounds
        var startWidth = 0
        var startHeight = 0
        var downRawX = 0f
        var downRawY = 0f

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startWidth = params.width
                    startHeight = params.height
                    downRawX = event.rawX
                    downRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    val newW = (startWidth + dx).coerceIn(minWidthPx, screenBounds.width())
                    val newH = (startHeight + dy).coerceIn(minHeightPx, screenBounds.height())
                    resizeTo(newW, newH, minWidthPx, minHeightPx)
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        private const val TAG = "OverlayWindow"
    }
}
