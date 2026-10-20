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
    val px: Int,
    val asset: String,
    val anchors: Int,
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
) {
    S320(320, "nudenet_320n.tflite", 2100, "320n_320", "320n @ 320 · TFLite GPU",
        note = "fast · measured ~16–24 ms on S23 FE GPU (default)"),
    S256(256, "", 1344, "320n_256", "320n @ 256 · TFLite GPU",
        file = "nudenet_320n_at256.tflite", source = Source.HOSTED, bytes = 6_196_208,
        sha256 = "1d4799b4f19ba30507f9e82cd623e16c84a3b494097db554d02ca33c8ab8c559",
        note = "fastest, misses more · est. ~12–18 ms GPU"),
    N640(640, "", 8400, "320n_640", "320n @ 640 · TFLite GPU",
        file = "nudenet_320n_at640.tflite", source = Source.HOSTED, bytes = 6_280_880,
        sha256 = "59412c84ff5598a4d45ca58a45598aa8ce8fe434146acf172dcacfa17285c887",
        note = "sharper on small/far content · est. ~40–70 ms GPU"),
    M640(640, "", 8400, "640m_640", "640m @ 640 · TFLite GPU",
        file = "nudenet_640m_fp16.tflite", tiles = false, source = Source.HOSTED, bytes = 52_031_664,
        sha256 = "e5be516c5416522b9869b795e027947b9083d6e333758394a90b170ef6abb073",
        note = "most accurate · est. ~120–300 ms GPU (still images, not reels)"),
    X480(480, "", 4725, "onnx_320n_480", "320n @ 480 · ONNX CPU",
        file = "320n.onnx", tiles = false, runtime = ModelRuntime.ONNX, source = Source.UPSTREAM, bytes = 12_150_158,
        sha256 = "c15d8273adad2d0a92f014cc69ab2d6c311a06777a55545f2c4eb46f51911f0f",
        upstreamAssetId = 176831997L,
        upstreamUrl = "https://github.com/notAI-tech/NudeNet/releases/download/v3.4-weights/320n.onnx",
        note = "official file, CPU only · est. ~30–60 ms"),
    X640(640, "", 8400, "onnx_320n_640", "320n @ 640 · ONNX CPU",
        file = "320n.onnx", tiles = false, runtime = ModelRuntime.ONNX, source = Source.UPSTREAM, bytes = 12_150_158,
        sha256 = "c15d8273adad2d0a92f014cc69ab2d6c311a06777a55545f2c4eb46f51911f0f",
        upstreamAssetId = 176831997L,
        upstreamUrl = "https://github.com/notAI-tech/NudeNet/releases/download/v3.4-weights/320n.onnx",
        note = "official file, CPU only · est. ~50–100 ms"),
    Y480(480, "", 4725, "onnx_640m_480", "640m @ 480 · ONNX CPU",
        file = "640m.onnx", tiles = false, runtime = ModelRuntime.ONNX, source = Source.UPSTREAM, bytes = 103_538_690,
        sha256 = "04fe3d77980780c1f8297dc6d7f942fd5b3abe6942a188f742a85241e4f634eb",
        upstreamAssetId = 176832019L,
        upstreamUrl = "https://github.com/notAI-tech/NudeNet/releases/download/v3.4-weights/640m.onnx",
        note = "official file, CPU only · est. ~250–500 ms"),
    Y640(640, "", 8400, "onnx_640m_640", "640m @ 640 · ONNX CPU",
        file = "640m.onnx", tiles = false, runtime = ModelRuntime.ONNX, source = Source.UPSTREAM, bytes = 103_538_690,
        sha256 = "04fe3d77980780c1f8297dc6d7f942fd5b3abe6942a188f742a85241e4f634eb",
        upstreamAssetId = 176832019L,
        upstreamUrl = "https://github.com/notAI-tech/NudeNet/releases/download/v3.4-weights/640m.onnx",
        note = "official file, CPU only · est. ~400–800 ms (still images)");

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
        fun byId(id: String?): ModelSize? = entries.firstOrNull { it.id == id }
        /** Capture long side: [perInput] x model input, but never above [maxLong] (640 models). */
        fun captureLong(model: ModelSize, perInput: Float, maxLong: Float = 1280f) = kotlin.math.min(perInput * model.px, maxLong)
        /** the shipped default host is a template the user must point at their own release */
        const val HOST_PLACEHOLDER = "YOUR-GITHUB-USER"
        const val DEFAULT_BASE_URL = "https://github.com/$HOST_PLACEHOLDER/veil-models/releases/download/models-v1"
        fun hostConfigured(baseUrl: String) =
            baseUrl.startsWith("https://") && !baseUrl.contains(HOST_PLACEHOLDER) && baseUrl.length > 12
        fun bySha(sha: String): ModelSize? = entries.firstOrNull { it.sha256 == sha }
    }
}

/** A runtime-selectable backend (implemented per build flavor in Backends.kt). */
class BackendOption(val id: String, val label: String)
