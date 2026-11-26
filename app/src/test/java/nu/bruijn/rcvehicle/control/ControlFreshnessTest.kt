package nu.bruijn.rcvehicle.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlFreshnessTest {
    // Controller clock runs 5 hours "ahead" of the vehicle's - offset must not matter.
    private val offset = -18_000_000L
    private fun ControlFreshness.frame(sentAt: Long, delay: Long, throttle: Float = 0.5f) =
        accept(sentAt, sentAt + offset + delay, throttle)

    @Test fun steadyDelayWithJitterPasses() {
        val f = ControlFreshness()
        for (i in 0 until 1000) assertTrue(f.frame(100_000L + i * 30, 80L + (i % 7) * 40))
    }

    @Test fun delayJumpIsDroppedAndRecovers() {
        val f = ControlFreshness()
        var t = 100_000L
        repeat(100) { assertTrue(f.frame(t, 50)); t += 30 }
        assertFalse(f.frame(t, 1500)); t += 30
        assertTrue(f.lagging)
        assertTrue(f.frame(t, 60))
        assertFalse(f.lagging)
    }

    @Test fun reorderedAndDuplicateDropped() {
        val f = ControlFreshness()
        assertTrue(f.frame(1000, 50))
        assertFalse(f.frame(1000, 50))
        assertFalse(f.frame(970, 40))
    }

    @Test fun permanentlySlowerPathNeedsNeutralThrottleToResume() {
        val f = ControlFreshness()
        var t = 100_000L
        repeat(100) { assertTrue(f.frame(t, 50)); t += 30 }
        // Lag jumps to 1 s and stays: held throttle never resumes, not even after RESYNC_AFTER_MS.
        repeat(200) { assertFalse(f.frame(t, 1050)); t += 30 }
        // Driver releases throttle: accepted at the new lag, and driving continues there.
        assertTrue(f.frame(t, 1050, throttle = 0f)); t += 30
        repeat(100) { assertTrue(f.frame(t, 1060)); t += 30 }
    }

    @Test fun neutralDuringShortSpikeIsStillDropped() {
        val f = ControlFreshness()
        var t = 100_000L
        repeat(100) { assertTrue(f.frame(t, 50)); t += 30 }
        assertFalse(f.frame(t, 1000, throttle = 0f)) // < RESYNC_AFTER_MS of lag: no resync yet
    }

    @Test fun growingQueueStaysStopped() {
        val f = ControlFreshness()
        var t = 100_000L
        repeat(100) { assertTrue(f.frame(t, 50)); t += 30 }
        var delay = 50L
        var accepted = 0
        repeat(1000) { delay += 10; if (f.frame(t, delay)) accepted++; t += 30 }
        assertEquals(30, accepted) // only until the queue grew past MAX_EXTRA_DELAY_MS
    }

    @Test fun twoHoursOfClockDriftPasses() {
        val f = ControlFreshness()
        // 50 ppm relative drift, frames every 30 ms for 2 h -> d grows by 360 ms overall.
        var t = 0L
        while (t < 7_200_000L) {
            assertTrue("at $t", f.frame(t, 60 + t * 50 / 1_000_000))
            t += 30
        }
    }

    @Test fun resetAcceptsRebootedController() {
        val f = ControlFreshness()
        assertTrue(f.frame(5_000_000, 50))
        f.reset()
        assertTrue(f.frame(20_000, 50))
    }
}
