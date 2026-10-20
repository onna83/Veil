package io.github.veilfilter

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Secondary screen: top app bar with back + a scrolling column of sections. */
abstract class Screen : AppCompatActivity() {
    protected lateinit var content: LinearLayout
    protected lateinit var toolbar: MaterialToolbar
    abstract val titleRes: Int
    abstract fun build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.load(this)
        setContentView(R.layout.activity_screen)
        toolbar = findViewById(R.id.toolbar)
        toolbar.setTitle(titleRes)
        toolbar.setNavigationOnClickListener { finish() }
        content = findViewById(R.id.content)
        Ui.edgeToEdge(this, findViewById(R.id.root), content)
        build()
    }
}

// ============================================================================ Detection model

class ModelsActivity : Screen() {
    override val titleRes = R.string.nav_models
    private class Row(val title: TextView, val badge: TextView, val detail: TextView, val state: TextView,
                      val primary: MaterialButton, val more: View, val bar: LinearProgressIndicator, val root: View)
    private val rows = LinkedHashMap<ModelSize, Row>()
    private lateinit var footerStatus: TextView
    private val modelListener: () -> Unit = { runOnUiThread { refreshModels() } }
    private val importer = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importModels(uris)
    }

    override fun build() {
        toolbar.menu.add(0, 1, 0, R.string.import_model)
        toolbar.menu.add(0, 2, 1, R.string.host_url)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> try { importer.launch(arrayOf("*/*")) } catch (_: Exception) { footerStatus.setText(R.string.import_no_picker) }
                2 -> editHost()
            }
            true
        }
        val tflite = ModelSize.entries.filter { it.runtime == ModelRuntime.TFLITE }
        val onnx = ModelSize.entries.filter { it.runtime == ModelRuntime.ONNX }
        Ui.header(content, R.string.models_tflite)
        val c1 = Ui.card(content)
        for (m in tflite) addRow(c1, m)
        Ui.header(content, R.string.models_onnx)
        val c2 = Ui.card(content)
        for (m in onnx) addRow(c2, m)
        Ui.note(content, getString(R.string.models_footer))
        footerStatus = Ui.note(content, "")
        footerStatus.visibility = View.GONE
        refreshModels()
    }

    private fun addRow(parent: LinearLayout, m: ModelSize) {
        val v = layoutInflater.inflate(R.layout.row_model, parent, false)
        val r = Row(v.findViewById(R.id.title), v.findViewById(R.id.badge), v.findViewById(R.id.detail), v.findViewById(R.id.state),
            v.findViewById(R.id.primary), v.findViewById(R.id.more), v.findViewById(R.id.bar), v)
        r.primary.setOnClickListener {
            when (val st = ModelManager.status(this, m)) {
                is ModelManager.Status.Downloading -> ModelManager.cancel(m)
                is ModelManager.Status.Ready, is ModelManager.Status.Bundled -> useModel(m)
                else -> if (st !is ModelManager.Status.NoHost) ModelManager.download(this, m)
            }
        }
        r.more.setOnClickListener { anchor ->
            val pm = PopupMenu(this, anchor)
            pm.menu.add(R.string.model_delete)
            pm.setOnMenuItemClickListener {
                val shared = ModelSize.entries.filter { it != m && it.file == m.file }
                if (shared.isNotEmpty()) Toast.makeText(this, getString(R.string.model_shared, m.file, shared.joinToString { it.id }), Toast.LENGTH_LONG).show()
                ModelManager.delete(this, m)
                true
            }
            pm.show()
        }
        parent.addView(v)
        rows[m] = r
    }

    override fun onStart() { super.onStart(); ModelManager.addListener(modelListener); refreshModels() }
    override fun onStop() { ModelManager.removeListener(modelListener); super.onStop() }

    private fun useModel(m: ModelSize) {
        if (!m.available(this)) return
        AppSettings.setModel(this, m)
        CaptureService.switchModel(this) // live swap if protection is running
        Toast.makeText(this, getString(R.string.model_switched, m.label), Toast.LENGTH_SHORT).show()
        refreshModels()
    }

    private fun mb(b: Long) = ((b + 500_000) / 1_000_000).toInt()

    private fun refreshModels() {
        if (rows.isEmpty()) return
        val current = AppSettings.effectiveModel(this)
        for ((m, r) in rows) {
            r.title.text = m.label.substringBefore(" · ") // the section says TFLite / ONNX
            val size = if (m.external) "%.1f MB".format(java.util.Locale.US, m.bytes / 1e6) else getString(R.string.model_status_bundled)
            r.detail.text = getString(R.string.model_detail, size, m.note)
            if (m.runtime == ModelRuntime.ONNX && !OrtDetector.SUPPORTED) {
                r.badge.visibility = View.GONE; r.primary.visibility = View.GONE; r.more.visibility = View.GONE
                r.bar.visibility = View.GONE
                r.state.setText(R.string.model_needs_classic)
                r.root.alpha = 0.6f
                continue
            }
            val st = ModelManager.status(this, m)
            r.badge.visibility = if (m == current) View.VISIBLE else View.GONE
            val stText = when (st) {
                is ModelManager.Status.Bundled -> ""
                is ModelManager.Status.Ready -> getString(R.string.model_status_ready)
                is ModelManager.Status.NotDownloaded -> getString(R.string.model_status_none)
                is ModelManager.Status.NoHost -> getString(R.string.model_status_nohost)
                is ModelManager.Status.Partial -> getString(R.string.model_status_partial, mb(st.bytes))
                is ModelManager.Status.Downloading -> getString(R.string.model_status_dl, mb(st.done), mb(st.total))
            }
            val err = ModelManager.error(m)?.let { "⚠ $it" }
            r.state.text = listOfNotNull(stText.ifEmpty { null }, err).joinToString("\n")
            r.state.visibility = if (r.state.text.isEmpty()) View.GONE else View.VISIBLE
            val downloading = st is ModelManager.Status.Downloading
            r.bar.visibility = if (downloading) View.VISIBLE else View.GONE
            if (st is ModelManager.Status.Downloading && st.total > 0) r.bar.progress = (1000 * st.done / st.total).toInt()
            // exactly one primary action per row
            val label = when {
                downloading -> R.string.model_cancel
                st is ModelManager.Status.Partial -> R.string.model_resume
                st is ModelManager.Status.NotDownloaded || st is ModelManager.Status.NoHost -> R.string.model_download
                m != current -> R.string.model_use
                else -> 0
            }
            r.primary.visibility = if (label == 0) View.GONE else View.VISIBLE
            if (label != 0) r.primary.setText(label)
            r.primary.isEnabled = st !is ModelManager.Status.NoHost
            r.more.visibility = if (m.external && (st is ModelManager.Status.Ready || st is ModelManager.Status.Partial)) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun editHost() {
        val til = TextInputLayout(this)
        val et = TextInputEditText(til.context)
        et.setText(AppSettings.modelBaseUrl)
        et.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
        til.hint = getString(R.string.base_url_hint)
        til.addView(et)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(til) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.host_url_title)
            .setMessage(R.string.base_url_label)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.reset_default) { _, _ -> AppSettings.setModelBaseUrl(this, ""); refreshModels() }
            .setPositiveButton(R.string.save) { _, _ ->
                val v = et.text?.toString()?.trim().orEmpty()
                if (v.isNotEmpty() && !v.startsWith("https://")) { Toast.makeText(this, R.string.base_url_bad, Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                AppSettings.setModelBaseUrl(this, v)
                Toast.makeText(this, R.string.base_url_saved, Toast.LENGTH_SHORT).show()
                refreshModels()
            }
            .show()
    }

    private fun importModels(uris: List<Uri>) {
        val sources = uris.map { u -> ModelImporter.Source(displayName(u)) { contentResolver.openInputStream(u) ?: error("cannot open $u") } }
        footerStatus.visibility = View.VISIBLE
        footerStatus.setText(R.string.importing)
        val dir = java.io.File(filesDir, "models")
        Thread({
            var last = 0L
            val (m, err) = ModelImporter.importAny(sources, dir) { n ->
                if (n - last > 4_000_000) { last = n; runOnUiThread { footerStatus.text = getString(R.string.importing_mb, n / 1_000_000) } }
            }
            runOnUiThread {
                if (m != null) { footerStatus.text = getString(R.string.import_ok, m.file); ModelManager.changed() }
                else footerStatus.text = err
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
}

// ============================================================================ Performance

class PerformanceActivity : Screen() {
    override val titleRes = R.string.nav_perf
    private lateinit var benchOut: TextView
    private lateinit var benchSummary: TextView
    @Volatile private var benchRunning = false

    override fun build() {
        Ui.header(content, R.string.sec_detector)
        val det = Ui.card(content)
        lateinit var backendSummary: TextView
        fun backendText() = getString(R.string.backend_summary,
            Backends.options.firstOrNull { it.id == AppSettings.backend }?.label ?: AppSettings.backend)
        backendSummary = Ui.infoRow(det, R.string.backend, backendText()) {
            val opts = Backends.options
            val sel = opts.indexOfFirst { it.id == AppSettings.backend }.coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.backend_dialog, Backends.RUNTIME))
                .setSingleChoiceItems(opts.map { it.label }.toTypedArray(), sel) { dlg, which ->
                    AppSettings.setBackend(this, opts[which].id)
                    backendSummary.text = backendText()
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        if (OrtDetector.SUPPORTED) Ui.switchRow(det, R.string.ort_nnapi, R.string.ort_nnapi_summary, AppSettings.ortNnapi(this)) {
            AppSettings.setOrtNnapi(this, it)
        }
        benchSummary = Ui.infoRow(det, R.string.benchmark, getString(R.string.benchmark_summary)) { runBenchmark() }
        benchOut = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, 0, p, p / 2)
            visibility = View.GONE
        }
        det.addView(benchOut)

        Ui.header(content, R.string.sec_speed)
        val sp = Ui.card(content)
        Ui.switchRow(sp, R.string.grid_boost, R.string.grid_boost_summary, AppSettings.gridBoost) { AppSettings.setGridBoost(this, it) }
        Ui.switchRow(sp, R.string.fresh_wait, R.string.fresh_wait_summary, AppSettings.freshWait) { AppSettings.setFreshWait(this, it) }
        Ui.sliderRow(sp, R.string.lead, R.string.lead_summary, 0, AppSettings.MAX_LEAD, AppSettings.extraLeadMs,
            { getString(R.string.lead_value, it) }) { AppSettings.setExtraLead(this, it) }

        Ui.header(content, R.string.sec_guards)
        val g = Ui.card(content)
        Ui.switchRow(g, R.string.scene_guard, R.string.scene_guard_summary, AppSettings.sceneGuard) { AppSettings.setSceneGuard(this, it) }
        Ui.switchRow(g, R.string.appearance_guard, R.string.appearance_guard_summary, AppSettings.appearanceGuard) { AppSettings.setAppearanceGuard(this, it) }
        Ui.switchRow(g, R.string.scroll_guard, R.string.scroll_guard_summary, AppSettings.scrollGuard) { AppSettings.setScrollGuard(this, it) }
    }

    /**
     * Median inference time per backend, each created, run and closed on one
     * background thread (GPU backends need that). Not while protection runs.
     */
    private fun runBenchmark() {
        benchOut.visibility = View.VISIBLE
        if (ServiceState.running) { benchOut.setText(R.string.benchmark_busy); return }
        if (benchRunning) return
        benchRunning = true
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
                val line = try {
                    val t0 = System.nanoTime()
                    val d = if (model.runtime == ModelRuntime.ONNX) OrtDetector.create(app, model, oid == "nnapi")
                        else Backends.createExact(app, oid, model)
                    val initMs = (System.nanoTime() - t0) / 1e6
                    try {
                        val times = DetectorFactory.time(d, 5, 30)
                        if (times[15] < bestMs) { bestMs = times[15]; bestId = oid }
                        String.format(java.util.Locale.US, "%-7s median %5.1f ms  p90 %5.1f  init %4.0f ms  [%s]",
                            oid, times[15], times[26], initMs, d.backend)
                    } finally { d.close() }
                } catch (t: Throwable) {
                    "$oid: failed (${t.javaClass.simpleName}: ${t.message?.take(80)})"
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
            runOnUiThread { benchRunning = false; benchOut.text = text.trimEnd() }
        }, "veil-bench").start()
    }
}

// ============================================================================ Debug

class DebugActivity : Screen() {
    override val titleRes = R.string.nav_debug
    override fun build() {
        Ui.header(content, R.string.sec_diagnostics)
        val c = Ui.card(content)
        Ui.switchRow(c, R.string.debug_hud, R.string.debug_hud_summary, AppSettings.debugHud) { AppSettings.setDebugHud(this, it) }
        Ui.switchRow(c, R.string.csv_log, R.string.csv_log_summary, AppSettings.csvLog) { AppSettings.setCsvLog(this, it) }
        Ui.note(content, getString(R.string.debug_note))
    }
}

// ============================================================================ About

class AboutActivity : Screen() {
    override val titleRes = R.string.nav_about
    override fun build() {
        val pi = packageManager.getPackageInfo(packageName, 0)
        Ui.header(content, R.string.app_name)
        val a = Ui.card(content)
        Ui.infoRow(a, R.string.about_version, "${pi.versionName} (${pi.longVersionCode})")
        Ui.infoRow(a, R.string.about_runtime, Backends.RUNTIME)
        Ui.infoRow(a, R.string.about_license, getString(R.string.about_license_text))
        Ui.infoRow(a, R.string.about_model, getString(R.string.about_model_text))
        Ui.header(content, R.string.about_privacy_header)
        val p = Ui.card(content)
        Ui.infoRow(p, R.string.about_privacy, getString(R.string.about_privacy_text))
        Ui.infoRow(p, R.string.about_network, getString(R.string.about_network_text))
        Ui.header(content, R.string.about_good_to_know)
        val g = Ui.card(content)
        Ui.infoRow(g, R.string.about_capture, getString(R.string.about_capture_text))
        Ui.infoRow(g, R.string.about_limits, getString(R.string.about_limits_text))
    }
}
