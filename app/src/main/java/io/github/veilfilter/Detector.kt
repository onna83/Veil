package io.github.veilfilter

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * NudeNet 320n on LiteRT (TensorFlow Lite runtime).
 *
 * Backend order: GPU delegate (FP16 inference, sustained-speed) -> XNNPACK CPU.
 * The model is Ultralytics' LiteRT export of NudeNet's 320n.pt: input
 * float32 [1,3,320,320] NCHW RGB 0..1, output float32 [1,22,2100] with
 * normalised (0..1) xywh boxes and sigmoid class scores.
 *
 * Must be created, used and closed on ONE thread (GPU delegate requirement).
 */
class Detector private constructor(
    private val interpreter: Interpreter,
    private val gpu: GpuDelegate?,
    val backend: String,
) : Closeable {
    val inputSize = 320
    val numAnchors = 2100
    val numOutputs = 4 + Labels.NUM_CLASSES
    /** Ultralytics LiteRT export emits normalised boxes. */
    val coordScale = inputSize.toFloat()

    private val inBuf: ByteBuffer = ByteBuffer.allocateDirect(4 * 3 * inputSize * inputSize).order(ByteOrder.nativeOrder())
    private val inFloats: FloatBuffer = inBuf.asFloatBuffer()
    private val outBuf: ByteBuffer = ByteBuffer.allocateDirect(4 * numOutputs * numAnchors).order(ByteOrder.nativeOrder())
    private val outFloats: FloatBuffer = outBuf.asFloatBuffer()
    val output = FloatArray(numOutputs * numAnchors)

    /** Runs the model on an NCHW float input array; result in [output]. */
    fun run(input: FloatArray) {
        inFloats.rewind()
        inFloats.put(input)
        inBuf.rewind()
        outBuf.rewind()
        interpreter.run(inBuf, outBuf)
        outFloats.rewind()
        outFloats.get(output)
    }

    override fun close() {
        try { interpreter.close() } catch (_: Throwable) {}
        try { gpu?.close() } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "VeilDetector"
        const val MODEL_ASSET = "nudenet_320n.tflite"

        private fun loadModel(ctx: Context): MappedByteBuffer {
            val fd = ctx.assets.openFd(MODEL_ASSET)
            FileInputStream(fd.fileDescriptor).use { fis ->
                return fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

        /** Tries GPU first, then CPU; verifies each with a warm-up inference. */
        fun create(ctx: Context, allowGpu: Boolean = true): Detector {
            val model = loadModel(ctx)
            if (allowGpu) {
                var delegate: GpuDelegate? = null
                var interp: Interpreter? = null
                try {
                    val compat = CompatibilityList()
                    val supported = compat.isDelegateSupportedOnThisDevice
                    compat.close()
                    if (supported) {
                        val opts = GpuDelegateFactory.Options()
                            .setPrecisionLossAllowed(true) // FP16 on GPU
                            .setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                        // Cache compiled GPU programs: much faster start after first run.
                        opts.setSerializationParams(ctx.codeCacheDir.absolutePath, "nudenet320n_v1")
                        delegate = GpuDelegate(opts)
                        val io = Interpreter.Options().addDelegate(delegate).setNumThreads(cpuThreads())
                        interp = Interpreter(model, io)
                        val d = Detector(interp, delegate, "GPU (LiteRT)")
                        d.run(FloatArray(3 * 320 * 320)) // warm-up / validation
                        Log.i(TAG, "Using GPU delegate")
                        return d
                    } else {
                        Log.i(TAG, "GPU delegate not supported on this device")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "GPU delegate failed, falling back to CPU", t)
                    try { interp?.close() } catch (_: Throwable) {}
                    try { delegate?.close() } catch (_: Throwable) {}
                }
            }
            val threads = cpuThreads()
            val io = Interpreter.Options().setNumThreads(threads).setUseXNNPACK(true)
            val interp = Interpreter(model, io)
            val d = Detector(interp, null, "CPU XNNPACK x$threads")
            d.run(FloatArray(3 * 320 * 320))
            Log.i(TAG, "Using CPU XNNPACK, $threads threads")
            return d
        }

        /** Big cores only, roughly: half the cores, between 2 and 4. */
        private fun cpuThreads(): Int =
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
    }
}
