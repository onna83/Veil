package io.github.veilfilter

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer

/**
 * "litert2" flavor: LiteRT 2.3 CompiledModel API. GPU uses the new ML Drift
 * GPU engine (OpenCL / OpenGL selectable), CPU uses XNNPACK.
 * NPU: LiteRT publishes NPU runtimes for Qualcomm/MediaTek/Google Tensor only
 * (none for Samsung Exynos), and they need per-SoC compiled models; not in v1.1.
 */
object Backends {
    const val RUNTIME = "LiteRT 2.3 (CompiledModel)"

    val options = listOf(
        BackendOption("auto", "Auto (GPU, fall back to CPU)"),
        BackendOption("gpu_cl", "GPU - OpenCL (ML Drift)"),
        BackendOption("gpu_gl", "GPU - OpenGL ES (ML Drift)"),
        BackendOption("cpu", "CPU - XNNPACK"),
    )

    private var env: Environment? = null

    private class Cm(
        private val model: CompiledModel,
        override val backend: String,
        size: ModelSize,
    ) : Detector {
        override val inW = size.w
        override val inH = size.h
        override val numAnchors = size.anchors
        override val coordScaleX = size.w.toFloat()
        override val coordScaleY = size.h.toFloat()
        private val inputs: List<TensorBuffer> = model.createInputBuffers()
        private val outputs: List<TensorBuffer> = model.createOutputBuffers()

        override fun run(input: FloatArray): FloatArray {
            inputs[0].writeFloat(input)
            model.run(inputs, outputs)
            return outputs[0].readFloat() // allocates 185 KB/frame; API has no read-into
        }

        override fun close() {
            try { inputs.forEach { it.close() } } catch (_: Throwable) {}
            try { outputs.forEach { it.close() } } catch (_: Throwable) {}
            try { model.close() } catch (_: Throwable) {}
        }
    }

    private fun environment(): Environment = env ?: Environment.create().also { env = it }

    private fun open(ctx: Context, size: ModelSize, opts: CompiledModel.Options): CompiledModel {
        if (size.external) {
            val f = size.filePath(ctx)
            if (!f.isFile) error("${size.id} not imported yet (Settings → Detection model → Import)")
            return CompiledModel.create(f.absolutePath, opts, environment())
        }
        return CompiledModel.create(ctx.assets, size.asset, opts, environment())
    }

    private fun gpu(ctx: Context, size: ModelSize, backend: CompiledModel.GpuOptions.Backend): Detector {
        val opts = CompiledModel.Options(Accelerator.GPU)
        opts.gpuOptions = CompiledModel.GpuOptions(
            precision = CompiledModel.GpuOptions.Precision.FP16,
            backend = backend,
            priority = CompiledModel.GpuOptions.Priority.HIGH,
            serializationDir = ctx.codeCacheDir.absolutePath,
            modelCacheKey = "nudenet_${size.id}_${backend.name}_v18",
            serializeProgramCache = true,
        )
        val m = open(ctx, size, opts)
        val name = when (backend) {
            CompiledModel.GpuOptions.Backend.OPENCL -> "GPU-CL"
            CompiledModel.GpuOptions.Backend.OPENGL -> "GPU-GL"
            else -> "GPU"
        }
        val d = Cm(m, "$name LiteRT2 ${size.id}", size)
        d.run(FloatArray(3 * size.w * size.h))
        return d
    }

    private fun cpu(ctx: Context, size: ModelSize): Detector {
        val n = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val opts = CompiledModel.Options(Accelerator.CPU)
        opts.cpuOptions = CompiledModel.CpuOptions(numThreads = n)
        val m = open(ctx, size, opts)
        val d = Cm(m, "CPU-XNNPACK x$n LiteRT2 ${size.id}", size)
        d.run(FloatArray(3 * size.w * size.h))
        return d
    }

    val autoCandidates = listOf("gpu_cl", "gpu_gl", "cpu")

    /** Create exactly this backend (no fallback); throws if it cannot run here. */
    fun createExact(ctx: Context, id: String, size: ModelSize): Detector = when (id) {
        "gpu_cl" -> gpu(ctx, size, CompiledModel.GpuOptions.Backend.OPENCL)
        "gpu_gl" -> gpu(ctx, size, CompiledModel.GpuOptions.Backend.OPENGL)
        "gpu" -> gpu(ctx, size, CompiledModel.GpuOptions.Backend.AUTOMATIC)
        "cpu" -> cpu(ctx, size)
        else -> error("unknown backend $id")
    }
}
