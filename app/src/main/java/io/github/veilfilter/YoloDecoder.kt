package io.github.veilfilter

/**
 * Decodes the YOLOv8 head output of NudeNet 320n and runs NMS.
 *
 * Output layout is channel-major `[1, 4 + C, N]`: for anchor i,
 * cx = out[0*N+i], cy = out[1*N+i], w = out[2*N+i], h = out[3*N+i],
 * score of class c = out[(4+c)*N+i]. Box values are in model-input pixels
 * (0..inputSize) for the ONNX export; pass [coordScale] = inputSize when the
 * backend outputs normalised (0..1) boxes (Ultralytics TFLite export does).
 *
 * All buffers are preallocated: [decode] does not allocate.
 */
class YoloDecoder(
    private val numAnchors: Int = 2100,
    private val numClasses: Int = Labels.NUM_CLASSES,
    private val maxCandidates: Int = 300,
    val maxDetections: Int = 32,
) {
    // Candidate storage (structure-of-arrays).
    private val cx1 = FloatArray(maxCandidates)
    private val cy1 = FloatArray(maxCandidates)
    private val cx2 = FloatArray(maxCandidates)
    private val cy2 = FloatArray(maxCandidates)
    private val cScore = FloatArray(maxCandidates)
    private val cClass = IntArray(maxCandidates)
    private val cAlive = BooleanArray(maxCandidates)

    // Results.
    val x1 = FloatArray(maxDetections)
    val y1 = FloatArray(maxDetections)
    val x2 = FloatArray(maxDetections)
    val y2 = FloatArray(maxDetections)
    val score = FloatArray(maxDetections)
    val cls = IntArray(maxDetections)
    var count = 0
        private set

    /**
     * @param out raw output, length >= (4 + numClasses) * numAnchors
     * @param coordScale multiply raw box values by this (1 for pixel outputs)
     * @param outScale multiply resulting model-input coordinates by this to get
     *   the caller's coordinate space (for NudeNet's top-left letterbox:
     *   paddedSide / inputSize)
     * @param threshold minimum score
     * @param classMask only these classes are considered; the score of an
     *   anchor is its max over enabled classes (safety-biased: a box whose
     *   argmax is a "covered" class but which also scores high on an
     *   "exposed" class is still reported)
     * @param classAware true = suppress only boxes of the same class
     * @param iouThreshold NMS IoU threshold
     * @param maxX,maxY clip boxes to [0,maxX]x[0,maxY] in output space
     * @return number of detections, results in x1/y1/x2/y2/score/cls
     */
    fun decode(
        out: FloatArray,
        coordScale: Float,
        outScale: Float,
        threshold: Float,
        classMask: BooleanArray,
        classAware: Boolean,
        iouThreshold: Float,
        maxX: Float,
        maxY: Float,
        /**
         * Disabled classes that veto an anchor: if one of them scores at least
         * [VETO_RATIO] x the best enabled class there, the anchor is that (disabled)
         * thing, e.g. a face, not a low-scoring enabled class. null = off.
         */
        veto: BooleanArray? = null,
    ): Int {
        val n = numAnchors
        var k = 0
        var minIdx = -1 // index of lowest-scoring candidate when full
        for (i in 0 until n) {
            var best = -1f
            var bestC = -1
            var c = 0
            while (c < numClasses) {
                if (classMask[c]) {
                    val s = out[(4 + c) * n + i]
                    if (s > best) { best = s; bestC = c }
                }
                c++
            }
            if (best < threshold || bestC < 0) continue
            if (veto != null) {
                var vb = 0f
                for (vc in 0 until numClasses) if (veto[vc]) { val s = out[(4 + vc) * n + i]; if (s > vb) vb = s }
                if (vb >= VETO_RATIO * best) continue
            }
            val slot: Int
            if (k < maxCandidates) {
                slot = k++
            } else {
                if (best <= cScore[minIdx]) continue
                slot = minIdx
            }
            val f = coordScale * outScale
            val bx = out[i] * f
            val by = out[n + i] * f
            val bw = out[2 * n + i] * f
            val bh = out[3 * n + i] * f
            cx1[slot] = clamp(bx - bw * 0.5f, 0f, maxX)
            cy1[slot] = clamp(by - bh * 0.5f, 0f, maxY)
            cx2[slot] = clamp(bx + bw * 0.5f, 0f, maxX)
            cy2[slot] = clamp(by + bh * 0.5f, 0f, maxY)
            cScore[slot] = best
            cClass[slot] = bestC
            if (k == maxCandidates) minIdx = argMin(cScore, k)
        }
        for (j in 0 until k) cAlive[j] = cx2[j] > cx1[j] && cy2[j] > cy1[j]

        // Greedy NMS: repeatedly take best alive candidate.
        var m = 0
        while (m < maxDetections) {
            var bi = -1
            var bs = -1f
            for (j in 0 until k) if (cAlive[j] && cScore[j] > bs) { bs = cScore[j]; bi = j }
            if (bi < 0) break
            cAlive[bi] = false
            x1[m] = cx1[bi]; y1[m] = cy1[bi]; x2[m] = cx2[bi]; y2[m] = cy2[bi]
            score[m] = bs; cls[m] = cClass[bi]
            m++
            for (j in 0 until k) {
                if (!cAlive[j]) continue
                if (classAware && cClass[j] != cClass[bi]) continue
                if (iou(cx1[bi], cy1[bi], cx2[bi], cy2[bi], cx1[j], cy1[j], cx2[j], cy2[j]) > iouThreshold) {
                    cAlive[j] = false
                }
            }
        }
        count = m
        return m
    }

    private fun argMin(a: FloatArray, len: Int): Int {
        var mi = 0
        for (j in 1 until len) if (a[j] < a[mi]) mi = j
        return mi
    }

    companion object {
        const val VETO_RATIO = 2f
        fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v

        fun iou(ax1: Float, ay1: Float, ax2: Float, ay2: Float, bx1: Float, by1: Float, bx2: Float, by2: Float): Float {
            val iw = minOf(ax2, bx2) - maxOf(ax1, bx1)
            val ih = minOf(ay2, by2) - maxOf(ay1, by1)
            if (iw <= 0f || ih <= 0f) return 0f
            val inter = iw * ih
            val union = (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - inter
            return if (union <= 0f) 0f else inter / union
        }
    }
}
