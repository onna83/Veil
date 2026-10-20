package io.github.veilfilter

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Creates the detector for the selected backend.
 *
 * "auto": on the first start (per app version, runtime, model size and SoC)
 * every candidate (GPU OpenCL, GPU OpenGL, CPU XNNPACK x4) is created and
 * timed on this device, the fastest is kept and the choice is cached, so later
 * starts skip the measurement. The in-app benchmark refreshes the cache too.
 * Explicit choices fall back to CPU if they cannot run.
 */
object DetectorFactory {
    private const val TAG = "VeilDetector"
    private const val PREFS = "veil_auto"

    /** Wraps a detector with a different HUD label. */
    private class Labeled(private val d: Detector, override val backend: String) : Detector by d

    fun create(ctx: Context, id: String, model: ModelSize, onStatus: (String) -> Unit = {}): Detector {
        if (model.runtime == ModelRuntime.ONNX) {
            onStatus("loading ONNX ${model.id}…")
            return OrtDetector.create(ctx, model, AppSettings.ortNnapi(ctx))
        }
        if (id == "auto") return auto(ctx, model, onStatus)
        return try {
            Backends.createExact(ctx, id, model)
        } catch (t: Throwable) {
            Log.w(TAG, "backend '$id' failed, falling back to CPU", t)
            Labeled(Backends.createExact(ctx, "cpu", model), "CPU (fallback from $id)")
        }
    }

    private fun key(ctx: Context, model: ModelSize): String {
        val ver = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        } catch (_: Throwable) { 0L }
        return "${Backends.RUNTIME}|${model.id}|$ver|${Build.SOC_MODEL}"
    }

    fun remember(ctx: Context, model: ModelSize, id: String, ms: Double) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(ctx, model), "$id|$ms").apply()
    }

    /** Median ms of [runs] inferences after [warm] warm-up runs. */
    fun time(d: Detector, warm: Int, runs: Int): DoubleArray {
        val input = FloatArray(3 * d.inputSize * d.inputSize) { ((it * 31) % 255) / 255f }
        repeat(warm) { d.run(input) }
        val t = DoubleArray(runs)
        for (i in 0 until runs) {
            val a = System.nanoTime()
            d.run(input)
            t[i] = (System.nanoTime() - a) / 1e6
        }
        t.sort()
        return t
    }

    private fun auto(ctx: Context, model: ModelSize, onStatus: (String) -> Unit): Detector {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(ctx, model)
        prefs.getString(k, null)?.split('|')?.let { parts ->
            val id = parts[0]
            val ms = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            try {
                val d = Backends.createExact(ctx, id, model)
                return Labeled(d, "auto→${d.backend} (cached %.1f ms)".format(java.util.Locale.US, ms))
            } catch (t: Throwable) {
                Log.w(TAG, "cached auto backend '$id' failed; re-measuring", t)
            }
        }
        var best: Detector? = null
        var bestId = ""
        var bestMs = Double.MAX_VALUE
        val summary = StringBuilder()
        for (id in Backends.autoCandidates) {
            onStatus("measuring $id…")
            val d = try { Backends.createExact(ctx, id, model) } catch (t: Throwable) {
                Log.w(TAG, "auto: $id unavailable", t)
                summary.append("$id:x ")
                continue
            }
            val ms = try { time(d, 3, 9)[4] } catch (t: Throwable) { Log.w(TAG, "auto: $id failed", t); Double.MAX_VALUE }
            summary.append("$id:%.1f ".format(java.util.Locale.US, ms))
            if (ms < bestMs) {
                best?.close()
                best = d; bestMs = ms; bestId = id
            } else d.close()
        }
        val b = best ?: return Labeled(Backends.createExact(ctx, "cpu", model), "CPU (no backend measured)")
        Log.i(TAG, "auto picked $bestId: $summary")
        prefs.edit().putString(k, "$bestId|$bestMs").apply()
        return Labeled(b, "auto→${b.backend} (measured: ${summary.trim()})")
    }
}
