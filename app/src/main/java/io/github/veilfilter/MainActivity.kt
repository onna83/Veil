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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** The single screen: start/stop, permissions, a few toggles, backend benchmark. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var startStop: Button
    private lateinit var overlayBtn: Button
    private lateinit var notifBtn: Button
    private lateinit var benchBtn: Button
    private lateinit var benchOut: TextView
    @Volatile private var benchRunning = false
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
        val appear = findViewById<Switch>(R.id.appearance_guard)
        appear.isChecked = AppSettings.appearanceGuard
        appear.setOnCheckedChangeListener { _, v -> AppSettings.setAppearanceGuard(this, v) }
        // What to censor (applied live)
        val faceF = findViewById<Switch>(R.id.censor_face_female)
        val faceM = findViewById<Switch>(R.id.censor_face_male)
        val c = AppSettings.censor
        fun bind(id: Int, key: String, v: Boolean, after: (Boolean) -> Unit = {}) {
            val sw = findViewById<Switch>(id)
            sw.isChecked = v
            sw.setOnCheckedChangeListener { _, on -> AppSettings.setCensorFlag(this, key, on); after(on) }
        }
        bind(R.id.censor_exposed, AppSettings.KEY_C_EXPOSED, c.exposed)
        bind(R.id.censor_covered, AppSettings.KEY_C_COVERED, c.covered)
        bind(R.id.censor_belly, AppSettings.KEY_C_BELLY, c.belly)
        bind(R.id.censor_male_chest, AppSettings.KEY_C_MALE_CHEST, c.maleChest)
        bind(R.id.censor_face_female, AppSettings.KEY_C_FACE_F, c.femaleFaces)
        bind(R.id.censor_face_male, AppSettings.KEY_C_FACE_M, c.maleFaces)
        faceF.isEnabled = c.faces; faceM.isEnabled = c.faces
        bind(R.id.censor_faces, AppSettings.KEY_C_FACES, c.faces) { on -> faceF.isEnabled = on; faceM.isEnabled = on }

        val grid = findViewById<Switch>(R.id.grid_boost)
        grid.isChecked = AppSettings.gridBoost
        grid.setOnCheckedChangeListener { _, v -> AppSettings.setGridBoost(this, v) }
        val scrollG = findViewById<Switch>(R.id.scroll_guard)
        scrollG.isChecked = AppSettings.scrollGuard
        scrollG.setOnCheckedChangeListener { _, v -> AppSettings.setScrollGuard(this, v) }
        val csv = findViewById<Switch>(R.id.csv_log)
        csv.isChecked = AppSettings.csvLog
        csv.setOnCheckedChangeListener { _, v -> AppSettings.setCsvLog(this, v) }
        setupModelPicker()

        val pixLabel = findViewById<TextView>(R.id.pixel_label)
        val pix = findViewById<SeekBar>(R.id.pixel_size)
        pix.max = AppSettings.PIXEL_MAX_DP - AppSettings.PIXEL_MIN_DP
        pix.progress = AppSettings.pixelDp - AppSettings.PIXEL_MIN_DP
        pixLabel.text = getString(R.string.pixel_label, AppSettings.pixelDp)
        pix.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                val dp = v + AppSettings.PIXEL_MIN_DP
                pixLabel.text = getString(R.string.pixel_label, dp)
                if (fromUser) AppSettings.setPixelDp(this@MainActivity, dp)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        val leadLabel = findViewById<TextView>(R.id.lead_label)
        val lead = findViewById<SeekBar>(R.id.lead)
        lead.max = AppSettings.MAX_LEAD
        lead.progress = AppSettings.extraLeadMs
        leadLabel.text = getString(R.string.lead_label, AppSettings.extraLeadMs)
        lead.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                leadLabel.text = getString(R.string.lead_label, v)
                if (fromUser) AppSettings.setExtraLead(this@MainActivity, v)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        findViewById<TextView>(R.id.backend_label).text = getString(R.string.backend_label, Backends.RUNTIME)
        val spinner = findViewById<Spinner>(R.id.backend)
        val opts = Backends.options
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opts.map { it.label })
        spinner.setSelection(opts.indexOfFirst { it.id == AppSettings.backend }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                AppSettings.setBackend(this@MainActivity, opts[pos].id)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        benchBtn = findViewById(R.id.benchmark)
        benchOut = findViewById(R.id.benchmark_result)
        benchBtn.setOnClickListener { runBenchmark() }

        overlayBtn.setOnClickListener { openOverlaySettings() }
        notifBtn.setOnClickListener { requestNotifications() }
        startStop.setOnClickListener {
            if (ServiceState.running) CaptureService.stop(this) else startProtection()
        }
    }

    override fun onStart() {
        super.onStart()
        ServiceState.addListener(listener)
        ModelManager.addListener(modelListener)
        refresh()
        refreshModels()
    }

    override fun onStop() {
        ServiceState.removeListener(listener)
        ModelManager.removeListener(modelListener)
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
        benchBtn.isEnabled = !running && !benchRunning
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
        if (requestCode == REQ_IMPORT) { if (resultCode == RESULT_OK && data != null) importModel(data); return }
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, resultCode, data)
            // Get out of the way so the user lands on the app they want filtered.
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, R.string.consent_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private lateinit var modelStatus: TextView
    private class Row(val title: TextView, val detail: TextView, val bar: android.widget.ProgressBar,
                      val dl: Button, val del: Button, val use: Button)
    private val rows = LinkedHashMap<ModelSize, Row>()
    private val modelListener: () -> Unit = { runOnUiThread { refreshModels() } }

    private fun setupModelPicker() {
        modelStatus = findViewById(R.id.model_status)
        val list = findViewById<android.widget.LinearLayout>(R.id.model_list)
        val dp = resources.displayMetrics.density
        for (m in ModelSize.entries) {
            val box = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
            }
            val title = TextView(this).apply { textSize = 15f }
            val detail = TextView(this).apply { alpha = 0.75f; textSize = 12f }
            val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
            val btns = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            fun btn(t: Int) = Button(this, null, android.R.attr.buttonStyleSmall).apply { setText(t); minHeight = (48 * dp).toInt() }
            val dl = btn(R.string.model_download); val del = btn(R.string.model_delete); val use = btn(R.string.model_use)
            btns.addView(dl); btns.addView(del); btns.addView(use)
            box.addView(title); box.addView(detail); box.addView(bar); box.addView(btns)
            list.addView(box)
            dl.setOnClickListener {
                if (ModelManager.status(this, m) is ModelManager.Status.Downloading) ModelManager.cancel(m)
                else ModelManager.download(this, m)
            }
            del.setOnClickListener {
                val shared = ModelSize.entries.filter { it != m && it.file == m.file }
                if (shared.isNotEmpty()) Toast.makeText(this, getString(R.string.model_shared, m.file, shared.joinToString { it.id }), Toast.LENGTH_LONG).show()
                ModelManager.delete(this, m)
            }
            use.setOnClickListener { useModel(m) }
            rows[m] = Row(title, detail, bar, dl, del, use)
        }
        findViewById<Button>(R.id.import_model).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            try { startActivityForResult(i, REQ_IMPORT) } catch (_: Exception) { modelStatus.text = getString(R.string.import_no_picker) }
        }
        val url = findViewById<android.widget.EditText>(R.id.model_base_url)
        url.setText(AppSettings.modelBaseUrl)
        findViewById<Button>(R.id.save_base_url).setOnClickListener {
            val v = url.text.toString().trim()
            if (v.isNotEmpty() && !v.startsWith("https://")) { Toast.makeText(this, R.string.base_url_bad, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            AppSettings.setModelBaseUrl(this, v)
            url.setText(AppSettings.modelBaseUrl)
            Toast.makeText(this, R.string.base_url_saved, Toast.LENGTH_SHORT).show()
            refreshModels()
        }
        val nn = findViewById<Switch>(R.id.ort_nnapi)
        nn.isChecked = AppSettings.ortNnapi(this)
        nn.setOnCheckedChangeListener { _, v -> AppSettings.setOrtNnapi(this, v) }
        refreshModels()
    }

    private fun useModel(m: ModelSize) {
        if (!m.available(this)) return
        AppSettings.setModel(this, m)
        CaptureService.switchModel(this) // live swap if protection is running
        Toast.makeText(this, getString(R.string.model_switched, m.label), Toast.LENGTH_SHORT).show()
        refreshModels()
    }

    private fun mb(b: Long) = ((b + 500_000) / 1_000_000).toInt()

    private fun refreshModels() {
        val current = AppSettings.effectiveModel(this)
        for ((m, r) in rows) {
            val st = ModelManager.status(this, m)
            if (m.runtime == ModelRuntime.ONNX && !OrtDetector.SUPPORTED) {
                r.title.text = m.label
                r.detail.setText(R.string.model_needs_classic)
                r.bar.visibility = View.GONE; r.dl.visibility = View.GONE; r.del.visibility = View.GONE; r.use.visibility = View.GONE
                continue
            }
            r.title.text = if (m == current) "${m.label}  ·  ${getString(R.string.model_in_use)}" else m.label
            r.title.setTypeface(null, if (m == current) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            val size = if (m.external) "%.1f MB".format(java.util.Locale.US, m.bytes / 1e6) else "in app"
            val stText = when (st) {
                is ModelManager.Status.Bundled -> getString(R.string.model_status_bundled)
                is ModelManager.Status.Ready -> getString(R.string.model_status_ready)
                is ModelManager.Status.NotDownloaded -> getString(R.string.model_status_none)
                is ModelManager.Status.NoHost -> getString(R.string.model_status_nohost)
                is ModelManager.Status.Partial -> getString(R.string.model_status_partial, mb(st.bytes))
                is ModelManager.Status.Downloading -> getString(R.string.model_status_dl, mb(st.done), mb(st.total))
            }
            val err = ModelManager.error(m)?.let { "\n⚠ $it" } ?: ""
            r.detail.text = "$size · ${m.note}\n$stText$err"
            val downloading = st is ModelManager.Status.Downloading
            r.bar.visibility = if (downloading) View.VISIBLE else View.GONE
            if (st is ModelManager.Status.Downloading && st.total > 0) r.bar.progress = (1000 * st.done / st.total).toInt()
            r.dl.visibility = if (m.external && st !is ModelManager.Status.Ready) View.VISIBLE else View.GONE
            r.dl.setText(if (downloading) R.string.model_cancel else R.string.model_download)
            r.dl.isEnabled = st !is ModelManager.Status.NoHost
            r.del.visibility = if (m.external && (st is ModelManager.Status.Ready || st is ModelManager.Status.Partial)) View.VISIBLE else View.GONE
            r.use.visibility = if (m != current && (st is ModelManager.Status.Ready || st is ModelManager.Status.Bundled)) View.VISIBLE else View.GONE
        }
    }

    private fun importModel(data: Intent) {
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        val sources = uris.map { u -> ModelImporter.Source(displayName(u)) { contentResolver.openInputStream(u) ?: error("cannot open $u") } }
        modelStatus.text = getString(R.string.importing)
        val dir = java.io.File(filesDir, "models")
        Thread({
            var last = 0L
            val (m, err) = ModelImporter.importAny(sources, dir) { n ->
                if (n - last > 4_000_000) { last = n; runOnUiThread { modelStatus.text = getString(R.string.importing_mb, n / 1_000_000) } }
            }
            runOnUiThread {
                if (m != null) { modelStatus.text = getString(R.string.import_ok, m.file); ModelManager.changed() }
                else modelStatus.text = err
            }
        }, "veil-import").start()
    }

    private fun displayName(u: Uri): String {
        val c = contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        var name: String? = null
        if (c != null) {
            try { if (c.moveToFirst()) name = c.getString(0) } finally { c.close() }
        }
        return name ?: u.lastPathSegment ?: "file"
    }

    /**
     * Median inference time per backend, each created, run and closed on one
     * background thread (GPU backends need that). Disabled while protection runs.
     */
    private fun runBenchmark() {
        if (ServiceState.running) { benchOut.setText(R.string.benchmark_busy); return }
        if (benchRunning) return
        benchRunning = true
        benchBtn.isEnabled = false
        benchOut.setText(R.string.benchmark_running)
        val model = AppSettings.effectiveModel(this)
        val app = applicationContext
        Thread({
            val sb = StringBuilder()
            sb.append("${Backends.RUNTIME}, ${model.id}, ${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}\n")
            var bestId = ""
            var bestMs = Double.MAX_VALUE
            val ids = if (model.runtime == ModelRuntime.ONNX) listOf("cpu", "nnapi")
                else Backends.options.map { it.id }.filter { it != "auto" }
            for (oid in ids) {
                val o = object { val id = oid }
                val line = try {
                    val t0 = System.nanoTime()
                    val d = if (model.runtime == ModelRuntime.ONNX) OrtDetector.create(app, model, oid == "nnapi")
                        else Backends.createExact(app, o.id, model)
                    val initMs = (System.nanoTime() - t0) / 1e6
                    try {
                        val times = DetectorFactory.time(d, 5, 30)
                        if (times[15] < bestMs) { bestMs = times[15]; bestId = o.id }
                        String.format(
                            java.util.Locale.US, "%-7s median %5.1f ms  p90 %5.1f  init %4.0f ms  [%s]",
                            o.id, times[15], times[26], initMs, d.backend,
                        )
                    } finally { d.close() }
                } catch (t: Throwable) {
                    "${o.id}: failed (${t.javaClass.simpleName}: ${t.message?.take(80)})"
                }
                sb.append(line).append('\n')
                val partial = sb.toString()
                runOnUiThread { benchOut.text = partial }
            }
            if (bestId.isNotEmpty() && model.runtime == ModelRuntime.TFLITE) {
                DetectorFactory.remember(app, model, bestId, bestMs)
                sb.append("Auto will use: $bestId\n")
            }
            val text = sb.toString()
            android.util.Log.i("VeilBench", text)
            runOnUiThread {
                benchRunning = false
                benchOut.text = text
                refresh()
            }
        }, "veil-bench").start()
    }

    companion object {
        private const val REQ_CAPTURE = 1
        const val REQ_IMPORT = 42
        private const val REQ_NOTIF = 2
    }
}
