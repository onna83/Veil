package io.github.veilfilter

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * ONNX Runtime backend for the official NudeNet .onnx files (dynamic input
 * size, so one 320n.onnx serves 480 and 640). CPU (XNNPACK) by default;
 * NNAPI optionally (experimental, falls back to CPU). Boxes come out in
 * input pixels (coordScale 1), unlike our TFLite exports (normalised).
 */
class OrtDetector private constructor(
    private val session: OrtSession,
    private val env: OrtEnvironment,
    override val backend: String,
    model: ModelSize,
) : Detector {
    override val inputSize = model.px
    override val numAnchors = model.anchors
    override val coordScale = 1f
    private val inName = session.inputNames.first()
    private val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
    private val buf: FloatBuffer = java.nio.ByteBuffer.allocateDirect(4 * 3 * inputSize * inputSize)
        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
    private val out = FloatArray(22 * numAnchors)

    override fun run(input: FloatArray): FloatArray {
        buf.rewind(); buf.put(input); buf.rewind()
        OnnxTensor.createTensor(env, buf, shape).use { t ->
            session.run(mapOf(inName to t)).use { r ->
                val v = r[0] as OnnxTensor
                val fb = v.floatBuffer
                fb.rewind(); fb.get(out, 0, minOf(out.size, fb.remaining()))
            }
        }
        return out
    }

    override fun close() {
        try { session.close() } catch (_: Throwable) {}
    }

    companion object {
        const val SUPPORTED = true
        fun create(ctx: android.content.Context, model: ModelSize, nnapi: Boolean): Detector {
            val f = model.filePath(ctx)
            if (!f.isFile) error("${model.id}: model not downloaded yet")
            val env = OrtEnvironment.getEnvironment()
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
            fun opts(useNnapi: Boolean) = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(threads)
                if (useNnapi) addNnapi() else addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            }
            if (nnapi) {
                try {
                    val s = env.createSession(f.absolutePath, opts(true))
                    val d = OrtDetector(s, env, "ORT-NNAPI ${model.id}", model)
                    d.run(FloatArray(3 * model.px * model.px))
                    return d
                } catch (t: Throwable) {
                    android.util.Log.w("VeilOrt", "NNAPI failed, using CPU", t)
                }
            }
            val s = env.createSession(f.absolutePath, opts(false))
            val d = OrtDetector(s, env, "ORT-CPU x$threads ${model.id}", model)
            d.run(FloatArray(3 * model.px * model.px))
            return d
        }
    }
}
