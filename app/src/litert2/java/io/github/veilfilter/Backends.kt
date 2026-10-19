package io.github.veilfilter

import android.content.Context
import android.util.Log
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
    private const val TAG = "VeilDetector"

    val options = listOf(
        BackendOption("auto", "Auto (GPU, fall back to CPU)"),
        BackendOption("gpu_cl", "GPU - OpenCL (ML Drift)"),
        BackendOption("gpu_gl", "GPU - OpenGL ES (ML Drift)"),
        BackendOption("cpu", "CPU - XNNPACK"),
    )

    @Volatile var strict = false
    private var env: Environment? = null

    private class Cm(
        private val model: CompiledModel,
        override val backend: String,
        size: ModelSize,
    ) : Detector {
        override val inputSize = size.px
        override val numAnchors = size.anchors
        override val coordScale = size.px.toFloat()
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

    private fun gpu(ctx: Context, size: ModelSize, backend: CompiledModel.GpuOptions.Backend): Detector {
        val opts = CompiledModel.Options(Accelerator.GPU)
        opts.gpuOptions = CompiledModel.GpuOptions(
            precision = CompiledModel.GpuOptions.Precision.FP16,
            backend = backend,
            priority = CompiledModel.GpuOptions.Priority.HIGH,
            serializationDir = ctx.codeCacheDir.absolutePath,
            modelCacheKey = "nudenet_${size.px}_${backend.name}_v11",
            serializeProgramCache = true,
        )
        val m = CompiledModel.create(ctx.assets, size.asset, opts, environment())
        val name = when (backend) {
            CompiledModel.GpuOptions.Backend.OPENCL -> "GPU-CL"
            CompiledModel.GpuOptions.Backend.OPENGL -> "GPU-GL"
            else -> "GPU"
        }
        val d = Cm(m, "$name LiteRT2 ${size.px}", size)
        d.run(FloatArray(3 * size.px * size.px))
        return d
    }

    private fun cpu(ctx: Context, size: ModelSize): Detector {
        val n = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val opts = CompiledModel.Options(Accelerator.CPU)
        opts.cpuOptions = CompiledModel.CpuOptions(numThreads = n)
        val m = CompiledModel.create(ctx.assets, size.asset, opts, environment())
        val d = Cm(m, "CPU-XNNPACK x$n LiteRT2 ${size.px}", size)
        d.run(FloatArray(3 * size.px * size.px))
        return d
    }

    fun create(ctx: Context, id: String, size: ModelSize): Detector {
        if (id != "cpu") {
            val b = when (id) {
                "gpu_cl" -> CompiledModel.GpuOptions.Backend.OPENCL
                "gpu_gl" -> CompiledModel.GpuOptions.Backend.OPENGL
                else -> CompiledModel.GpuOptions.Backend.AUTOMATIC
            }
            try { return gpu(ctx, size, b) } catch (t: Throwable) {
                Log.w(TAG, "GPU backend '$id' failed, falling back to CPU", t)
                if (id != "auto" && strict) throw t
            }
        }
        return cpu(ctx, size)
    }
}
