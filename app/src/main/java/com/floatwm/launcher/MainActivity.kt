package com.floatwm.launcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Entry point when the user taps the launcher icon.
 *
 * This activity never shows real UI (see Theme.FloatWM.Transparent in
 * themes.xml). Its only job: make sure we hold the two permissions the
 * overlay system needs, start OverlayService, then get out of the way.
 * Once the bubble is up, this activity is gone -- the service and its
 * overlay windows are the entire UI surface from then on, exactly as
 * specified ("no full-screen UI").
 */
class MainActivity : AppCompatActivity() {

    private var overlayPromptShown = false

    private val overlaySettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                proceedOrRequestOverlay()
            } else {
                Toast.makeText(this, R.string.overlay_permission_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Notifications only control whether the required foreground-
            // service notice is visible; proceed either way so tapping
            // "Deny" doesn't strand the user with no bubble at all.
            startOverlayServiceAndFinish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proceedOrRequestOverlay()
    }

    private fun proceedOrRequestOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            if (overlayPromptShown) {
                // Already sent them to Settings once this launch -- don't
                // loop forever if they back out without granting it. They
                // can just tap the app icon again whenever they're ready.
                Toast.makeText(this, R.string.overlay_permission_denied, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            overlayPromptShown = true
            Toast.makeText(this, R.string.overlay_permission_rationale, Toast.LENGTH_LONG).show()
            overlaySettingsLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        startOverlayServiceAndFinish()
    }

    private fun startOverlayServiceAndFinish() {
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        finish()
    }
}
