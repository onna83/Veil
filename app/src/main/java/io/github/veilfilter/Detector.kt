package io.github.veilfilter

import java.io.Closeable

/**
 * One NudeNet 320n/256n model instance on one backend. Created, used and
 * closed on ONE thread (GPU backends require it).
 */
interface Detector : Closeable {
    /** Human-readable backend, shown in the HUD. */
    val backend: String
    val inputSize: Int
    val numAnchors: Int
    /** multiply raw box values by this to get model-input pixels */
    val coordScale: Float
    /** Run on an NCHW RGB/255 float input; returns the raw [22 x anchors] output (do not keep). */
    fun run(input: FloatArray): FloatArray
}

/**
 * Detection models (all NudeNet v3, same 18 classes, FP16 weights).
 * [asset] = bundled in the APK; [file] = imported once into app storage
 * (too big for the APK / a 25 MB attachment), see [ModelImporter].
 */
enum class ModelSize(
    val px: Int, val asset: String, val anchors: Int, val id: String, val label: String,
    val file: String? = null,
    /** grid-boost tile passes are affordable with this model */
    val tiles: Boolean = true,
) {
    S320(320, "nudenet_320n.tflite", 2100, "320n_320", "320n @ 320 – fast (default)"),
    S256(256, "nudenet_320n_at256.tflite", 1344, "320n_256", "320n @ 256 – fastest, less accurate"),
    N640(640, "nudenet_320n_at640.tflite", 8400, "320n_640", "320n @ 640 – sharper on small/far content, ~3x slower"),
    M640(640, "", 8400, "640m_640", "640m @ 640 – most accurate, ~10x+ slower (import model first)",
        file = "nudenet_640m_fp16.tflite", tiles = false);

    val external: Boolean get() = file != null
    fun filePath(ctx: android.content.Context): java.io.File = java.io.File(java.io.File(ctx.filesDir, "models"), file ?: asset)
    fun available(ctx: android.content.Context): Boolean = !external || filePath(ctx).isFile

    companion object {
        fun byId(id: String?): ModelSize? = entries.firstOrNull { it.id == id }
        /** Capture long side: [perInput] x model input, but never above [maxLong] (640 models). */
        fun captureLong(model: ModelSize, perInput: Float, maxLong: Float = 1280f) = kotlin.math.min(perInput * model.px, maxLong)
    }
}

/** A runtime-selectable backend (implemented per build flavor in Backends.kt). */
class BackendOption(val id: String, val label: String)
