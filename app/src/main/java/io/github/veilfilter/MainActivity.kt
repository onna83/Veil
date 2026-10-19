package io.github.veilfilter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** The single screen: start/stop, permissions, two toggles. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var startStop: Button
    private lateinit var overlayBtn: Button
    private lateinit var notifBtn: Button
    private val listener: () -> Unit = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.load(this)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        startStop = findViewById(R.id.start_stop)
        overlayBtn = findViewById(R.id.overlay_permission)
        notifBtn = findViewById(R.id.notif_permission)
        val guard = findViewById<Switch>(R.id.scene_guard)
        val hud = findViewById<Switch>(R.id.debug_hud)
        guard.isChecked = AppSettings.sceneGuard
        hud.isChecked = AppSettings.debugHud
        guard.setOnCheckedChangeListener { _, v -> AppSettings.setSceneGuard(this, v) }
        hud.setOnCheckedChangeListener { _, v -> AppSettings.setDebugHud(this, v) }

        overlayBtn.setOnClickListener { openOverlaySettings() }
        notifBtn.setOnClickListener { requestNotifications() }
        startStop.setOnClickListener {
            if (ServiceState.running) CaptureService.stop(this) else startProtection()
        }
    }

    override fun onStart() {
        super.onStart()
        ServiceState.addListener(listener)
        refresh()
    }

    override fun onStop() {
        ServiceState.removeListener(listener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun hasNotifPermission() =
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        overlayBtn.visibility = if (overlayOk) View.GONE else View.VISIBLE
        notifBtn.visibility = if (hasNotifPermission()) View.GONE else View.VISIBLE
        val running = ServiceState.running
        startStop.setText(if (running) R.string.stop_protection else R.string.start_protection)
        val err = ServiceState.lastError
        status.text = when {
            running -> getString(R.string.status_running)
            err != null -> getString(R.string.status_error, err)
            !overlayOk -> getString(R.string.status_need_overlay)
            else -> getString(R.string.status_stopped)
        }
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
        )
    }

    private fun requestNotifications() {
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    private fun startProtection() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.status_need_overlay, Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        if (!hasNotifPermission()) {
            // Not strictly required for the service, but the Stop action lives
            // in the notification. Ask once, then continue either way.
            pendingStart = true
            requestNotifications()
            return
        }
        askConsent()
    }

    private var pendingStart = false

    private fun askConsent() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Fresh intent every session (Android 14 forbids reusing consent).
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        if (requestCode == REQ_NOTIF && pendingStart) {
            pendingStart = false
            askConsent()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, resultCode, data)
            // Get out of the way so the user lands on the app they want filtered.
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, R.string.consent_denied, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_NOTIF = 2
    }
}
