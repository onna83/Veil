package io.github.veilfilter

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Runs the jitter replay (writes app/build/jitter/report.md) and guards the v1.8.1 gains. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JitterSimTest {
    @Test fun replay() {
        val text = JitterSim.report()
        File("build/jitter").mkdirs()
        File("build/jitter/report.md").writeText(text)
        println(text)
        val old = JitterSim.runAll(MotionTuning.V18, 6)
        val new = JitterSim.runAll(MotionTuning.NORMAL, 6)
        for (k in JitterSim.Kind.entries) {
            val o = old.getValue(k); val n = new.getValue(k)
            assertTrue("$k jitter ${n.jitRms} vs ${o.jitRms}", n.jitRms <= o.jitRms * 1.02 + 0.05)
            assertTrue("$k leaks ${n.leakPct} vs ${o.leakPct}", n.leakPct <= o.leakPct + 0.5)
            assertTrue("$k lag ${n.lagMs} vs ${o.lagMs}", n.lagMs <= o.lagMs + 3.0)
        }
    }
}
