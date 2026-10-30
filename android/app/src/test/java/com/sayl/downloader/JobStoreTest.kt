package com.sayl.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JobStoreTest {
    private fun tmp(): File = File(Files.createTempDirectory("jobs").toFile(), "jobs.json")
    private fun job(id: String, url: String = "https://youtu.be/$id", args: List<String> = listOf("-x"), at: Long = 0) =
        Job(id = id, url = url, title = id, kind = "audio", args = args, createdAt = at)

    @Test
    fun skipsDuplicateWhileQueuedOrRunning() {
        val s = JobStore(tmp())
        assertEquals(1, s.add(listOf(job("a"))).size)
        assertEquals(0, s.add(listOf(job("b", url = "https://youtu.be/a"))).size)
        // صيغة مختلفة للرابط نفسه مقبولة
        assertEquals(1, s.add(listOf(job("c", url = "https://youtu.be/a", args = listOf("-f", "b")))).size)
        s.update("a") { it.copy(state = "done") }
        assertEquals(1, s.add(listOf(job("d", url = "https://youtu.be/a"))).size)
    }

    @Test
    fun recoverInterruptedRequeuesRunning() {
        val s = JobStore(tmp())
        s.add(listOf(job("a"), job("b")))
        s.update("a") { it.copy(state = "running", pct = 40f) }
        s.recoverInterrupted()
        assertEquals("queued", s.get("a")!!.state)
        // التقدم يبقى لأن التنزيل يكمل الملف الجزئي
        assertEquals(40f, s.get("a")!!.pct)
    }

    @Test
    fun persistsAcrossInstances() {
        val f = tmp()
        JobStore(f).add(listOf(job("a", at = 5)))
        val again = JobStore(f)
        assertEquals("a", again.all().single().id)
        assertEquals(listOf("-x"), again.all().single().args)
    }

    @Test
    fun nextReturnsOldestQueued() {
        val s = JobStore(tmp())
        s.add(listOf(job("new", at = 20), job("old", at = 10)))
        assertEquals("old", s.next()!!.id)
        s.update("old") { it.copy(state = "running") }
        assertEquals("new", s.next()!!.id)
        s.update("new") { it.copy(state = "failed") }
        assertNull(s.next())
    }

    @Test
    fun clearFinishedRemovesFailedAndCanceledOnly() {
        // المكتمل يبقى لأنه السجل
        val s = JobStore(tmp())
        s.add(listOf(job("a", at = 1), job("b", at = 2), job("c", at = 3), job("d", at = 4)))
        s.update("a") { it.copy(state = "done") }
        s.update("b") { it.copy(state = "canceled") }
        s.update("d") { it.copy(state = "failed") }
        s.clearFinished()
        assertEquals(listOf("a", "c"), s.all().map { it.id })
    }

    @Test
    fun claimMarksOldestQueuedRunning() {
        val s = JobStore(tmp())
        s.add(listOf(job("a", at = 1), job("b", at = 2)))
        assertEquals("a", s.claim()!!.id)
        assertEquals("running", s.get("a")!!.state)
        assertEquals("b", s.claim()!!.id)
        assertNull(s.claim())
    }

    @Test
    fun pausedIsNotClaimedAndResumeRequeues() {
        val s = JobStore(tmp())
        s.add(listOf(job("a", at = 1), job("b", at = 2)))
        s.pause("a")
        assertEquals("paused", s.get("a")!!.state)
        assertEquals("b", s.claim()!!.id)
        assertNull(s.claim())
        s.resume("a")
        assertEquals("queued", s.get("a")!!.state)
        assertEquals("a", s.claim()!!.id)
    }

    @Test
    fun pausedCountsAsDuplicate() {
        val s = JobStore(tmp())
        s.add(listOf(job("a")))
        s.pause("a")
        assertEquals(0, s.add(listOf(job("b", url = "https://youtu.be/a"))).size)
    }

    @Test
    fun reorderChangesClaimOrder() {
        val s = JobStore(tmp())
        s.add(listOf(job("a", at = 1), job("b", at = 2), job("c", at = 3)))
        s.toTop("c")
        assertEquals(listOf("c", "a", "b"), s.queueOrder())
        s.move("a", 1)
        assertEquals(listOf("c", "b", "a"), s.queueOrder())
        s.move("c", -1) // الأول لا يصعد أكثر
        assertEquals(listOf("c", "b", "a"), s.queueOrder())
        assertEquals("c", s.claim()!!.id)
    }
}
