package io.github.veilfilter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CutGateTest {
    @Test fun neverDuringScroll() = assertFalse(CutGate.fire(true, true, true, 1000, 1000))
    @Test fun notJustAfterScroll() {
        assertFalse(CutGate.fire(true, true, false, 1100, 1000))
        assertFalse(CutGate.fire(true, true, false, 1149, 1000))
        assertTrue(CutGate.fire(true, true, false, 1150, 1000))
    }
    @Test fun firesOnStillVideoCut() = assertTrue(CutGate.fire(true, true, false, 10_000, Long.MIN_VALUE / 2))
    @Test fun disabledOrNoCut() {
        assertFalse(CutGate.fire(false, true, false, 10_000, 0))
        assertFalse(CutGate.fire(true, false, false, 10_000, 0))
    }
    @Test fun releasedByFirstPostCutResultOnly() {
        assertFalse(CutGate.releases(41, 42))
        assertTrue(CutGate.releases(42, 42))
        assertTrue(CutGate.releases(43, 42))
    }
}
