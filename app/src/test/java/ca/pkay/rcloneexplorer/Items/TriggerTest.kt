package ca.pkay.rcloneexplorer.Items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerTest {

    @Test
    fun testDefaultState() {
        val trigger = Trigger(1L)
        // By default, Trigger initializes weekdays to 0b01111111 (127), so days 0-6 should be enabled.
        for (i in 0..6) {
            assertTrue("Day $i should be enabled by default", trigger.isEnabledAtDay(i))
        }
    }

    @Test
    fun testDisableDay() {
        val trigger = Trigger(1L)
        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_MON, false)
        assertFalse("Monday should be disabled", trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_MON))

        // Other days should remain enabled
        for (i in 1..6) {
            assertTrue("Day $i should remain enabled", trigger.isEnabledAtDay(i))
        }
    }

    @Test
    fun testEnableDay() {
        val trigger = Trigger(1L)
        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_WED, false)
        assertFalse("Wednesday should be disabled", trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_WED))

        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_WED, true)
        assertTrue("Wednesday should be enabled", trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_WED))
    }

    @Test
    fun testMultipleDays() {
        val trigger = Trigger(1L)

        // Disable Mon, Wed, Fri
        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_MON, false)
        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_WED, false)
        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_FRI, false)

        assertFalse(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_MON))
        assertTrue(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_TUE))
        assertFalse(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_WED))
        assertTrue(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_THU))
        assertFalse(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_FRI))
        assertTrue(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_SAT))
        assertTrue(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_SUN))
    }

    @Test
    fun testBoundaryConditions() {
        val trigger = Trigger(1L)

        // Disable Monday (0)
        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_MON, false)
        assertFalse(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_MON))

        // Disable Sunday (6)
        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_SUN, false)
        assertFalse(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_SUN))

        // Check middle days are still enabled
        for (i in 1..5) {
            assertTrue("Day $i should be enabled", trigger.isEnabledAtDay(i))
        }
    }

    @Test
    fun testSetWeekdays() {
        val trigger = Trigger(1L)
        // Set to 0 (all disabled)
        trigger.setWeekdays(0.toByte())
        for (i in 0..6) {
            assertFalse("Day $i should be disabled", trigger.isEnabledAtDay(i))
        }

        // Set to 1 (Monday enabled)
        trigger.setWeekdays(1.toByte())
        assertTrue(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_MON))
        for (i in 1..6) {
            assertFalse("Day $i should be disabled", trigger.isEnabledAtDay(i))
        }

        // Set to 64 (Sunday enabled) -> 1 << 6
        trigger.setWeekdays(64.toByte())
        assertTrue(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_SUN))
        for (i in 0..5) {
            assertFalse("Day $i should be disabled", trigger.isEnabledAtDay(i))
        }

        // Set to 127 (all enabled)
        trigger.setWeekdays(127.toByte())
        for (i in 0..6) {
            assertTrue("Day $i should be enabled", trigger.isEnabledAtDay(i))
        }
    }

    @Test
    fun testGetWeekdays() {
        val trigger = Trigger(1L)
        // Default is 127
        assertEquals(127, trigger.getWeekdays())

        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_MON, false)
        // 127 - 1 = 126
        assertEquals(126, trigger.getWeekdays())

        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_SUN, false)
        // 126 - 64 = 62
        assertEquals(62, trigger.getWeekdays())
    }
}
