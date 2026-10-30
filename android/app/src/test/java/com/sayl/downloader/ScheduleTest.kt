package com.sayl.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {
    private val off = Schedule.Window(false, 0, 0)
    private val night = Schedule.Window(true, 60, 420)          // 01:00 → 07:00
    private val cross = Schedule.Window(true, 23 * 60, 6 * 60)  // 23:00 → 06:00
    private val net = Schedule.Net(online = true, wifi = true)
    private val mobile = Schedule.Net(online = true, wifi = false)
    private fun job(state: String = "queued", scheduledAt: Long = 0, retryAt: Long = 0, wifiOnly: Boolean = false) =
        Job(id = "a", url = "u", title = "t", kind = "video", args = emptyList(), state = state, scheduledAt = scheduledAt, retryAt = retryAt, wifiOnly = wifiOnly)

    @Test
    fun windowEdges() {
        assertTrue(Schedule.inWindow(night, 60))
        assertFalse(Schedule.inWindow(night, 420))
        assertTrue(Schedule.inWindow(cross, 5 * 60 + 59))
        assertFalse(Schedule.inWindow(cross, 6 * 60 + 1))
        assertTrue(Schedule.inWindow(cross, 23 * 60 + 30))
        assertTrue(Schedule.inWindow(off, 720))
    }

    @Test
    fun dueRules() {
        assertTrue(Schedule.due(job(), 1000, off, 720, net))
        assertFalse(Schedule.due(job(scheduledAt = 5000), 1000, off, 720, net))
        assertTrue(Schedule.due(job(scheduledAt = 500), 1000, off, 720, net))
        assertFalse(Schedule.due(job(wifiOnly = true), 1000, off, 720, mobile))
        assertFalse(Schedule.due(job(state = "retrying", retryAt = 2000), 1000, off, 720, net))
        assertTrue(Schedule.due(job(state = "retrying", retryAt = 900), 1000, off, 720, net))
        assertFalse(Schedule.due(job(state = "waiting_network"), 1000, off, 720, Schedule.Net(false, false)))
        assertTrue(Schedule.due(job(state = "waiting_network"), 1000, off, 720, mobile))
        assertFalse(Schedule.due(job(), 1000, night, 720, net))
        // «ابدأ الآن» يتجاوز نافذة التنزيل
        assertTrue(Schedule.due(job(scheduledAt = Schedule.FORCE), 1000, night, 720, net))
        assertFalse(Schedule.due(job(state = "paused"), 1000, off, 720, net))
    }

    @Test
    fun nextAtWaitsForTimeAndWindow() {
        val now = 1_000_000L
        assertEquals(now, Schedule.nextAt(job(), now, off, 720))
        assertEquals(now + 60_000, Schedule.nextAt(job(scheduledAt = now + 60_000), now, off, 720))
        assertEquals(now + 30_000, Schedule.nextAt(job(state = "retrying", retryAt = now + 30_000), now, off, 720))
        // الساعة 12:00 والنافذة تبدأ 01:00 → بعد 13 ساعة
        assertEquals(now + 13 * 3_600_000L, Schedule.nextAt(job(), now, night, 720))
        assertEquals(now, Schedule.nextAt(job(), now, night, 90))
        assertNull(Schedule.nextAt(job(state = "paused"), now, off, 720))
    }
}
