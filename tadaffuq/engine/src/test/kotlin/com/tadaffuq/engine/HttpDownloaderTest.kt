package com.tadaffuq.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

class HttpDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val client = HttpClients.create(readTimeoutSec = 10)
    private lateinit var server: RangeServer
    private val content = Random(42).nextBytes(5 * 1024 * 1024 + 123)

    @Before fun setUp() { server = RangeServer(content) }
    @After fun tearDown() { server.close() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private class Recorder : DownloadListener {
        @Volatile var last: ResumeState? = null
        @Volatile var restarted = false
        @Volatile var bytes = 0L
        @Volatile var maxConnections = 0
        @Volatile var retries = 0
        override fun onStart(probe: ProbeResult, restarted: Boolean) { this.restarted = restarted }
        override suspend fun persist(state: ResumeState) { last = state }
        override fun onBytes(count: Long) { synchronized(this) { bytes += count } }
        override fun onProgress(snapshot: Snapshot) { maxConnections = maxOf(maxConnections, snapshot.connections) }
        override fun onRetry(segment: Int, attempt: Int, error: DownloadError, delayMs: Long) { retries++ }
    }

    private fun downloader(file: File, cfg: DownloadConfig = DownloadConfig(connections = 4, baseRetryDelayMs = 20, persistIntervalMs = 100), saved: ResumeState? = null, rec: Recorder = Recorder(), limiter: RateLimiter = RateLimiter()) =
        HttpDownloader(client, RequestSpec(server.url()), file, cfg, saved, taskLimiter = limiter, listener = rec)

    @Test fun multiConnectionDownloadIsByteExact() = runBlocking {
        val f = tmp.newFile("a.part")
        val rec = Recorder()
        val probe = downloader(f, rec = rec).run()
        assertEquals(content.size.toLong(), probe.totalBytes)
        assertTrue(probe.resumable)
        assertEquals(sha(content), sha(f.readBytes()))
        assertTrue("used several ranges", server.rangeRequests.get() >= 4)
        assertEquals(content.size.toLong(), rec.bytes)
        assertTrue(SegmentPlanner.isConsistent(rec.last!!.segments, probe.totalBytes))
    }

    @Test fun pauseThenResumeContinuesWithoutRedownloading() = runBlocking {
        server.throttleMsPer64k = 120
        val f = tmp.newFile("b.part")
        val rec = Recorder()
        val job = async { downloader(f, rec = rec).run() }
        // انتظر حتى يُحفظ تقدم حقيقي ثم أوقف
        withTimeout(20_000) { while ((rec.last?.segments?.sumOf { it.downloaded } ?: 0) < 512 * 1024) kotlinx.coroutines.delay(50) }
        job.cancelAndJoin()
        val saved = rec.last!!
        val done = saved.segments.sumOf { it.downloaded }
        assertTrue(done in 1 until content.size)
        server.throttleMsPer64k = 0
        val rec2 = Recorder()
        downloader(f, saved = saved, rec = rec2).run()
        assertFalse(rec2.restarted)
        assertEquals(sha(content), sha(f.readBytes()))
        assertEquals("only the remaining bytes were fetched", content.size - done, rec2.bytes)
    }

    @Test fun connectionDropsAreRetriedAndFileStaysIntact() = runBlocking {
        server.cutAfterBytes = 300_000
        server.cutsRemaining.set(6)
        val f = tmp.newFile("c.part")
        val rec = Recorder()
        downloader(f, rec = rec).run()
        assertTrue(rec.retries > 0)
        assertEquals(sha(content), sha(f.readBytes()))
    }

    @Test fun serverErrorsAreRetriedWithBackoff() = runBlocking {
        val f = tmp.newFile("d.part")
        var retries = 0
        val rec = object : DownloadListener {
            // الأخطاء تبدأ بعد الفحص مباشرة، على طلبات الأجزاء
            override fun onStart(probe: ProbeResult, restarted: Boolean) { server.errorCode = 500; server.errorsRemaining.set(3) }
            override fun onRetry(segment: Int, attempt: Int, error: DownloadError, delayMs: Long) { synchronized(this) { retries++ } }
        }
        HttpDownloader(client, RequestSpec(server.url()), f, DownloadConfig(connections = 2, baseRetryDelayMs = 20), listener = rec).run()
        assertEquals(3, retries)
        assertEquals(sha(content), sha(f.readBytes()))
    }

    @Test fun serverWithoutRangeUsesSingleConnection() = runBlocking {
        server.supportRanges = false
        val f = tmp.newFile("e.part")
        val rec = Recorder()
        val probe = downloader(f, rec = rec).run()
        assertFalse(probe.acceptRanges)
        assertEquals(0, server.rangeRequests.get())
        assertTrue(rec.maxConnections <= 1)
        assertEquals(sha(content), sha(f.readBytes()))
    }

    @Test fun noRangeServerRestartsFromZeroAfterDrop() = runBlocking {
        server.supportRanges = false
        server.cutAfterBytes = 1_000_000
        server.cutsRemaining.set(1)
        val f = tmp.newFile("f.part")
        downloader(f).run()
        assertEquals(sha(content), sha(f.readBytes()))
    }

    @Test fun changedFileOnServerIsNotResumedUnsafely() = runBlocking {
        server.throttleMsPer64k = 120
        val f = tmp.newFile("g.part")
        val rec = Recorder()
        val job = async { downloader(f, rec = rec).run() }
        withTimeout(20_000) { while ((rec.last?.segments?.sumOf { it.downloaded } ?: 0) < 256 * 1024) kotlinx.coroutines.delay(50) }
        job.cancelAndJoin()
        val saved = rec.last!!
        // الملف يتغيّر على الخادم (محتوى جديد ومعرّف جديد)
        val v2 = Random(7).nextBytes(content.size)
        server.content = v2
        server.etag = "\"v2\""
        server.throttleMsPer64k = 0
        val rec2 = Recorder()
        downloader(f, saved = saved, rec = rec2).run()
        assertTrue("must restart instead of mixing versions", rec2.restarted)
        assertEquals(sha(v2), sha(f.readBytes()))
    }

    @Test fun contentChangingMidDownloadIsDetectedByIfRange() = runBlocking {
        val f = tmp.newFile("h.part")
        val first = CompletableDeferred<Unit>()
        val rec = object : DownloadListener {
            override fun onStart(probe: ProbeResult, restarted: Boolean) {
                server.etag = "\"changed\"" // يتغيّر مباشرة بعد الفحص
                server.lastModified = "Thu, 02 Jan 2025 00:00:00 GMT"
                first.complete(Unit)
            }
        }
        try {
            HttpDownloader(client, RequestSpec(server.url()), f, DownloadConfig(connections = 4), listener = rec).run()
            fail("expected ChangedOnServer")
        } catch (e: DownloadException) {
            assertEquals(DownloadError.ChangedOnServer, e.error)
        }
    }

    @Test fun unknownSizeStreamCompletes() = runBlocking {
        server.supportRanges = false
        server.sendLength = false
        val f = tmp.newFile("i.part")
        val probe = downloader(f).run()
        assertEquals(-1L, probe.totalBytes)
        assertEquals(sha(content), sha(f.readBytes()))
    }

    @Test fun notFoundFailsWithoutRetries() = runBlocking {
        server.statusOverride = 404
        try { downloader(tmp.newFile("j.part")).run(); fail() } catch (e: DownloadException) { assertEquals(DownloadError.NotFound, e.error) }
        assertEquals(1, server.requests.get())
    }

    @Test fun expiredLinkIsReported() = runBlocking {
        server.statusOverride = 403
        try { downloader(tmp.newFile("k.part")).run(); fail() } catch (e: DownloadException) { assertTrue(e.error is DownloadError.LinkExpired) }
    }

    @Test fun insufficientSpaceIsCheckedBeforeWriting() = runBlocking {
        val f = tmp.newFile("l.part")
        try {
            HttpDownloader(client, RequestSpec(server.url()), f, usableSpace = { 1000 }).run(); fail()
        } catch (e: DownloadException) { assertTrue(e.error is DownloadError.InsufficientSpace) }
        assertEquals(0L, f.length())
    }

    @Test fun dynamicSplittingKeepsConnectionsBusy() = runBlocking {
        val f = tmp.newFile("m.part")
        val rec = Recorder()
        // تقدم محفوظ بجزء واحد كبير، والهدف ٤ اتصالات: المحرك يقسم الجزء ديناميكياً
        val probe = Prober(client).probe(RequestSpec(server.url()))
        val saved = ResumeState(probe, listOf(SegmentState(0, 0, content.size - 1L, 0)))
        HttpDownloader(client, RequestSpec(server.url()), f, DownloadConfig(connections = 4, minSplitBytes = 64 * 1024), saved, listener = rec).run()
        assertFalse(rec.restarted)
        assertTrue("segments were split: ${rec.last!!.segments.size}", rec.last!!.segments.size >= 4)
        assertTrue(SegmentPlanner.isConsistent(rec.last!!.segments, content.size.toLong()))
        assertEquals(sha(content), sha(f.readBytes()))
    }

    @Test fun speedLimitIsRespected() = runBlocking {
        val small = Random(1).nextBytes(600 * 1024)
        server.content = small
        val f = tmp.newFile("n.part")
        val t0 = System.nanoTime()
        downloader(f, limiter = RateLimiter(300 * 1024)).run()
        val secs = (System.nanoTime() - t0) / 1e9
        assertTrue("took $secs s", secs >= 0.9)
        assertArrayEquals(small, f.readBytes())
    }
}
