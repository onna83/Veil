package io.github.veilfilter

import android.content.Context
import android.graphics.Rect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** v1.7: settings carry-over, compact HUD, floating control logic, own-UI masking. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V17Test {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Test fun existingSettingsCarryOver() {
        // prefs as v1.6.1 wrote them (same file, same keys)
        ctx.getSharedPreferences("veil", Context.MODE_PRIVATE).edit()
            .putBoolean("scene_guard", false).putBoolean("debug_hud", true).putBoolean("csv_log", true)
            .putString("backend", "cpu").putString("model", "320n_256").putInt("extra_lead_ms", 25)
            .putBoolean("appearance_guard", false).putBoolean("scroll_guard", true).putBoolean("grid_boost", false)
            .putInt("pixel_dp", 18).putBoolean("censor_covered", true).putBoolean("censor_faces", true)
            .putBoolean("censor_face_male", false).putBoolean("ort_nnapi", true)
            .commit()
        AppSettings.resetForTest(); AppSettings.load(ctx)
        assertFalse(AppSettings.sceneGuard); assertTrue(AppSettings.debugHud); assertTrue(AppSettings.csvLog)
        assertEquals("cpu", AppSettings.backend); assertEquals("320n_256", AppSettings.model.id)
        assertEquals(25, AppSettings.extraLeadMs); assertFalse(AppSettings.appearanceGuard)
        assertTrue(AppSettings.scrollGuard); assertFalse(AppSettings.gridBoost); assertEquals(18, AppSettings.pixelDp)
        assertTrue(AppSettings.censor.covered); assertTrue(AppSettings.censor.faces)
        assertTrue(AppSettings.censor.femaleFaces); assertFalse(AppSettings.censor.maleFaces)
        assertTrue(AppSettings.ortNnapi(ctx))
        // new v1.7 keys default sensibly
        assertTrue(AppSettings.freshWait); assertTrue(AppSettings.floatingControl)
    }

    @Test fun hudDefaultsOffButKeepsUserValue() {
        ctx.getSharedPreferences("veil", Context.MODE_PRIVATE).edit().clear().commit()
        AppSettings.resetForTest(); AppSettings.load(ctx)
        assertFalse(AppSettings.debugHud)
        AppSettings.setDebugHud(ctx, true)
        AppSettings.resetForTest(); AppSettings.load(ctx)
        assertTrue(AppSettings.debugHud)
    }

    @Test fun legacy256SwitchStillMigrates() {
        ctx.getSharedPreferences("veil", Context.MODE_PRIVATE).edit().clear().putBoolean("fast256", true).commit()
        AppSettings.resetForTest(); AppSettings.load(ctx)
        assertEquals(ModelSize.S256, AppSettings.model)
    }

    @Test fun listenersFireOnChange() {
        AppSettings.resetForTest(); AppSettings.load(ctx)
        var n = 0
        val l: () -> Unit = { n++ }
        AppSettings.addListener(l)
        AppSettings.setFloatingControl(ctx, false); AppSettings.setFreshWait(ctx, false)
        AppSettings.setCensorFlag(ctx, AppSettings.KEY_C_FACES, true); AppSettings.setPixelDp(ctx, 12)
        AppSettings.removeListener(l)
        AppSettings.setFloatingControl(ctx, true)
        assertEquals(4, n)
        AppSettings.setFreshWait(ctx, true); AppSettings.setCensorFlag(ctx, AppSettings.KEY_C_FACES, false)
    }

    @Test fun compactHudIsFirstLine() {
        val c = Pipeline.hudCompact("320n_320", 42.8f, 103.4f)
        assertEquals("320n_320 · det 43 fps · e2e 103 ms", c)
        assertEquals(c, OverlayRenderer.hudCollapsed("$c\nmore\nlines"))
        assertTrue(Pipeline.hudCompact("x", 1f, 2f, paused = true).endsWith("PAUSED"))
        assertEquals("Veil: starting…", OverlayRenderer.hudCollapsed("Veil: starting…"))
    }

    @Test fun pixelPresetsCycle() {
        assertEquals(10, AppSettings.nextPixelPreset(6))
        assertEquals(16, AppSettings.nextPixelPreset(10))
        assertEquals(16, AppSettings.nextPixelPreset(12)) // custom value -> next preset up
        assertEquals(6, AppSettings.nextPixelPreset(32))
        assertEquals(6, AppSettings.nextPixelPreset(40))
    }

    @Test fun bubbleSnapsToNearestEdge() {
        val area = Rect(10, 100, 1070, 2200)
        val (r1, y1) = FloatingControl.snap(900f, 100f, area, 150)
        assertTrue(r1); assertEquals(0f, y1, 1e-4f)
        val (r2, y2) = FloatingControl.snap(200f, 2200f, area, 150)
        assertFalse(r2); assertEquals(1f, y2, 1e-4f)
        val (_, y3) = FloatingControl.snap(200f, 100f + 0.5f * (2100 - 150), area, 150)
        assertEquals(0.5f, y3, 1e-3f)
    }

    @Test fun quickMenuHasAllActions() {
        val items = FloatingControl.quickItems(paused = true, pixelDp = 16, faces = true, covered = false, hud = false, modelLabel = "320n_320")
        val acts = items.map { it.action }.toSet()
        for (a in listOf(QuickAction.PAUSE, QuickAction.MODEL, QuickAction.PIXEL, QuickAction.FACES, QuickAction.COVERED, QuickAction.HUD, QuickAction.STOP))
            assertTrue("$a", a in acts)
        assertEquals("Resume", items.first { it.action == QuickAction.PAUSE }.label)
        assertTrue(items.first { it.action == QuickAction.FACES }.active)
        assertFalse(items.first { it.action == QuickAction.COVERED }.active)
        assertEquals("Pixel 16", items.first { it.action == QuickAction.PIXEL }.label)
    }

    @Test fun switchableModelsAreOnlyPresentOnes() {
        val ms = FloatingControl.switchableModels(ctx)
        assertTrue(ModelSize.DEFAULT in ms) // bundled
        assertTrue(ms.all { it.available(ctx) })
    }

    @Test fun ownUiRectsMapToCaptureAndMaskDetections() {
        // screen 1080x2340 shown in a 540x1170 capture
        val r = OverlayRenderer.screenToCapture(listOf(Rect(920, 700, 1076, 856)), Rect(0, 0, 1080, 2340), 540, 1170)
        assertArrayEquals(floatArrayOf(460f, 350f, 538f, 428f), r, 0.01f)
        // a detection on the bubble is dropped, one beside it is kept
        assertTrue(Pipeline.mostlyInside(465f, 355f, 530f, 420f, r))
        assertFalse(Pipeline.mostlyInside(300f, 355f, 470f, 420f, r))
        assertFalse(Pipeline.mostlyInside(0f, 0f, 10f, 10f, FloatArray(0)))
        // off-screen / degenerate rects are dropped
        assertEquals(0, OverlayRenderer.screenToCapture(listOf(Rect(-50, -50, -10, -10)), Rect(0, 0, 1080, 2340), 540, 1170).size)
    }
}
