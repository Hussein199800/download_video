package com.tadaffuq.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.tadaffuq.app.download.Conditions
import com.tadaffuq.app.download.DeviceState
import com.tadaffuq.app.download.DownloadManager
import com.tadaffuq.app.download.NewTask
import com.tadaffuq.app.download.Notifier
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.NetKind
import com.tadaffuq.data.SettingsRepository
import com.tadaffuq.data.TaskStatus
import com.tadaffuq.engine.HttpClients
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** المدير الحقيقي مع Room وخادم HTTP حقيقي يدعم Range: إضافة، إيقاف، استئناف، إعادة تشغيل العملية. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ManagerIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private val content = Random(3).nextBytes(3 * 1024 * 1024 + 7)
    @Volatile private var throttle = 0L

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")?.removePrefix("bytes=")
                val start = range?.substringBefore('-')?.toLong() ?: 0
                val end = range?.substringAfter('-')?.toLongOrNull() ?: (content.size - 1L)
                val slice = content.copyOfRange(start.toInt(), minOf(end, content.size - 1L).toInt() + 1)
                return MockResponse().setResponseCode(if (range != null) 206 else 200)
                    .setHeader("ETag", "\"x\"")
                    .apply { if (range != null) setHeader("Content-Range", "bytes $start-${start + slice.size - 1}/${content.size}") }
                    .setBody(Buffer().write(slice))
                    .apply { if (throttle > 0) throttleBody(64 * 1024, throttle, TimeUnit.MILLISECONDS) }
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown(); db.close() }

    private val settings by lazy { SettingsRepository(ctx) }

    private fun manager(): DownloadManager {
        val conditions = Conditions(ctx)
        val m = DownloadManager(ctx, db, settings, HttpClients.create(), conditions, Notifier(ctx))
        m.start()
        conditions.set(DeviceState(connected = true, net = NetKind.WIFI, batteryPercent = 80))
        m.wake()
        return m
    }

    private suspend fun waitFor(id: Long, vararg status: TaskStatus): com.tadaffuq.data.TaskEntity {
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end) {
            val t = db.tasks().get(id)
            if (t?.status in status) return t!!
            delay(50)
        }
        throw AssertionError("timeout waiting ${status.toList()}, task=${db.tasks().get(id)}")
    }

    @Test fun downloadsToCompletionAndRecordsUsage() = runBlocking {
        val m = manager()
        val dir = tmp.newFolder("out")
        val id = m.enqueue(listOf(NewTask(server.url("/f/a.bin").toString(), "a.bin", dir.path))).single()
        val t = waitFor(id, TaskStatus.COMPLETED, TaskStatus.FAILED)
        assertEquals(t.errorCode, TaskStatus.COMPLETED, t.status)
        assertArrayEquals(content, File(t.filePath!!).readBytes())
        assertTrue("no partial file left", dir.listFiles()!!.none { it.name.endsWith(".tdq") })
        assertTrue(db.tasks().segments(id).isEmpty())
    }

    @Test fun pauseResumeAndProcessRestartKeepIntegrity() = runBlocking {
        throttle = 400
        val dir = tmp.newFolder("out2")
        val m1 = manager()
        val id = m1.enqueue(listOf(NewTask(server.url("/f/b.bin").toString(), "b.bin", dir.path, connections = 4))).single()
        waitFor(id, TaskStatus.RUNNING)
        withTimeout(30_000) { while ((db.tasks().get(id)?.downloadedBytes ?: 0) < 300_000) delay(50) }
        m1.pause(listOf(id))
        val paused = waitFor(id, TaskStatus.PAUSED)
        assertTrue(paused.downloadedBytes in 1 until content.size)
        assertTrue(db.tasks().segments(id).isNotEmpty())

        // «إعادة تشغيل»: تموت العملية والمهمة تعمل، ثم يبدأ مدير جديد على القاعدة نفسها
        m1.resume(listOf(id))
        waitFor(id, TaskStatus.RUNNING)
        delay(500)
        m1.kill()
        delay(300)
        db.tasks().setStatus(id, TaskStatus.RUNNING)
        throttle = 0
        manager()
        val done = waitFor(id, TaskStatus.COMPLETED, TaskStatus.FAILED)
        assertEquals(done.errorCode, TaskStatus.COMPLETED, done.status)
        assertArrayEquals(content, File(done.filePath!!).readBytes())
    }

    @Test fun nameConflictNeverOverwrites() = runBlocking {
        val dir = tmp.newFolder("out3")
        File(dir, "c.bin").writeText("existing")
        val m = manager()
        val id = m.enqueue(listOf(NewTask(server.url("/f/c.bin").toString(), "c.bin", dir.path))).single()
        val t = waitFor(id, TaskStatus.COMPLETED, TaskStatus.FAILED)
        assertEquals("c (1).bin", t.fileName)
        assertEquals("existing", File(dir, "c.bin").readText())
    }

    @Test fun checksumMismatchIsFlagged() = runBlocking {
        val dir = tmp.newFolder("out4")
        val m = manager()
        val id = m.enqueue(listOf(NewTask(server.url("/f/d.bin").toString(), "d.bin", dir.path, expectedHash = "0".repeat(64)))).single()
        val t = waitFor(id, TaskStatus.COMPLETED, TaskStatus.FAILED)
        assertEquals(false, t.hashOk)
        assertEquals("CHECKSUM", t.errorCode)
    }

    @Test fun offlineHoldsQueueAndResumesWhenBack() = runBlocking {
        val dir = tmp.newFolder("out5")
        val conditions = Conditions(ctx)
        val m = DownloadManager(ctx, db, settings, HttpClients.create(), conditions, Notifier(ctx))
        m.start()
        conditions.set(DeviceState(connected = false))
        val id = m.enqueue(listOf(NewTask(server.url("/f/e.bin").toString(), "e.bin", dir.path))).single()
        delay(800)
        assertEquals(TaskStatus.QUEUED, db.tasks().get(id)!!.status)
        conditions.set(DeviceState(connected = true, net = NetKind.WIFI))
        val t = waitFor(id, TaskStatus.COMPLETED, TaskStatus.FAILED)
        assertEquals(TaskStatus.COMPLETED, t.status)
    }
}
