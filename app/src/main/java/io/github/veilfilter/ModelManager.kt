package io.github.veilfilter

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Model files on this device + user-initiated downloads. Downloads run on a
 * plain thread started by a button tap; nothing is scheduled, nothing runs in
 * the background after the app process ends (a partial .dl file is resumed
 * on the next tap). Several catalog entries can share one file (320n.onnx
 * serves 480 and 640), so state is keyed by file name.
 */
object ModelManager {
    class Dl {
        @Volatile var done = 0L
        @Volatile var total = 0L
        @Volatile var cancel = false
    }

    sealed class Status {
        object Bundled : Status()
        object Ready : Status()
        object NotDownloaded : Status()
        object NoHost : Status()
        class Partial(val bytes: Long) : Status()
        class Downloading(val done: Long, val total: Long) : Status()
    }

    private val active = ConcurrentHashMap<String, Dl>()
    private val errors = ConcurrentHashMap<String, String>()
    private val listeners = ArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners.remove(l) }
    private fun notifyAll0() { val ls = synchronized(listeners) { listeners.toList() }; ls.forEach { it() } }

    private fun dir(ctx: Context) = java.io.File(ctx.filesDir, "models")

    fun status(ctx: Context, m: ModelSize): Status {
        if (!m.external) return Status.Bundled
        val f = m.file!!
        active[f]?.let { return Status.Downloading(it.done, it.total) }
        if (m.filePath(ctx).isFile) return Status.Ready
        if (m.source == Source.HOSTED && !ModelSize.hostConfigured(AppSettings.modelBaseUrl)) return Status.NoHost
        val part = java.io.File(dir(ctx), "$f.dl")
        if (part.isFile && part.length() > 0) return Status.Partial(part.length())
        return Status.NotDownloaded
    }

    fun error(m: ModelSize): String? = m.file?.let { errors[it] }

    fun download(ctx: Context, m: ModelSize, onDone: (ok: Boolean) -> Unit = {}) {
        val f = m.file ?: return
        val dl = Dl().also { it.total = m.bytes }
        if (active.putIfAbsent(f, dl) != null) return
        errors.remove(f)
        val app = ctx.applicationContext
        val urls = m.urls(AppSettings.modelBaseUrl)
        notifyAll0()
        Thread({
            var last = 0L
            val r = ModelDownloader(dir(app)).download(urls, f, m.bytes, m.sha256,
                onProgress = { d, t ->
                    dl.done = d; dl.total = t
                    val now = System.currentTimeMillis()
                    if (now - last > 250) { last = now; notifyAll0() }
                },
                cancelled = { dl.cancel },
            )
            active.remove(f)
            when (r) {
                is ModelDownloader.Result.Ok -> {}
                is ModelDownloader.Result.Cancelled -> errors[f] = "cancelled (tap Download to resume)"
                is ModelDownloader.Result.Failed -> errors[f] = r.message
            }
            notifyAll0()
            onDone(r is ModelDownloader.Result.Ok)
        }, "veil-download").start()
    }

    fun cancel(m: ModelSize) { m.file?.let { active[it]?.cancel = true } }

    /** Deletes the file (and partial download). Shared files affect every entry using them. */
    fun delete(ctx: Context, m: ModelSize) {
        val f = m.file ?: return
        if (active.containsKey(f)) return
        m.filePath(ctx).delete()
        java.io.File(dir(ctx), "$f.dl").delete()
        errors.remove(f)
        if (!AppSettings.model.available(ctx)) {
            AppSettings.setModel(ctx, ModelSize.DEFAULT)
            CaptureService.switchModel(ctx)
        }
        notifyAll0()
    }

    fun changed() = notifyAll0()
}
