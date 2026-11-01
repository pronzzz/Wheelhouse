package io.github.pronzzz.wheelhouse

import io.github.pronzzz.wheelhouse.ui.device.AodDrift
import io.github.pronzzz.wheelhouse.ui.device.Haptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DeviceFeedbackTest {

    @Test fun offMeansNoPulse() {
        assertNull(Haptics.tick(Haptics.OFF, amplitudeControl = false))
        assertNull(Haptics.press(Haptics.OFF, amplitudeControl = true))
    }

    @Test fun plainMotorsGetPulsesLongEnoughToSpinUp() {
        // Budget phones (e.g. Galaxy J7 Pro) have no amplitude control; anything under ~10 ms is not felt.
        for (level in Haptics.LIGHT..Haptics.STRONG) {
            val p = Haptics.tick(level, amplitudeControl = false)!!
            assertTrue("level $level is ${p.ms} ms", p.ms >= 12)
            assertNull(p.amplitude)
        }
    }

    @Test fun strongerLevelsAreStronger() {
        for (amp in listOf(true, false)) {
            val ms = (Haptics.LIGHT..Haptics.STRONG).map { Haptics.tick(it, amp)!!.ms }
            assertEquals(ms.sorted(), ms)
        }
        val amps = (Haptics.LIGHT..Haptics.STRONG).map { Haptics.tick(it, true)!!.amplitude!! }
        assertEquals(amps.sorted(), amps)
        assertTrue(amps.all { it in 1..255 })
    }

    @Test fun pressIsLongerThanTick() {
        assertTrue(Haptics.press(Haptics.MEDIUM, false)!!.ms > Haptics.tick(Haptics.MEDIUM, false)!!.ms)
    }

    @Test fun outOfRangeLevelsAreClamped() {
        assertEquals(Haptics.tick(Haptics.STRONG, false)!!.ms, Haptics.tick(99, false)!!.ms)
    }

    @Test fun alwaysOnContentMovesEveryMinuteWithinBounds() {
        val spots = (0 until 20).map { AodDrift.offset(it, 100f, 50f) }
        assertTrue(spots.all { (x, y) -> abs(x) <= 100f && abs(y) <= 50f })
        for (i in 1 until spots.size) assertNotEquals(spots[i - 1], spots[i])
        assertEquals(AodDrift.offset(-3, 100f, 50f), AodDrift.offset(7, 100f, 50f))
    }
}
