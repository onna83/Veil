package io.github.veilfilter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CensorClassesTest {
    private fun enabled(c: CensorConfig) = Labels.NAMES.filterIndexed { i, _ -> c.mask[i] }.toSet()

    @Test fun exactNudeNetLabelNamesExist() {
        for (n in CensorConfig.EXPOSED + CensorConfig.COVERED + CensorConfig.BELLY + listOf("FACE_FEMALE", "FACE_MALE"))
            assertTrue(n, n in Labels.NAMES)
        assertEquals(16, Labels.index("FEMALE_BREAST_COVERED"))
        assertEquals(0, Labels.index("FEMALE_GENITALIA_COVERED"))
        assertEquals(17, Labels.index("BUTTOCKS_COVERED"))
        assertEquals(15, Labels.index("ANUS_COVERED"))
        assertEquals(1, Labels.index("FACE_FEMALE"))
        assertEquals(12, Labels.index("FACE_MALE"))
    }

    @Test fun defaultIsTheV1ExposedSet() {
        val c = CensorConfig()
        assertEquals(Labels.CENSORED_NAMES, enabled(c))
        assertArrayEquals(Labels.mask(Labels.CENSORED_NAMES), c.mask)
        assertEquals("exposed", c.label())
        assertTrue(c.skinGuard)
    }

    @Test fun groupsMapToClasses() {
        assertEquals(Labels.CENSORED_NAMES + CensorConfig.COVERED, enabled(CensorConfig(covered = true)))
        assertEquals(setOf("BELLY_EXPOSED"), enabled(CensorConfig(exposed = false, belly = true)))
        // sub-options do nothing while Faces is off
        assertEquals(Labels.CENSORED_NAMES, enabled(CensorConfig(faces = false, femaleFaces = true, maleFaces = true)))
        assertEquals(setOf("FACE_FEMALE", "FACE_MALE"), enabled(CensorConfig(exposed = false, faces = true)))
        assertEquals(setOf("FACE_MALE"), enabled(CensorConfig(exposed = false, faces = true, femaleFaces = false)))
        val none = CensorConfig(exposed = false)
        assertFalse(none.any); assertFalse(none.skinGuard); assertEquals("nothing", none.label())
        assertEquals("exposed+covered+faces(F,M)", CensorConfig(covered = true, faces = true).label())
        assertFalse("faces alone don't arm the skin guard", CensorConfig(exposed = false, faces = true).skinGuard)
        // never censored: feet, armpits, belly covered, male breast
        val all = enabled(CensorConfig(covered = true, belly = true, faces = true))
        for (n in listOf("FEET_EXPOSED", "FEET_COVERED", "ARMPITS_EXPOSED", "ARMPITS_COVERED", "BELLY_COVERED", "MALE_BREAST_EXPOSED"))
            assertFalse(n, n in all)
    }

    @Test fun thresholdsKeepSafetyBiasForExposed() {
        val c = CensorConfig(covered = true, faces = true)
        val ex = Labels.index("FEMALE_BREAST_EXPOSED"); val cov = Labels.index("FEMALE_BREAST_COVERED"); val f = Labels.index("FACE_MALE")
        assertEquals(0.30f, c.start[ex], 0f); assertEquals(0.20f, c.keep[ex], 0f); assertEquals(0.12f, c.fastKeep[ex], 1e-6f)
        assertTrue(c.start[cov] > c.start[ex] && c.keep[cov] > c.keep[ex])
        assertEquals(0.35f, c.start[f], 0f)
        assertEquals(0.12f, c.decodeThreshold, 1e-6f)
    }

    @Test fun settingsMapping() {
        val prefs = HashMap<String, Boolean>()
        val get = { k: String, d: Boolean -> prefs[k] ?: d }
        assertEquals(CensorConfig(), AppSettings.censorFrom(get))
        prefs[AppSettings.KEY_C_FACES] = true; prefs[AppSettings.KEY_C_FACE_M] = false
        assertEquals(Labels.CENSORED_NAMES + "FACE_FEMALE", enabled(AppSettings.censorFrom(get)))
        prefs[AppSettings.KEY_C_EXPOSED] = false; prefs[AppSettings.KEY_C_COVERED] = true
        assertEquals(CensorConfig.COVERED.toSet() + "FACE_FEMALE", enabled(AppSettings.censorFrom(get)))
    }

    private fun one(s: Float, cls: Int) = arrayOf<Any>(floatArrayOf(100f), floatArrayOf(100f), floatArrayOf(200f), floatArrayOf(200f), floatArrayOf(s), intArrayOf(cls))
    private fun Tracker.feed(d: Array<*>, now: Long) = update(1, d[0] as FloatArray, d[1] as FloatArray, d[2] as FloatArray,
        d[3] as FloatArray, d[4] as FloatArray, d[5] as IntArray, now, false)

    @Test fun trackerUsesPerClassStartThresholds() {
        val c = CensorConfig(covered = true)
        val tr = Tracker(); tr.applyClasses(c.mask, c.start, c.keep, c.fastKeep)
        tr.feed(one(0.35f, Labels.index("BUTTOCKS_COVERED")), 0)
        assertEquals("covered needs 0.40 to start", 0, tr.tracks.size)
        tr.feed(one(0.35f, Labels.index("BUTTOCKS_EXPOSED")), 20)
        assertEquals(1, tr.tracks.size)
    }

    @Test fun disablingAGroupDropsItsTracksLive() {
        val on = CensorConfig(faces = true)
        val tr = Tracker(); tr.applyClasses(on.mask, on.start, on.keep, on.fastKeep)
        tr.feed(one(0.9f, Labels.index("FACE_FEMALE")), 0)
        assertEquals(1, tr.tracks.size)
        val off = CensorConfig()
        tr.applyClasses(off.mask, off.start, off.keep, off.fastKeep)
        assertEquals(0, tr.tracks.size)
    }

    @Test fun decoderRespectsTheMask() {
        val n = 4; val out = FloatArray((4 + Labels.NUM_CLASSES) * n)
        // anchor 0: a face with a high score at (50,50) 40x40
        out[0] = 50f; out[n] = 50f; out[2 * n] = 40f; out[3 * n] = 40f
        out[(4 + Labels.index("FACE_FEMALE")) * n] = 0.9f
        val dec = YoloDecoder(numAnchors = n)
        val def = CensorConfig()
        assertEquals(0, dec.decode(out, 1f, 1f, def.decodeThreshold, def.mask, true, 0.45f, 320f, 320f))
        val faces = CensorConfig(faces = true)
        assertEquals(1, dec.decode(out, 1f, 1f, faces.decodeThreshold, faces.mask, true, 0.45f, 320f, 320f))
        assertEquals(Labels.index("FACE_FEMALE"), dec.cls[0])
    }
}
