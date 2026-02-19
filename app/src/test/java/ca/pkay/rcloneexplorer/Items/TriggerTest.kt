package ca.pkay.rcloneexplorer.Items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerTest {

    @Test
    fun testDefaultState() {
        val trigger = Trigger(1)
        // Default: 0b01111111 (all days enabled)
        // 0=Mon, 6=Sun
        for (day in 0..6) {
            assertTrue("Day $day should be enabled by default", trigger.isEnabledAtDay(day))
        }
    }

    @Test
    fun testDisableDay() {
        val trigger = Trigger(1)
        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_MON, false)
        assertFalse("Monday should be disabled", trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_MON))

        // Check other days are still enabled
        for (day in 1..6) {
            assertTrue("Day $day should still be enabled", trigger.isEnabledAtDay(day))
        }
    }

    @Test
    fun testEnableDay() {
        val trigger = Trigger(1)
        trigger.setWeekdays(0) // Disable all

        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_SUN, true)
        assertTrue("Sunday should be enabled", trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_SUN))

        // Check other days are still disabled
        for (day in 0..5) {
            assertFalse("Day $day should still be disabled", trigger.isEnabledAtDay(day))
        }
    }

    @Test
    fun testToggleDay() {
        val trigger = Trigger(1)

        // Disable Monday
        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_MON, false)
        assertFalse(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_MON))

        // Enable Monday
        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_MON, true)
        assertTrue(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_MON))
    }

    @Test
    fun testMultipleDays() {
        val trigger = Trigger(1)
        trigger.setWeekdays(0)

        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_MON, true)
        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_WED, true)
        trigger.setEnabledAtDay(Trigger.TRIGGER_DAY_FRI, true)

        assertTrue(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_MON))
        assertFalse(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_TUE))
        assertTrue(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_WED))
        assertFalse(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_THU))
        assertTrue(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_FRI))
        assertFalse(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_SAT))
        assertFalse(trigger.isEnabledAtDay(Trigger.TRIGGER_DAY_SUN))
    }

    @Test
    fun testBoundaryConditions() {
        val trigger = Trigger(1)
        trigger.setWeekdays(0)

        // Test boundary 0 (Monday)
        trigger.setEnabledAtDay(0, true)
        assertEquals(1, trigger.getWeekdays())
        assertTrue(trigger.isEnabledAtDay(0))

        // Test boundary 6 (Sunday)
        trigger.setEnabledAtDay(6, true)
        // 1 | 64 = 65
        assertEquals(65, trigger.getWeekdays())
        assertTrue(trigger.isEnabledAtDay(6))
    }

    @Test
    fun testSetWeekdays() {
        val trigger = Trigger(1)
        // Set specific pattern: Mon, Wed, Fri (1 + 4 + 16 = 21)
        // Mon=0 (1), Tue=1 (2), Wed=2 (4), Thu=3 (8), Fri=4 (16), Sat=5 (32), Sun=6 (64)
        val pattern = (1 or 4 or 16).toByte()
        trigger.setWeekdays(pattern)

        assertTrue(trigger.isEnabledAtDay(0))
        assertFalse(trigger.isEnabledAtDay(1))
        assertTrue(trigger.isEnabledAtDay(2))
        assertFalse(trigger.isEnabledAtDay(3))
        assertTrue(trigger.isEnabledAtDay(4))
        assertFalse(trigger.isEnabledAtDay(5))
        assertFalse(trigger.isEnabledAtDay(6))
    }
}
