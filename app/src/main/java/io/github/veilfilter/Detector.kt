package io.github.veilfilter

import java.io.Closeable

/**
 * One NudeNet model instance on one backend. Created, used and closed on ONE
 * thread (GPU backends require it). v1.8: the input may be non-square
 * ([inW] x [inH], e.g. 224 x 480 portrait).
 */
interface Detector : Closeable {
    /** Human-readable backend, shown in the HUD. */
    val backend: String
    val inW: Int
    val inH: Int
    val numAnchors: Int
    /** multiply raw box x / w values by this to get model-input pixels */
    val coordScaleX: Float
    /** multiply raw box y / h values by this to get model-input pixels */
    val coordScaleY: Float
    /** Run on an NCHW RGB/255 float input; returns the raw [22 x anchors] output (do not keep). */
    fun run(input: FloatArray): FloatArray
}

enum class ModelRuntime { TFLITE, ONNX }

/** Where a model file comes from. */
enum class Source {
    /** inside the APK */
    BUNDLED,
    /** official NudeNet GitHub release (works out of the box) */
    UPSTREAM,
    /** our TFLite conversions, hosted wherever [AppSettings.modelBaseUrl] points */
    HOSTED,
}

/**
 * Detection models (all NudeNet v3, same 18 classes). Every downloadable file
 * has a hard-coded size + SHA-256 and is only usable after verification.
 * Name kept as ModelSize for compatibility with settings / older code.
 */
enum class ModelSize(
    /** long side of the model input (used for the capture size) */
    val px: Int,
    val asset: String,
    anchors: Int,
    val id: String,
    val label: String,
    val file: String? = null,
    /** grid-boost tile passes are affordable with this model */
    val tiles: Boolean = true,
    val runtime: ModelRuntime = ModelRuntime.TFLITE,
    val source: Source = Source.BUNDLED,
    val bytes: Long = 0L,
    val sha256: String = "",
    /** official release asset (UPSTREAM): GitHub API asset id + public URL */
    val upstreamAssetId: Long = 0L,
    val upstreamUrl: String = "",
    /** honest speed note for the S23 FE (estimates unless marked measured) */
    val note: String = "",
    /** v1.8: model input width x height (default square px x px) */
    val w: Int = px,
    val h: Int = px,
    /** v1.8: vsync extrapolation cap (ms) for this model's detector latency class */
    val predictCapMs: Long = 200L,
) {
    // ---- v1.8 portrait models (NudeNet 320n weights, fixed portrait input, FP16 weights) ----
    P224(480, "nudenet_320n_p224x480.tflite", 0, "320n_p224", "320n @ 224×480 portrait · TFLite GPU",
        file = null, w = 224, h = 480, predictCapMs = 180L,
        note = "default · cost of 320×320, 1.5× the resolution on a phone screen · est. ~17–25 ms GPU"),
    P256(544, "", 0, "320n_p256", "320n @ 256×544 portrait · TFLite GPU",
        file = "nudenet_320n_p256x544_fp16.tflite", source = Source.HOSTED, bytes = 6_214_464,
        sha256 = "3b8cebb09ac584c1347b4c92e59840ec34dc935e08ecfc969170cd71e980c35f",
        w = 256, h = 544, predictCapMs = 200L,
        note = "balanced · est. ~22–33 ms GPU"),
    P288(608, "", 0, "320n_p288", "320n @ 288×608 portrait · TFLite GPU",
        file = "nudenet_320n_p288x608_fp16.tflite", source = Source.HOSTED, bytes = 6_223_296,
        sha256 = "bd3b5436229a1dbf2b55bc906ac40169309da461fdca598b3deec1db7661698b",
        w = 288, h = 608, predictCapMs = 210L,
        note = "sharper · est. ~27–41 ms GPU"),
    P320(704, "", 0, "320n_p320", "320n @ 320×704 portrait · TFLite GPU",
        file = "nudenet_320n_p320x704_fp16.tflite", source = Source.HOSTED, bytes = 6_235_632,
        sha256 = "fc547bac3f616138f130381c821a62951acc72f00fbc8bfb401c3e79edaaeb1f",
        w = 320, h = 704, predictCapMs = 230L,
        note = "most detail, best recall · est. ~35–53 ms GPU"),
    // ---- square models (v1.7 and earlier; the square 320 is no longer bundled since v1.8) ----
    S320(320, "", 2100, "320n_320", "320n @ 320 square · TFLite GPU",
        file = "nudenet_320n_fp16.tflite", source = Source.HOSTED, bytes = 6_205_280,
        sha256 = "bc3e88c6896b2a2d8efdbc16dc413a3642baba19ac25dbb7bdb4466500c138f6",
        predictCapMs = 180L,
        note = "v1.7 default · measured ~16–24 ms on S23 FE GPU"),
    S256(256, "", 1344, "320n_256", "320n @ 256 · TFLite GPU",
        file = "nudenet_320n_at256.tflite", source = Source.HOSTED, bytes = 6_196_208,
        sha256 = "1d4799b4f19ba30507f9e82cd623e16c84a3b494097db554d02ca33c8ab8c559",
        predictCapMs = 180L,
        note = "fastest, misses more · est. ~12–18 ms GPU"),
    N640(640, "", 8400, "320n_640", "320n @ 640 · TFLite GPU",
        file = "nudenet_320n_at640.tflite", source = Source.HOSTED, bytes = 6_280_880,
        sha256 = "59412c84ff5598a4d45ca58a45598aa8ce8fe434146acf172dcacfa17285c887",
        predictCapMs = 230L,
        note = "sharper on small/far content · est. ~40–70 ms GPU"),
    M640(640, "", 8400, "640m_640", "640m @ 640 · TFLite GPU",
        file = "nudenet_640m_fp16.tflite", tiles = false, source = Source.HOSTED, bytes = 52_031_664,
        sha256 = "e5be516c5416522b9869b795e027947b9083d6e333758394a90b170ef6abb073",
        predictCapMs = 230L,
        note = "most accurate · est. ~120–300 ms GPU (still images, not reels)"),
    X480(480, "", 4725, "onnx_320n_480", "320n @ 480 · ONNX CPU",
        file = "320n.onnx", tiles = false, runtime = ModelRuntime.ONNX, source = Source.UPSTREAM, bytes = 12_150_158,
        sha256 = "c15d8273adad2d0a92f014cc69ab2d6c311a06777a55545f2c4eb46f51911f0f",
        upstreamAssetId = 176831997L,
        upstreamUrl = "https://github.com/notAI-tech/NudeNet/releases/download/v3.4-weights/320n.onnx",
        predictCapMs = 230L,
        note = "official file, CPU only · est. ~30–60 ms"),
    X640(640, "", 8400, "onnx_320n_640", "320n @ 640 · ONNX CPU",
        file = "320n.onnx", tiles = false, runtime = ModelRuntime.ONNX, source = Source.UPSTREAM, bytes = 12_150_158,
        sha256 = "c15d8273adad2d0a92f014cc69ab2d6c311a06777a55545f2c4eb46f51911f0f",
        upstreamAssetId = 176831997L,
        upstreamUrl = "https://github.com/notAI-tech/NudeNet/releases/download/v3.4-weights/320n.onnx",
        predictCapMs = 230L,
        note = "official file, CPU only · est. ~50–100 ms"),
    Y480(480, "", 4725, "onnx_640m_480", "640m @ 480 · ONNX CPU",
        file = "640m.onnx", tiles = false, runtime = ModelRuntime.ONNX, source = Source.UPSTREAM, bytes = 103_538_690,
        sha256 = "04fe3d77980780c1f8297dc6d7f942fd5b3abe6942a188f742a85241e4f634eb",
        upstreamAssetId = 176832019L,
        upstreamUrl = "https://github.com/notAI-tech/NudeNet/releases/download/v3.4-weights/640m.onnx",
        predictCapMs = 230L,
        note = "official file, CPU only · est. ~250–500 ms"),
    Y640(640, "", 8400, "onnx_640m_640", "640m @ 640 · ONNX CPU",
        file = "640m.onnx", tiles = false, runtime = ModelRuntime.ONNX, source = Source.UPSTREAM, bytes = 103_538_690,
        sha256 = "04fe3d77980780c1f8297dc6d7f942fd5b3abe6942a188f742a85241e4f634eb",
        upstreamAssetId = 176832019L,
        upstreamUrl = "https://github.com/notAI-tech/NudeNet/releases/download/v3.4-weights/640m.onnx",
        predictCapMs = 230L,
        note = "official file, CPU only · est. ~400–800 ms (still images)");

    /** YOLOv8 anchors for the input (strides 8, 16, 32) */
    val anchors: Int = if (anchors > 0) anchors else (w / 8) * (h / 8) + (w / 16) * (h / 16) + (w / 32) * (h / 32)
    /** taller than wide: landscape frames are folded into it (see [InputLayout]) */
    val portrait: Boolean get() = h > w

    val external: Boolean get() = file != null
    fun filePath(ctx: android.content.Context): java.io.File = filePath(ctx.filesDir)
    fun filePath(filesDir: java.io.File): java.io.File = java.io.File(java.io.File(filesDir, "models"), file ?: asset)
    fun available(ctx: android.content.Context): Boolean = !external || filePath(ctx).isFile

    /**
     * Download URLs, in order of preference. UPSTREAM: GitHub API asset endpoint
     * (needs Accept: application/octet-stream; robust against the web URL's
     * login redirect seen from some networks) then the public web URL.
     */
    fun urls(baseUrl: String): List<Pair<String, Map<String, String>>> = when (source) {
        Source.BUNDLED -> emptyList()
        Source.UPSTREAM -> listOf(
            "https://api.github.com/repos/notAI-tech/NudeNet/releases/assets/$upstreamAssetId" to
                mapOf("Accept" to "application/octet-stream"),
            upstreamUrl to emptyMap(),
        )
        Source.HOSTED -> if (!hostConfigured(baseUrl)) emptyList()
        else listOf((baseUrl.trimEnd('/') + "/" + file) to emptyMap())
    }

    companion object {
        /** the bundled default (v1.8: portrait 224 x 480) */
        val DEFAULT = P224
        fun byId(id: String?): ModelSize? = entries.firstOrNull { it.id == id }
        fun anchorsFor(w: Int, h: Int) = (w / 8) * (h / 8) + (w / 16) * (h / 16) + (w / 32) * (h / 32)
        /** Capture long side: [perInput] x model input long side, but never above [maxLong]. */
        fun captureLong(model: ModelSize, perInput: Float, maxLong: Float = 1280f) = kotlin.math.min(perInput * model.px, maxLong)
        /** pre-1.8 template host (a stored copy of it is replaced by [DEFAULT_BASE_URL]) */
        const val HOST_PLACEHOLDER = "YOUR-GITHUB-USER"
        /** v1.8: the project's own GitHub release hosts the TFLite files (public, no login) */
        const val DEFAULT_BASE_URL = "https://github.com/onna83/Veil/releases/download/v1.8"
        fun hostConfigured(baseUrl: String) =
            baseUrl.startsWith("https://") && !baseUrl.contains(HOST_PLACEHOLDER) && baseUrl.length > 12
        fun bySha(sha: String): ModelSize? = entries.firstOrNull { it.sha256 == sha }
    }
}

/** A runtime-selectable backend (implemented per build flavor in Backends.kt). */
class BackendOption(val id: String, val label: String)
