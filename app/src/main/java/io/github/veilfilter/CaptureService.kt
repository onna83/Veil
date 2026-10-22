package io.github.veilfilter

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Foreground service (type mediaProjection) that owns the projection, the
 * single VirtualDisplay, the ImageReader, the inference thread and the
 * overlay renderer.
 */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    @Volatile private var pipeline: Pipeline? = null
    private var activeModel = ModelSize.DEFAULT
    private var probeRef: CaptureProbe? = null
    private var socInfo = ""
    private var vsyncMs = 16.6f
    private var renderer: OverlayRenderer? = null
    private val main = Handler(Looper.getMainLooper())
    private var floating: FloatingControl? = null
    /** AppSettings changed (UI thread): bubble on/off, HUD on/off, menu labels */
    private val settingsListener: () -> Unit = { main.post { syncUi(); syncA11y() } }
    /** v1.8: accessibility service connected / disconnected */
    private val a11yListener: () -> Unit = { main.post { syncA11y() } }
    /** captured content on screen (px), snapshot for the capture thread */
    @Volatile private var contentSnap = Rect()
    private var windowCtx: Context? = null

    /** Size of the captured content reported by onCapturedContentResize (px), or 0. */
    private var contentW = 0
    private var contentH = 0
    private var capW = 0
    /** on-screen width of the captured content (px), for dp -> capture px */
    private var screenW = 1
    private var capH = 0
    @Volatile private var stopping = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "projection stopped (user / system / screen lock)")
            main.post { shutdown() }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            Log.i(TAG, "captured content resize ${width}x$height")
            main.post {
                contentW = width
                contentH = height
                reconfigure()
            }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            Log.i(TAG, "captured content visible=$isVisible")
            // Single-app capture: when the captured app is not visible our
            // overlays would sit on top of some other app. Pause and clear.
            pipeline?.paused = !isVisible
            worker?.post { pipeline?.reset() }
            if (!isVisible) renderer?.clear()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_SWITCH_MODEL -> { if (projection != null) switchModel(); return START_NOT_STICKY }
            ACTION_START -> {
                if (projection != null) return START_NOT_STICKY
                val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
                // Must be in the foreground BEFORE getMediaProjection().
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                try {
                    start(code, data)
                } catch (t: Throwable) {
                    Log.e(TAG, "start failed", t)
                    ServiceState.lastError = t.message ?: t.javaClass.simpleName
                    shutdown()
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(code: Int, data: Intent) {
        stopping = false
        contentW = 0
        contentH = 0
        AppSettings.load(this)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data) ?: error("no projection")
        projection = mp

        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val wctx = createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        windowCtx = wctx
        val probe = CaptureProbe()
        val r = OverlayRenderer(wctx, probe)
        renderer = r
        r.prewarm(PREWARM_WINDOWS) // window + surface creation is slow; do it before the first detection
        val vsync = 1000f / max(30f, display.refreshRate)
        r.vsyncMs = vsync

        val t = HandlerThread("veil-capture", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        thread = t
        val h = Handler(t.looper)
        worker = h
        val model = AppSettings.effectiveModel(this)
        activeModel = model
        probeRef = probe
        socInfo = "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim()
        vsyncMs = vsync
        val p = buildPipeline(model, r, h)
        pipeline = p
        if (p.csvLog) {
            Log.i(Pipeline.CSV, Pipeline.CSV_HEADER_F)
            Log.i(Pipeline.CSV, Pipeline.CSV_HEADER_D)
            Log.i(Pipeline.CSV, "I,soc=$socInfo,model=${model.id},backend=${AppSettings.backend},runtime=${Backends.RUNTIME},refresh=${display.refreshRate}")
        }
        p.start()

        // Callback must be registered before createVirtualDisplay (Android 14).
        mp.registerCallback(projectionCallback, main)

        computeSizes()
        val ir = newReader(capW, capH)
        reader = ir
        virtualDisplay = mp.createVirtualDisplay(
            "veil-capture", capW, capH, captureDpi(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            ir.surface, null, null,
        )

        r.showProbe()
        ServiceState.lastError = null
        ServiceState.paused = false
        ServiceState.set(true)
        AppSettings.addListener(settingsListener)
        VeilAccessibilityService.addListener(a11yListener)
        syncUi()
        syncA11y()
        if (AppSettings.debugHud) r.showHud("Veil: starting… (${Backends.RUNTIME})")
    }

    private fun applyLive(p: Pipeline) {
        p.sceneGuardEnabled = AppSettings.sceneGuard
        p.csvLog = AppSettings.csvLog
        p.appearanceGuardEnabled = AppSettings.appearanceGuard
        p.scrollGuardEnabled = AppSettings.scrollGuard
        p.gridBoostEnabled = AppSettings.gridBoost && activeModel.tiles
        p.censor = AppSettings.censor
        p.blockPx = AppSettings.blockCapturePx(AppSettings.pixelDp, resources.displayMetrics.density, capW, screenW)
        p.extraLeadMs = AppSettings.extraLeadMs.toFloat()
        p.freshWaitEnabled = AppSettings.freshWait
        p.tuning = AppSettings.smoothing.tuning
    }

    // ---- v1.7 floating control (main thread) ----

    private fun syncUi() {
        if (stopping || projection == null) return
        val want = AppSettings.floatingControl
        if (want && floating == null) {
            val ctx = windowCtx ?: return
            floating = FloatingControl(ctx, renderer, object : FloatingControl.Actions {
                override fun togglePause() = setPaused(!ServiceState.paused)
                override fun switchModel(m: ModelSize) {
                    AppSettings.setModel(this@CaptureService, m)
                    this@CaptureService.switchModel()
                }
                override fun stop() = shutdown()
                override fun openApp() {
                    startActivity(Intent(this@CaptureService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            }).also { it.show() }
        } else if (!want && floating != null) {
            floating?.hide(); floating = null
            if (ServiceState.paused) setPaused(false) // never leave censoring paused without its indicator
        }
        floating?.refresh()
        // HUD off applies at once; HUD on appears with the next stats line (every 0.5 s)
        if (!AppSettings.debugHud) renderer?.showHud(null)
    }

    // ---- v1.8 accessibility mode (main thread) ----

    /** Mode on + service connected: one accessibility overlay, hints, clean-frame checks. Else per-box windows. */
    private fun syncA11y() {
        if (stopping || projection == null) return
        val svc = VeilAccessibilityService.instance
        val on = AppSettings.a11yMode && svc != null
        A11yHints.state.active = on
        val r = renderer ?: return
        if (on && !r.sheetActive) {
            if (!r.attachSheet(svc!!)) Log.w(TAG, "accessibility overlay unavailable, keeping per-box windows")
        } else if (!on && r.sheetActive) r.detachSheet()
        applyA11y(pipeline)
    }

    private fun applyA11y(p: Pipeline?) {
        p ?: return
        val on = AppSettings.a11yMode && VeilAccessibilityService.instance != null
        p.a11yMode = on
        p.cleanShotRequester = if (on) { w, h -> requestClean(p, w, h) } else null
    }

    /** capture thread: overlay-free screenshot of the foreground app window -> p.onCleanFrame (capture thread) */
    private fun requestClean(p: Pipeline, w: Int, h: Int) {
        val svc = VeilAccessibilityService.instance
        val wk = worker
        if (svc == null || wk == null) { wk?.post { p.onCleanFrame(null) }; return }
        svc.requestCleanShot(contentSnap, w, h) { shot -> wk.post { p.onCleanFrame(shot) } }
    }

    /** Pause / resume censoring. The pipeline keeps running; only what is shown changes, instantly. */
    private fun setPaused(v: Boolean) {
        if (ServiceState.paused == v) return
        ServiceState.paused = v
        // one hop to the capture thread so it lands after any frame being processed right now
        worker?.post { pipeline?.let { if (v) it.blankNow() else it.republishNow() } }
        floating?.refresh()
        ServiceState.changed()
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) } catch (_: Throwable) {}
    }

    private fun buildPipeline(model: ModelSize, r: OverlayRenderer, h: Handler): Pipeline {
        val backendId = AppSettings.backend
        val p = Pipeline(
            detectorFactory = { status -> DetectorFactory.create(applicationContext, backendId, model, status) },
            model = model,
            renderer = r,
            probe = probeRef!!,
            capture = h,
            onHud = { text -> main.post { showHud(text) } },
            onFatal = { msg ->
                ServiceState.lastError = msg
                main.post { shutdown() }
            },
            socInfo = socInfo,
        )
        p.vsyncMs = vsyncMs
        applyLive(p)
        applyA11y(p)
        return p
    }

    /**
     * Live model switch ("Use" in the model manager while running): build a
     * pipeline for the new model, swap it in on the capture thread (the old
     * one's inference thread stops and closes its detector), cover the frame
     * until the new model's first detection, then resize the capture if the
     * new model wants a different size. No new screen-capture consent needed.
     */
    private fun switchModel() {
        if (stopping || projection == null) return
        val nm = AppSettings.effectiveModel(this)
        if (nm == activeModel) return
        val r = renderer ?: return
        val wk = worker ?: return
        Log.i(TAG, "switching model ${activeModel.id} -> ${nm.id}")
        activeModel = nm
        val np = buildPipeline(nm, r, wk)
        np.coverUntilFirstDetection = true
        wk.post {
            if (stopping) { np.release(); return@post }
            val old = pipeline
            try { old?.release() } catch (_: Throwable) {}
            pipeline = np
            np.start()
        }
        if (AppSettings.debugHud) r.showHud("Veil: loading ${nm.id}…")
        reconfigure()
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        ir.setOnImageAvailableListener({ rd ->
            // always the CURRENT pipeline (it can be swapped by a model switch)
            val p = pipeline
            if (p == null) { rd.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            applyLive(p) // keep settings live
            p.onImageAvailable(rd)
        }, worker)
        return ir
    }

    /** Display (screen) bounds in the current rotation. */
    private fun displayBounds(): Rect =
        (windowCtx ?: this).getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds

    /**
     * Capture size: the captured content's aspect ratio with the short side
     * ~540 px (never upscaled). Also updates the renderer's content rect.
     */
    private fun computeSizes() {
        val db = displayBounds()
        val cw = if (contentW > 0) contentW else db.width()
        val ch = if (contentH > 0) contentH else db.height()
        // Capture size follows the model: the detector only sees `px` across the
        // long side, so ~3x that (960 for 320) loses nothing for detection while
        // cutting deliver/copy cost vs 540x1170 (~-30% pixels). Short side >= 360.
        // grid boost: 4x input (1280 for 320) so 2-across tiles get ~1:1 source pixels
        // 640-input models: capped at 1280 (more pixels would only cost copy/analysis time)
        val long = ModelSize.captureLong(
            activeModel, if (AppSettings.gridBoost) CAPTURE_LONG_PER_INPUT_GRID else CAPTURE_LONG_PER_INPUT,
        )
        var s = min(1f, long / max(cw, ch).toFloat())
        s = min(1f, max(s, MIN_SHORT_SIDE / min(cw, ch).toFloat()))
        capW = max(2, (cw * s / 2f).roundToInt() * 2)
        capH = max(2, (ch * s / 2f).roundToInt() * 2)

        // Where is the captured content on screen? Full-screen capture or a
        // full-screen app: the whole display. Otherwise (split screen,
        // freeform) the platform does not tell us; best guess is centred
        // fit, see README limitations.
        val rect = Rect(db)
        val da = db.width().toFloat() / db.height()
        val ca = cw.toFloat() / ch
        if (abs(da - ca) / da > 0.02f) {
            val scale = min(db.width().toFloat() / cw, db.height().toFloat() / ch)
            val w = (cw * scale).roundToInt()
            val h = (ch * scale).roundToInt()
            rect.set(db.centerX() - w / 2, db.centerY() - h / 2, db.centerX() - w / 2 + w, db.centerY() - h / 2 + h)
        }
        screenW = rect.width()
        contentSnap = Rect(rect)
        renderer?.let {
            it.contentRect.set(rect)
            it.frameW = capW
            it.frameH = capH
            it.relayoutProbe()
        }
    }

    /** Scale the screen density with the capture so layouts are mirrored 1:1. */
    private fun captureDpi(): Int {
        val db = displayBounds()
        val ratio = min(capW, capH).toFloat() / max(1, min(db.width(), db.height()))
        return max(80, (resources.displayMetrics.densityDpi * ratio).roundToInt())
    }

    /** Rotation or content resize: resize() the ONE virtual display. */
    private fun reconfigure() {
        val vd = virtualDisplay ?: return
        val oldW = capW; val oldH = capH
        computeSizes()
        if (oldW == capW && oldH == capH) return
        val w = capW; val h = capH; val dpi = captureDpi()
        val wk = worker ?: return
        wk.post {
            if (stopping) return@post
            val nr = newReader(w, h)
            vd.resize(w, h, dpi)
            vd.surface = nr.surface
            reader?.close()
            reader = nr
            pipeline?.reset()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Full-screen capture gets no content-resize callback on some builds;
        // recompute from the rotated display (keeps content size if known,
        // swapping orientation to match the display).
        if (contentW > 0 && contentH > 0) {
            val db = displayBounds()
            val dispPortrait = db.height() >= db.width()
            val contPortrait = contentH >= contentW
            if (dispPortrait != contPortrait && abs(contentW - db.height()) < 4 && abs(contentH - db.width()) < 4) {
                val t = contentW; contentW = contentH; contentH = t
            }
        }
        reconfigure()
        renderer?.resizeSheet()
        floating?.onDisplayChanged()
    }

    private fun showHud(text: String?) {
        val r = renderer ?: return
        r.showHud(if (AppSettings.debugHud) text else null)
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
        AppSettings.removeListener(settingsListener)
        VeilAccessibilityService.removeListener(a11yListener)
        A11yHints.state.active = false
        floating?.hide(); floating = null
        ServiceState.paused = false
        ServiceState.set(false)
        val mp = projection
        projection = null
        try { mp?.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { mp?.stop() } catch (_: Throwable) {}
        renderer?.release()
        renderer = null
        windowCtx = null
        val wk = worker
        val p = pipeline
        val ir = reader
        reader = null
        worker = null
        pipeline = null
        if (wk != null) {
            // Detector must be closed on the thread that created it.
            wk.post {
                try { ir?.close() } catch (_: Throwable) {}
                // stops the inference thread, which closes the detector on its own thread
                try { p?.release() } catch (_: Throwable) {}
                thread?.quitSafely()
                thread = null
            }
        } else {
            ir?.close()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(if (ServiceState.paused) R.string.notif_paused else R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val TAG = "VeilService"
        const val ACTION_START = "io.github.veilfilter.START"
        const val ACTION_STOP = "io.github.veilfilter.STOP"
        const val ACTION_SWITCH_MODEL = "io.github.veilfilter.SWITCH_MODEL"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "protection"
        private const val NOTIF_ID = 1
        const val CAPTURE_LONG_PER_INPUT = 3f
        const val CAPTURE_LONG_PER_INPUT_GRID = 4f
        const val MIN_SHORT_SIDE = 360f
        const val PREWARM_WINDOWS = 6

        fun start(ctx: Context, code: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, CaptureService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data),
            )
        }

        /** apply AppSettings.model to a running capture without restarting it */
        fun switchModel(ctx: Context) {
            if (ServiceState.running)
                ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_SWITCH_MODEL))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
