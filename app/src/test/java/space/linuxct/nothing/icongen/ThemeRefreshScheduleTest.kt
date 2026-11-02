package space.linuxct.nothing.icongen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeRefreshScheduleTest {
    @Test fun firstRequestAtBootIsNotMistakenForCooldown() {
        val schedule = ThemeRefreshSchedule()
        schedule.request(0)
        assertEquals(250L, schedule.nextAt())
        assertFalse(schedule.take(249))
        assertTrue(schedule.take(250))
        assertNull(schedule.nextAt())
    }

    @Test fun burstSettlesFromItsFinalEvent() {
        val schedule = ThemeRefreshSchedule()
        schedule.request(100)
        schedule.request(300)
        assertEquals(550L, schedule.nextAt())
        assertFalse(schedule.take(549))
        assertTrue(schedule.take(550))
    }

    @Test fun finalTransitionDuringCooldownIsDeferredRatherThanDropped() {
        val schedule = ThemeRefreshSchedule()
        schedule.request(0)
        assertTrue(schedule.take(250))
        schedule.request(700)
        assertEquals(5_250L, schedule.nextAt())
        assertFalse(schedule.take(5_249))
        assertTrue(schedule.take(5_250))
        assertNull(schedule.nextAt())
    }

    @Test fun burstDuringCooldownCoalescesButStillHonorsFinalSettle() {
        val schedule = ThemeRefreshSchedule()
        schedule.request(0)
        assertTrue(schedule.take(250))
        schedule.request(1_000)
        schedule.request(5_100)
        assertEquals(5_350L, schedule.nextAt())
        assertFalse(schedule.take(5_349))
        assertTrue(schedule.take(5_350))
    }

    @Test fun eventDuringInflightWritesRemainsPendingAfterFirstRequestIsTaken() {
        val schedule = ThemeRefreshSchedule()
        schedule.request(0)
        assertTrue(schedule.take(250))
        schedule.request(900)
        assertEquals(5_250L, schedule.nextAt())
        assertTrue(schedule.take(5_250))
        assertFalse(schedule.take(5_250))
    }

    @Test fun disablingClearsPendingWorkWithoutResettingCooldown() {
        val schedule = ThemeRefreshSchedule()
        schedule.request(0)
        assertTrue(schedule.take(250))
        schedule.request(1_000)
        schedule.clear()
        assertNull(schedule.nextAt())
        assertFalse(schedule.take(10_000))
        schedule.request(2_000)
        assertEquals(5_250L, schedule.nextAt())
    }

    @Test fun lateExecutionMeasuresCooldownFromActualStart() {
        val schedule = ThemeRefreshSchedule()
        schedule.request(0)
        assertTrue(schedule.take(2_000))
        schedule.request(2_100)
        assertEquals(7_000L, schedule.nextAt())
        assertTrue(schedule.take(7_000))
    }

}
