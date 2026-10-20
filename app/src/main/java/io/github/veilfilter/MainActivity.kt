package io.github.veilfilter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider

/**
 * v1.7 main screen: start/stop with status, what to censor, pixel size with a
 * live preview, and a short list to the secondary screens. Every control
 * writes the same AppSettings keys as before and applies live.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var statusHint: TextView
    private lateinit var statusDot: View
    private lateinit var startStop: MaterialButton
    private lateinit var modelSummary: TextView
    private val listener: () -> Unit = { runOnUiThread { refresh() } }
    private var pendingStart = false

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
        if (pendingStart) { pendingStart = false; askConsent() }
    }
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, res.resultCode, data)
            // get out of the way so the user lands on the app they want filtered
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, R.string.consent_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.load(this)
        setContentView(R.layout.activity_main)
        val content = findViewById<View>(R.id.content)
        Ui.edgeToEdge(this, content, content)
        status = findViewById(R.id.status)
        statusHint = findViewById(R.id.status_hint)
        statusDot = findViewById(R.id.status_dot)
        startStop = findViewById(R.id.start_stop)
        startStop.setOnClickListener { if (ServiceState.running) CaptureService.stop(this) else startProtection() }

        Ui.switchRow(findViewById(R.id.protection_rows), R.string.floating_title, R.string.floating_summary, AppSettings.floatingControl) {
            AppSettings.setFloatingControl(this, it)
        }

        buildCensorRows(findViewById(R.id.censor_rows))

        val pixValue = findViewById<TextView>(R.id.pixel_value)
        val preview = findViewById<PixelPreviewView>(R.id.pixel_preview)
        val pix = findViewById<Slider>(R.id.pixel_size)
        pix.valueFrom = AppSettings.PIXEL_MIN_DP.toFloat(); pix.valueTo = AppSettings.PIXEL_MAX_DP.toFloat()
        pix.value = AppSettings.pixelDp.toFloat()
        pixValue.text = getString(R.string.pixel_value, AppSettings.pixelDp)
        preview.blockDp = AppSettings.pixelDp
        pix.addOnChangeListener { _, v, fromUser ->
            val dp = v.toInt()
            pixValue.text = getString(R.string.pixel_value, dp)
            preview.blockDp = dp
            if (fromUser) AppSettings.setPixelDp(this, dp)
        }

        val nav = findViewById<LinearLayout>(R.id.nav_rows)
        modelSummary = Ui.navRow(nav, R.drawable.ic_memory, R.string.nav_models, "") { open(ModelsActivity::class.java) }
        Ui.navRow(nav, R.drawable.ic_tune, R.string.nav_perf, getString(R.string.nav_perf_summary)) { open(PerformanceActivity::class.java) }
        Ui.navRow(nav, R.drawable.ic_bug, R.string.nav_debug, getString(R.string.nav_debug_summary)) { open(DebugActivity::class.java) }
        Ui.navRow(nav, R.drawable.ic_info, R.string.nav_about, getString(R.string.nav_about_summary)) { open(AboutActivity::class.java) }
    }

    private fun buildCensorRows(parent: LinearLayout) {
        val c = AppSettings.censor
        fun row(t: Int, s: Int?, key: String, v: Boolean, indent: Boolean = false, after: (Boolean) -> Unit = {}) =
            Ui.switchRow(parent, t, s, v, indent) { on -> AppSettings.setCensorFlag(this, key, on); after(on) }
        row(R.string.censor_exposed, R.string.censor_exposed_summary, AppSettings.KEY_C_EXPOSED, c.exposed)
        row(R.string.censor_covered, R.string.censor_covered_summary, AppSettings.KEY_C_COVERED, c.covered)
        row(R.string.censor_belly, R.string.censor_belly_summary, AppSettings.KEY_C_BELLY, c.belly)
        row(R.string.censor_male_chest, R.string.censor_male_chest_summary, AppSettings.KEY_C_MALE_CHEST, c.maleChest)
        val subs = ArrayList<View>()
        row(R.string.censor_faces, R.string.censor_faces_summary, AppSettings.KEY_C_FACES, c.faces) { on ->
            subs.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
        }
        subs += row(R.string.censor_face_female, null, AppSettings.KEY_C_FACE_F, c.femaleFaces, indent = true).view
        subs += row(R.string.censor_face_male, null, AppSettings.KEY_C_FACE_M, c.maleFaces, indent = true).view
        subs.forEach { it.visibility = if (c.faces) View.VISIBLE else View.GONE }
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    override fun onStart() {
        super.onStart()
        ServiceState.addListener(listener)
        AppSettings.addListener(listener) // e.g. pause / faces changed from the floating control
        refresh()
    }

    override fun onStop() {
        ServiceState.removeListener(listener)
        AppSettings.removeListener(listener)
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
        val running = ServiceState.running
        val err = ServiceState.lastError
        status.text = getString(when {
            running && ServiceState.paused -> R.string.status_paused
            running -> R.string.status_running
            else -> R.string.status_stopped
        })
        statusHint.text = when {
            running -> getString(R.string.hint_running)
            err != null -> getString(R.string.status_error, err)
            !overlayOk -> getString(R.string.status_need_overlay)
            else -> getString(R.string.hint_single_app)
        }
        val dot = when {
            running && ServiceState.paused -> 0xFFF2A93B.toInt()
            running -> getColor(R.color.status_on)
            else -> MaterialColors.getColor(statusDot, com.google.android.material.R.attr.colorOutline)
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(dot)
        startStop.setText(if (running) R.string.stop_protection else R.string.start_protection)
        startStop.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_shield)
        val bg = MaterialColors.getColor(startStop, if (running) com.google.android.material.R.attr.colorErrorContainer else androidx.appcompat.R.attr.colorPrimary)
        val fg = MaterialColors.getColor(startStop, if (running) com.google.android.material.R.attr.colorOnErrorContainer else com.google.android.material.R.attr.colorOnPrimary)
        startStop.backgroundTintList = ColorStateList.valueOf(bg)
        startStop.setTextColor(fg); startStop.iconTint = ColorStateList.valueOf(fg)
        val m = AppSettings.effectiveModel(this)
        modelSummary.text = m.label
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun startProtection() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.status_need_overlay, Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        if (!hasNotifPermission()) {
            // not strictly required, but the Stop action lives in the notification: ask once, then go on
            pendingStart = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        askConsent()
    }

    private fun askConsent() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // fresh intent every session (Android 14 forbids reusing consent)
        capture.launch(mpm.createScreenCaptureIntent())
    }
}
