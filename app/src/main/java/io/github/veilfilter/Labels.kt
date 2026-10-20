package io.github.veilfilter

/**
 * NudeNet v3 class labels, in model output order.
 * Verified against NudeNet's nudenet/nudenet.py (`__labels`) and the `names`
 * metadata embedded in 320n.onnx.
 */
object Labels {
    val NAMES = arrayOf(
        "FEMALE_GENITALIA_COVERED", // 0
        "FACE_FEMALE",              // 1
        "BUTTOCKS_EXPOSED",         // 2
        "FEMALE_BREAST_EXPOSED",    // 3
        "FEMALE_GENITALIA_EXPOSED", // 4
        "MALE_BREAST_EXPOSED",      // 5
        "ANUS_EXPOSED",             // 6
        "FEET_EXPOSED",             // 7
        "BELLY_COVERED",            // 8
        "FEET_COVERED",             // 9
        "ARMPITS_COVERED",          // 10
        "ARMPITS_EXPOSED",          // 11
        "FACE_MALE",                // 12
        "BELLY_EXPOSED",            // 13
        "MALE_GENITALIA_EXPOSED",   // 14
        "ANUS_COVERED",             // 15
        "FEMALE_BREAST_COVERED",    // 16
        "BUTTOCKS_COVERED",         // 17
    )

    const val NUM_CLASSES = 18

    /** Classes censored in v1 (hardcoded). */
    val CENSORED_NAMES = setOf(
        "FEMALE_BREAST_EXPOSED",
        "FEMALE_GENITALIA_EXPOSED",
        "MALE_GENITALIA_EXPOSED",
        "BUTTOCKS_EXPOSED",
        "ANUS_EXPOSED",
    )

    fun mask(names: Set<String>): BooleanArray =
        BooleanArray(NUM_CLASSES) { NAMES[it] in names }

    fun index(name: String): Int = NAMES.indexOf(name).also { require(it >= 0) { "unknown label $name" } }

    fun allMask(): BooleanArray = BooleanArray(NUM_CLASSES) { true }
}
