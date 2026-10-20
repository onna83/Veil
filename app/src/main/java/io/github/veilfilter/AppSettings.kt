package io.github.veilfilter

import android.content.Context

/** A few settings; toggles are read live by the running service, backend/model at start. */
object AppSettings {
    private const val PREFS = "veil"
    private const val KEY_GUARD = "scene_guard"
    private const val KEY_HUD = "debug_hud"
    private const val KEY_CSV = "csv_log"
    private const val KEY_BACKEND = "backend"
    private const val KEY_FAST = "fast256"
    private const val KEY_LEAD = "extra_lead_ms"
    private const val KEY_APPEAR = "appearance_guard"
    private const val KEY_SCROLL = "scroll_guard"
    private const val KEY_GRID = "grid_boost"
    const val KEY_C_EXPOSED = "censor_exposed"
    const val KEY_C_COVERED = "censor_covered"
    const val KEY_C_BELLY = "censor_belly"
    const val KEY_C_FACES = "censor_faces"
    const val KEY_C_FACE_F = "censor_face_female"
    const val KEY_C_FACE_M = "censor_face_male"

    /** settings -> class set (pure; unit-tested). Defaults: exposed on, everything else off. */
    fun censorFrom(get: (key: String, default: Boolean) -> Boolean) = CensorConfig(
        exposed = get(KEY_C_EXPOSED, true),
        covered = get(KEY_C_COVERED, false),
        belly = get(KEY_C_BELLY, false),
        faces = get(KEY_C_FACES, false),
        femaleFaces = get(KEY_C_FACE_F, true),
        maleFaces = get(KEY_C_FACE_M, true),
    )

    /** current class set; replaced (never mutated) on every change, read live by the pipeline */
    @Volatile var censor = CensorConfig(); private set
    fun setCensorFlag(ctx: Context, key: String, v: Boolean) {
        val p = prefs(ctx); p.edit().putBoolean(key, v).apply()
        censor = censorFrom { k, d -> if (k == key) v else p.getBoolean(k, d) }
    }

    @Volatile var sceneGuard = true; private set
    @Volatile var debugHud = false; private set
    @Volatile var appearanceGuard = true; private set
    @Volatile var scrollGuard = false; private set
    @Volatile var gridBoost = true; private set
    @Volatile var csvLog = false; private set
    @Volatile var backend = "auto"; private set
    @Volatile var fast256 = false; private set
    /** extra scroll prediction, ms (0..60), read live */
    @Volatile var extraLeadMs = 0; private set
    private var loaded = false

    val modelSize: ModelSize get() = if (fast256) ModelSize.S256 else ModelSize.S320

    fun load(ctx: Context) {
        if (loaded) return
        val p = prefs(ctx)
        sceneGuard = p.getBoolean(KEY_GUARD, true)
        debugHud = p.getBoolean(KEY_HUD, false)
        appearanceGuard = p.getBoolean(KEY_APPEAR, true)
        scrollGuard = p.getBoolean(KEY_SCROLL, false)
        gridBoost = p.getBoolean(KEY_GRID, true)
        csvLog = p.getBoolean(KEY_CSV, false)
        backend = p.getString(KEY_BACKEND, "auto") ?: "auto"
        if (Backends.options.none { it.id == backend }) backend = "auto"
        fast256 = p.getBoolean(KEY_FAST, false)
        extraLeadMs = p.getInt(KEY_LEAD, 0).coerceIn(0, MAX_LEAD)
        censor = censorFrom { k, d -> p.getBoolean(k, d) }
        loaded = true
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setSceneGuard(ctx: Context, v: Boolean) { sceneGuard = v; prefs(ctx).edit().putBoolean(KEY_GUARD, v).apply() }
    fun setAppearanceGuard(ctx: Context, v: Boolean) { appearanceGuard = v; prefs(ctx).edit().putBoolean(KEY_APPEAR, v).apply() }
    fun setScrollGuard(ctx: Context, v: Boolean) { scrollGuard = v; prefs(ctx).edit().putBoolean(KEY_SCROLL, v).apply() }
    fun setGridBoost(ctx: Context, v: Boolean) { gridBoost = v; prefs(ctx).edit().putBoolean(KEY_GRID, v).apply() }
    fun setDebugHud(ctx: Context, v: Boolean) { debugHud = v; prefs(ctx).edit().putBoolean(KEY_HUD, v).apply() }
    fun setCsvLog(ctx: Context, v: Boolean) { csvLog = v; prefs(ctx).edit().putBoolean(KEY_CSV, v).apply() }
    fun setBackend(ctx: Context, v: String) { backend = v; prefs(ctx).edit().putString(KEY_BACKEND, v).apply() }
    const val MAX_LEAD = 60
    fun setExtraLead(ctx: Context, v: Int) { extraLeadMs = v; prefs(ctx).edit().putInt(KEY_LEAD, v).apply() }
    fun setFast256(ctx: Context, v: Boolean) { fast256 = v; prefs(ctx).edit().putBoolean(KEY_FAST, v).apply() }
}

/** Running flag + listener so the activity can refresh its button. */
object ServiceState {
    @Volatile var running = false
        private set
    @Volatile var lastError: String? = null
    private val listeners = ArrayList<() -> Unit>()

    fun set(v: Boolean) {
        running = v
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { it() }
    }

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners.remove(l) }
}
