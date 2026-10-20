package io.github.veilfilter

/**
 * Which NudeNet classes are censored, and with which thresholds. Immutable;
 * the pipeline swaps a new instance in live when settings change.
 *
 * Thresholds (tracker start = first shown, keep = refresh an existing track):
 *  - exposed parts keep the v1 safety bias (start 0.30 / keep 0.20);
 *  - covered/clothed classes and belly fire on lots of ordinary clothed
 *    people at low scores, so they need more evidence (0.40 / 0.25) to avoid
 *    flickering boxes;
 *  - faces score high and stably on NudeNet, 0.35 / 0.20.
 * The fast-motion keep threshold scales with keep (0.12 for exposed).
 */
class CensorConfig(
    val exposed: Boolean = true,
    val covered: Boolean = false,
    val belly: Boolean = false,
    /** MALE_BREAST_EXPOSED: never part of "exposed"; separate, default off */
    val maleChest: Boolean = false,
    val faces: Boolean = false,
    val femaleFaces: Boolean = true,
    val maleFaces: Boolean = true,
) {
    val mask = BooleanArray(Labels.NUM_CLASSES)
    val start = FloatArray(Labels.NUM_CLASSES) { 1f }
    val keep = FloatArray(Labels.NUM_CLASSES) { 1f }
    val fastKeep = FloatArray(Labels.NUM_CLASSES) { 1f }

    init {
        fun on(names: Collection<String>, s: Float, k: Float) {
            for (n in names) {
                val i = Labels.index(n)
                mask[i] = true; start[i] = s; keep[i] = k; fastKeep[i] = k * 0.6f
            }
        }
        if (exposed) on(EXPOSED, 0.30f, 0.20f)
        if (covered) on(COVERED, 0.40f, 0.25f)
        if (belly) on(BELLY, 0.40f, 0.25f)
        if (maleChest) on(MALE_CHEST, 0.40f, 0.25f)
        if (faces && femaleFaces) on(listOf("FACE_FEMALE"), 0.35f, 0.20f)
        if (faces && maleFaces) on(listOf("FACE_MALE"), 0.35f, 0.20f)
    }

    /** disabled face / male-chest classes veto anchors they dominate (see YoloDecoder) */
    val veto = BooleanArray(Labels.NUM_CLASSES).also { v ->
        for (n in VETO_CANDIDATES) { val i = Labels.index(n); v[i] = !mask[i] }
    }

    val any: Boolean get() = mask.any { it }
    /** the skin-colour appearance guard only makes sense for bare-skin classes */
    val skinGuard: Boolean get() = exposed || belly
    /** lowest score worth decoding for any enabled class */
    val decodeThreshold: Float get() = fastKeep.min().coerceAtMost(1f)

    /** short HUD label, e.g. "exposed+covered+faces(F,M)" */
    fun label(): String {
        val p = ArrayList<String>()
        if (exposed) p += "exposed"
        if (covered) p += "covered"
        if (belly) p += "belly"
        if (maleChest) p += "male chest"
        if (faces && (femaleFaces || maleFaces)) {
            p += "faces(" + listOfNotNull("F".takeIf { femaleFaces }, "M".takeIf { maleFaces }).joinToString(",") + ")"
        }
        return if (p.isEmpty()) "nothing" else p.joinToString("+")
    }

    override fun equals(other: Any?) = other is CensorConfig && mask.contentEquals(other.mask)
    override fun hashCode() = mask.contentHashCode()

    companion object {
        val EXPOSED = listOf("FEMALE_BREAST_EXPOSED", "FEMALE_GENITALIA_EXPOSED", "MALE_GENITALIA_EXPOSED",
            "BUTTOCKS_EXPOSED", "ANUS_EXPOSED")
        val COVERED = listOf("FEMALE_BREAST_COVERED", "FEMALE_GENITALIA_COVERED", "BUTTOCKS_COVERED", "ANUS_COVERED")
        val BELLY = listOf("BELLY_EXPOSED")
        val MALE_CHEST = listOf("MALE_BREAST_EXPOSED")
        val VETO_CANDIDATES = listOf("FACE_FEMALE", "FACE_MALE", "MALE_BREAST_EXPOSED")
    }
}
