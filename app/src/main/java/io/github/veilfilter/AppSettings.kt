package io.github.veilfilter

import android.content.Context

/** A few settings; toggles are read live by the running service, backend/model at start. */
object AppSettings {
    private const val PREFS = "veil"
    private const val KEY_GUARD = "scene_guard"
    private const val KEY_HUD = "debug_hud"
    private const val KEY_CSV = "csv_log"
    private const val KEY_BACKEND = "backend"
    private const val KEY_FAST = "fast256" // pre-1.5, migrated to KEY_MODEL
    private const val KEY_MODEL = "model"
    private const val KEY_A11Y = "a11y_mode"
    private const val KEY_A11Y_SEEN = "a11y_disclosure_seen"
    private const val KEY_LEAD = "extra_lead_ms"
    private const val KEY_APPEAR = "appearance_guard"
    private const val KEY_SCROLL = "scroll_guard"
    private const val KEY_GRID = "grid_boost"
    private const val KEY_PIXEL = "pixel_dp"
    private const val KEY_BASE_URL = "model_base_url"
    private const val KEY_NNAPI = "ort_nnapi"
    private const val KEY_FRESH = "fresh_wait"
    private const val KEY_FLOAT = "floating_control"
    private const val KEY_BUBBLE_RIGHT = "bubble_right"
    private const val KEY_BUBBLE_Y = "bubble_y_frac"
    /** pixel sizes the floating control cycles through (dp) */
    val PIXEL_PRESETS = intArrayOf(6, 10, 16, 24, 32)
    /** next preset strictly above [dp], wrapping to the smallest */
    fun nextPixelPreset(dp: Int): Int = PIXEL_PRESETS.firstOrNull { it > dp } ?: PIXEL_PRESETS[0]
    const val PIXEL_MIN_DP = 4
    const val PIXEL_MAX_DP = 40
    /** v1.4 blocks were ~1/7 of the box (often 20-30 dp); finer default so you can judge what is covered */
    const val PIXEL_DEFAULT_DP = 10
    const val KEY_C_EXPOSED = "censor_exposed"
    const val KEY_C_COVERED = "censor_covered"
    const val KEY_C_BELLY = "censor_belly"
    const val KEY_C_MALE_CHEST = "censor_male_chest"
    const val KEY_C_FACES = "censor_faces"
    const val KEY_C_FACE_F = "censor_face_female"
    const val KEY_C_FACE_M = "censor_face_male"

    /** settings -> class set (pure; unit-tested). Defaults: exposed on, everything else off. */
    fun censorFrom(get: (key: String, default: Boolean) -> Boolean) = CensorConfig(
        exposed = get(KEY_C_EXPOSED, true),
        covered = get(KEY_C_COVERED, false),
        belly = get(KEY_C_BELLY, false),
        maleChest = get(KEY_C_MALE_CHEST, false),
        faces = get(KEY_C_FACES, false),
        femaleFaces = get(KEY_C_FACE_F, true),
        maleFaces = get(KEY_C_FACE_M, true),
    )

    /** current class set; replaced (never mutated) on every change, read live by the pipeline */
    @Volatile var censor = CensorConfig(); private set
    fun setCensorFlag(ctx: Context, key: String, v: Boolean) {
        val p = prefs(ctx); p.edit().putBoolean(key, v).apply()
        censor = censorFrom { k, d -> if (k == key) v else p.getBoolean(k, d) }
        changed()
    }

    @Volatile var sceneGuard = true; private set
    @Volatile var debugHud = false; private set
    @Volatile var appearanceGuard = true; private set
    @Volatile var scrollGuard = false; private set
    @Volatile var gridBoost = true; private set
    @Volatile var csvLog = false; private set
    /** v1.7: fresh-frame wait (v1.6.1 behaviour, now switchable), read live */
    @Volatile var freshWait = true; private set
    /** v1.7: floating quick-action bubble while protection runs */
    @Volatile var floatingControl = true; private set
    @Volatile var bubbleRight = true; private set
    @Volatile var bubbleYFrac = 0.35f; private set
    @Volatile var backend = "auto"; private set
    @Volatile var model = ModelSize.DEFAULT; private set
    /** v1.8: optional accessibility mode (needs VeilAccessibilityService enabled too) */
    @Volatile var a11yMode = false; private set
    /** extra scroll prediction, ms (0..60), read live */
    @Volatile var extraLeadMs = 0; private set
    /** pixelation block size, dp on screen, read live */
    @Volatile var pixelDp = PIXEL_DEFAULT_DP; private set
    /** where HOSTED (TFLite) models are downloaded from; editable under Advanced */
    @Volatile var modelBaseUrl = ModelSize.DEFAULT_BASE_URL; private set
    private var loaded = false

    val modelSize: ModelSize get() = model

    fun load(ctx: Context) {
        if (loaded) return
        val p = prefs(ctx)
        sceneGuard = p.getBoolean(KEY_GUARD, true)
        debugHud = p.getBoolean(KEY_HUD, false)
        appearanceGuard = p.getBoolean(KEY_APPEAR, true)
        scrollGuard = p.getBoolean(KEY_SCROLL, false)
        gridBoost = p.getBoolean(KEY_GRID, true)
        csvLog = p.getBoolean(KEY_CSV, false)
        freshWait = p.getBoolean(KEY_FRESH, true)
        floatingControl = p.getBoolean(KEY_FLOAT, true)
        bubbleRight = p.getBoolean(KEY_BUBBLE_RIGHT, true)
        bubbleYFrac = p.getFloat(KEY_BUBBLE_Y, 0.35f).coerceIn(0f, 1f)
        backend = p.getString(KEY_BACKEND, "auto") ?: "auto"
        if (Backends.options.none { it.id == backend }) backend = "auto"
        model = ModelSize.byId(p.getString(KEY_MODEL, null))
            ?: if (p.getBoolean(KEY_FAST, false)) ModelSize.S256 else ModelSize.DEFAULT
        extraLeadMs = p.getInt(KEY_LEAD, 0).coerceIn(0, MAX_LEAD)
        censor = censorFrom { k, d -> p.getBoolean(k, d) }
        pixelDp = p.getInt(KEY_PIXEL, PIXEL_DEFAULT_DP).coerceIn(PIXEL_MIN_DP, PIXEL_MAX_DP)
        // v1.8: a URL still holding the old placeholder host becomes the real release URL
        modelBaseUrl = p.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() && !it.contains(ModelSize.HOST_PLACEHOLDER) }
            ?: ModelSize.DEFAULT_BASE_URL
        a11yMode = p.getBoolean(KEY_A11Y, false)
        loaded = true
    }

    /** tests only: forget the loaded state so [load] re-reads the prefs */
    internal fun resetForTest() { loaded = false }

    // ---- change listeners (UI thread): lets the running service react, e.g. show/hide the bubble ----
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners) l() }

    fun setFreshWait(ctx: Context, v: Boolean) { freshWait = v; prefs(ctx).edit().putBoolean(KEY_FRESH, v).apply(); changed() }
    fun setFloatingControl(ctx: Context, v: Boolean) { floatingControl = v; prefs(ctx).edit().putBoolean(KEY_FLOAT, v).apply(); changed() }
    fun setBubblePos(ctx: Context, right: Boolean, yFrac: Float) {
        bubbleRight = right; bubbleYFrac = yFrac.coerceIn(0f, 1f)
        prefs(ctx).edit().putBoolean(KEY_BUBBLE_RIGHT, right).putFloat(KEY_BUBBLE_Y, bubbleYFrac).apply()
    }

    fun setModelBaseUrl(ctx: Context, v: String) {
        modelBaseUrl = v.trim().ifEmpty { ModelSize.DEFAULT_BASE_URL }
        prefs(ctx).edit().putString(KEY_BASE_URL, modelBaseUrl).apply()
    }
    fun ortNnapi(ctx: Context) = prefs(ctx).getBoolean(KEY_NNAPI, false)
    fun setOrtNnapi(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_NNAPI, v).apply() }
    /** selected model if its file is present, else the bundled default */
    fun effectiveModel(ctx: Context): ModelSize = if (model.available(ctx)) model else ModelSize.DEFAULT

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setSceneGuard(ctx: Context, v: Boolean) { sceneGuard = v; prefs(ctx).edit().putBoolean(KEY_GUARD, v).apply(); changed() }
    fun setAppearanceGuard(ctx: Context, v: Boolean) { appearanceGuard = v; prefs(ctx).edit().putBoolean(KEY_APPEAR, v).apply(); changed() }
    fun setScrollGuard(ctx: Context, v: Boolean) { scrollGuard = v; prefs(ctx).edit().putBoolean(KEY_SCROLL, v).apply(); changed() }
    fun setGridBoost(ctx: Context, v: Boolean) { gridBoost = v; prefs(ctx).edit().putBoolean(KEY_GRID, v).apply(); changed() }
    fun setDebugHud(ctx: Context, v: Boolean) { debugHud = v; prefs(ctx).edit().putBoolean(KEY_HUD, v).apply(); changed() }
    /** the accessibility-mode disclosure screen was shown and confirmed */
    fun a11yDisclosed(ctx: Context) = prefs(ctx).getBoolean(KEY_A11Y_SEEN, false)
    fun setA11yDisclosed(ctx: Context) { prefs(ctx).edit().putBoolean(KEY_A11Y_SEEN, true).apply() }
    fun setA11yMode(ctx: Context, v: Boolean) { a11yMode = v; A11yHints.state.active = v && VeilAccessibilityService.instance != null; prefs(ctx).edit().putBoolean(KEY_A11Y, v).apply(); changed() }
    fun setCsvLog(ctx: Context, v: Boolean) { csvLog = v; prefs(ctx).edit().putBoolean(KEY_CSV, v).apply(); changed() }
    fun setBackend(ctx: Context, v: String) { backend = v; prefs(ctx).edit().putString(KEY_BACKEND, v).apply(); changed() }
    const val MAX_LEAD = 60
    fun setExtraLead(ctx: Context, v: Int) { extraLeadMs = v; prefs(ctx).edit().putInt(KEY_LEAD, v).apply(); changed() }
    fun setPixelDp(ctx: Context, v: Int) { pixelDp = v.coerceIn(PIXEL_MIN_DP, PIXEL_MAX_DP); prefs(ctx).edit().putInt(KEY_PIXEL, pixelDp).apply(); changed() }
    /** dp on screen -> capture px */
    fun blockCapturePx(dp: Int, density: Float, captureW: Int, screenW: Int): Float =
        dp * density * captureW / kotlin.math.max(1, screenW).toFloat()
    fun setModel(ctx: Context, v: ModelSize) { model = v; prefs(ctx).edit().putString(KEY_MODEL, v.id).apply(); changed() }
}

/** Running flag + listener so the activity can refresh its button. */
object ServiceState {
    @Volatile var running = false
        private set
    @Volatile var lastError: String? = null
    /** v1.7: censoring paused from the floating control (pipeline keeps running, so resume is instant) */
    @Volatile var paused = false
    private val listeners = ArrayList<() -> Unit>()

    fun set(v: Boolean) {
        running = v
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { it() }
    }

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners.remove(l) }
    /** state other than running changed (pause) */
    fun changed() = set(running)
}
