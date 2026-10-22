package io.github.veilfilter

/**
 * v1.8 accessibility-mode hints (written by [VeilAccessibilityService] on the
 * main thread, read by the capture thread). Only event TYPES, times and the
 * package / class name of window-state events are used; no window content
 * (text, view tree) is ever read.
 *
 *  - scroll: the foreground app reported TYPE_VIEW_SCROLLED. While this is
 *    recent the pipeline keeps extrapolating with the page velocity, never
 *    treats the motion as a scene cut and never covers anything
 *    pre-emptively (only real detections + tracked boxes are shown).
 *  - content change: a different activity / app took the screen
 *    (TYPE_WINDOW_STATE_CHANGED with a new package/class, or another app
 *    window became the foreground window). Boxes from the old screen that
 *    the next full detector pass does not find again are dropped at once
 *    instead of ageing out.
 *
 * Times are ms on the System.nanoTime() clock (the service converts).
 */
class HintState {
    @Volatile var active = false
    @Volatile var lastScrollMs = Long.MIN_VALUE / 2
        private set
    @Volatile var contentSeq = 0
        private set
    @Volatile var lastContentMs = Long.MIN_VALUE / 2
        private set
    @Volatile var scrollEvents = 0L
        private set
    private var lastKey: String? = null
    private var lastWindowId = Int.MIN_VALUE

    fun onScroll(tMs: Long) {
        if (tMs > lastScrollMs) lastScrollMs = tMs
        scrollEvents++
    }

    /** @return true if this counts as a content change */
    fun onWindowState(pkg: String?, cls: String?, tMs: Long): Boolean {
        val key = "${pkg ?: "?"}/${cls ?: "?"}"
        if (key == lastKey) return false
        val first = lastKey == null
        lastKey = key
        if (first) return false
        contentSeq++
        lastContentMs = tMs
        return true
    }

    /** the foreground full-screen app window changed (id from AccessibilityWindowInfo) */
    fun onForegroundWindow(id: Int, tMs: Long): Boolean {
        if (id == lastWindowId) return false
        val first = lastWindowId == Int.MIN_VALUE
        lastWindowId = id
        if (first) return false
        contentSeq++
        lastContentMs = tMs
        return true
    }

    fun scrolling(nowMs: Long, windowMs: Long = SCROLL_HINT_MS) = active && nowMs - lastScrollMs <= windowMs

    fun reset() {
        lastScrollMs = Long.MIN_VALUE / 2; lastKey = null; lastWindowId = Int.MIN_VALUE
    }

    companion object {
        /** a scroll event keeps "scrolling" true this long (apps report every few frames) */
        const val SCROLL_HINT_MS = 250L
    }
}

/** process-wide hint state shared by the service and the pipeline */
object A11yHints {
    val state = HintState()
}
