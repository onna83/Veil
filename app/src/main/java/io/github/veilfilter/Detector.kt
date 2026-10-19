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

/** Available model inputs. 256 is experimental: faster, measurably less confident. */
enum class ModelSize(val px: Int, val asset: String, val anchors: Int) {
    S320(320, "nudenet_320n.tflite", 2100),
    S256(256, "nudenet_320n_at256.tflite", 1344),
}

/** A runtime-selectable backend (implemented per build flavor in Backends.kt). */
class BackendOption(val id: String, val label: String)
