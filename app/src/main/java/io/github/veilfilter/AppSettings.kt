package io.github.veilfilter

import android.content.Context

/** Two booleans; read live by the running service. */
object AppSettings {
    private const val PREFS = "veil"
    private const val KEY_GUARD = "scene_guard"
    private const val KEY_HUD = "debug_hud"

    @Volatile var sceneGuard = true
        private set
    @Volatile var debugHud = false
        private set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sceneGuard = p.getBoolean(KEY_GUARD, true)
        debugHud = p.getBoolean(KEY_HUD, false)
        loaded = true
    }

    fun setSceneGuard(ctx: Context, v: Boolean) {
        sceneGuard = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_GUARD, v).apply()
    }

    fun setDebugHud(ctx: Context, v: Boolean) {
        debugHud = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HUD, v).apply()
    }
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
