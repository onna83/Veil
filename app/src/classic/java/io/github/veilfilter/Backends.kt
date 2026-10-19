package io.github.veilfilter

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * "classic" flavor: LiteRT 1.4.2 Interpreter + legacy GPU delegate
 * (the backend that ran at 15.8 ms on the S23 FE in v1).
 */
object Backends {
    const val RUNTIME = "LiteRT 1.4 (GPU delegate)"
    private const val TAG = "VeilDetector"

    val options = listOf(
        BackendOption("auto", "Auto (GPU, fall back to CPU)"),
        BackendOption("gpu_cl", "GPU - OpenCL"),
        BackendOption("gpu_gl", "GPU - OpenGL ES"),
        BackendOption("cpu", "CPU - XNNPACK"),
    )

    private class Tfl(
        private val interpreter: Interpreter,
        private val gpu: GpuDelegate?,
        override val backend: String,
        model: ModelSize,
    ) : Detector {
        override val inputSize = model.px
        override val numAnchors = model.anchors
        override val coordScale = model.px.toFloat() // normalised boxes
        private val inBuf = ByteBuffer.allocateDirect(4 * 3 * inputSize * inputSize).order(ByteOrder.nativeOrder())
        private val inF: FloatBuffer = inBuf.asFloatBuffer()
        private val outBuf = ByteBuffer.allocateDirect(4 * 22 * numAnchors).order(ByteOrder.nativeOrder())
        private val outF: FloatBuffer = outBuf.asFloatBuffer()
        private val out = FloatArray(22 * numAnchors)

        override fun run(input: FloatArray): FloatArray {
            inF.rewind(); inF.put(input); inBuf.rewind(); outBuf.rewind()
            interpreter.run(inBuf, outBuf)
            outF.rewind(); outF.get(out)
            return out
        }

        override fun close() {
            try { interpreter.close() } catch (_: Throwable) {}
            try { gpu?.close() } catch (_: Throwable) {}
        }
    }

    private fun load(ctx: Context, asset: String): MappedByteBuffer {
        val fd = ctx.assets.openFd(asset)
        FileInputStream(fd.fileDescriptor).use { return it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
    }

    private fun cpuThreads() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    private fun gpu(ctx: Context, model: ModelSize, buf: MappedByteBuffer, force: GpuDelegateFactory.Options.GpuBackend?): Detector {
        val compat = CompatibilityList()
        val ok = compat.isDelegateSupportedOnThisDevice
        compat.close()
        if (!ok) error("GPU delegate not supported on this device")
        val opts = GpuDelegateFactory.Options()
            .setPrecisionLossAllowed(true)
            .setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
            .setSerializationParams(ctx.codeCacheDir.absolutePath, "nudenet_${model.px}_${force ?: "auto"}_v11")
        if (force != null) opts.setForceBackend(force)
        val delegate = GpuDelegate(opts)
        try {
            val interp = Interpreter(buf, Interpreter.Options().addDelegate(delegate).setNumThreads(cpuThreads()))
            val name = when (force) {
                GpuDelegateFactory.Options.GpuBackend.OPENCL -> "GPU-CL"
                GpuDelegateFactory.Options.GpuBackend.OPENGL -> "GPU-GL"
                else -> "GPU"
            }
            val d = Tfl(interp, delegate, "$name LiteRT1 ${model.px}", model)
            d.run(FloatArray(3 * model.px * model.px))
            return d
        } catch (t: Throwable) {
            delegate.close()
            throw t
        }
    }

    private fun cpu(model: ModelSize, buf: MappedByteBuffer): Detector {
        val n = cpuThreads()
        val interp = Interpreter(buf, Interpreter.Options().setNumThreads(n).setUseXNNPACK(true))
        val d = Tfl(interp, null, "CPU-XNNPACK x$n ${model.px}", model)
        d.run(FloatArray(3 * model.px * model.px))
        return d
    }

    /** Create the requested backend; "auto" and failing GPU options fall back to CPU. */
    fun create(ctx: Context, id: String, model: ModelSize): Detector {
        val buf = load(ctx, model.asset)
        val force = when (id) {
            "gpu_cl" -> GpuDelegateFactory.Options.GpuBackend.OPENCL
            "gpu_gl" -> GpuDelegateFactory.Options.GpuBackend.OPENGL
            else -> null
        }
        if (id != "cpu") {
            try { return gpu(ctx, model, buf, force) } catch (t: Throwable) {
                Log.w(TAG, "GPU backend '$id' failed, falling back to CPU", t)
                if (id != "auto" && strict) throw t
            }
        }
        return cpu(model, buf)
    }

    /** Benchmark mode sets this so a failing option reports an error instead of CPU numbers. */
    @Volatile var strict = false
}
