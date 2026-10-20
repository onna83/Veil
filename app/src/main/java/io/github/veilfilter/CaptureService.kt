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
    private var pipeline: Pipeline? = null
    private var renderer: OverlayRenderer? = null
    private val main = Handler(Looper.getMainLooper())
    private var windowCtx: Context? = null

    /** Size of the captured content reported by onCapturedContentResize (px), or 0. */
    private var contentW = 0
    private var contentH = 0
    private var capW = 0
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
        val model = AppSettings.modelSize
        val backendId = AppSettings.backend
        val soc = "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim()
        val p = Pipeline(
            detectorFactory = { status -> DetectorFactory.create(applicationContext, backendId, model, status) },
            model = model,
            renderer = r,
            probe = probe,
            capture = h,
            onHud = { text -> main.post { showHud(text) } },
            onFatal = { msg ->
                ServiceState.lastError = msg
                main.post { shutdown() }
            },
            socInfo = soc,
        )
        p.sceneGuardEnabled = AppSettings.sceneGuard
        p.csvLog = AppSettings.csvLog
        p.appearanceGuardEnabled = AppSettings.appearanceGuard
        p.scrollGuardEnabled = AppSettings.scrollGuard
        p.gridBoostEnabled = AppSettings.gridBoost
        p.vsyncMs = vsync
        p.extraLeadMs = AppSettings.extraLeadMs.toFloat()
        pipeline = p
        if (p.csvLog) {
            Log.i(Pipeline.CSV, Pipeline.CSV_HEADER_F)
            Log.i(Pipeline.CSV, Pipeline.CSV_HEADER_D)
            Log.i(Pipeline.CSV, "I,soc=$soc,model=${model.px},backend=$backendId,runtime=${Backends.RUNTIME},refresh=${display.refreshRate}")
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
        ServiceState.set(true)
        if (AppSettings.debugHud) r.showHud("Veil: starting… (${Backends.RUNTIME})")
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        val p = pipeline!!
        ir.setOnImageAvailableListener({ rd ->
            // keep settings live
            p.sceneGuardEnabled = AppSettings.sceneGuard
            p.csvLog = AppSettings.csvLog
            p.appearanceGuardEnabled = AppSettings.appearanceGuard
            p.scrollGuardEnabled = AppSettings.scrollGuard
            p.gridBoostEnabled = AppSettings.gridBoost
            p.extraLeadMs = AppSettings.extraLeadMs.toFloat()
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
        val long = (if (AppSettings.gridBoost) CAPTURE_LONG_PER_INPUT_GRID else CAPTURE_LONG_PER_INPUT) * AppSettings.modelSize.px
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
    }

    private fun showHud(text: String?) {
        val r = renderer ?: return
        r.showHud(if (AppSettings.debugHud) text else null)
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
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
            .setContentText(getString(R.string.notif_text))
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

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
