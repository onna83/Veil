package io.github.veilfilter

/**
 * The litert2 build does not include ONNX Runtime (it would push the APK zip
 * over the 25 MB attachment limit). ONNX models need the classic build.
 */
object OrtDetector {
    const val SUPPORTED = false
    fun create(ctx: android.content.Context, model: ModelSize, nnapi: Boolean): Detector =
        error("${model.id}: ONNX models need the classic Veil build")
}
