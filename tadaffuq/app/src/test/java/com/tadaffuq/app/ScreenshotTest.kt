package com.tadaffuq.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.room.Room
import androidx.work.testing.WorkManagerTestInitHelper
import com.tadaffuq.app.download.Conditions
import com.tadaffuq.app.download.DownloadManager
import com.tadaffuq.app.download.Notifier
import com.tadaffuq.app.ui.MainViewModel
import com.tadaffuq.app.ui.downloads.DownloadsScreen
import com.tadaffuq.app.ui.downloads.TaskDetailSheet
import com.tadaffuq.app.ui.files.FilesScreen
import com.tadaffuq.app.ui.home.HomeScreen
import com.tadaffuq.app.ui.more.MoreScreen
import com.tadaffuq.app.ui.more.ReportsScreen
import com.tadaffuq.app.ui.more.SettingsScreen
import com.tadaffuq.app.ui.theme.TadaffuqTheme
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.NetKind
import com.tadaffuq.data.SettingsRepository
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus
import com.tadaffuq.data.ThemeMode
import com.tadaffuq.engine.HttpClients
import com.tadaffuq.engine.SegmentState
import com.tadaffuq.engine.Snapshot
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * يرسم الشاشات الحقيقية ببيانات اختبار (في الاختبارات فقط) إلى PNG عند ضبط TADAFFUQ_SHOTS،
 * بالعربية والإنجليزية والفاتح والداكن. وبدونه يبقى اختبار دخان يتأكد أن كل شاشة تُرسم.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: MainViewModel

    @Before fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val now = System.currentTimeMillis()
        runBlocking {
            db.tasks().insertAll(
                listOf(
                    TaskEntity(url = "https://releases.example.org/ubuntu-24.04-desktop-amd64.iso", fileName = "ubuntu-24.04-desktop-amd64.iso", dirPath = "/x", status = TaskStatus.RUNNING, totalBytes = 5_900_000_000, downloadedBytes = 2_300_000_000, acceptRanges = true, finalUrl = "https://mirror.example.org/ubuntu.iso", createdAt = now - 1000),
                    TaskEntity(url = "https://huggingface.co/x/model-Q4_K_M.gguf", fileName = "model-Q4_K_M.gguf", dirPath = "/x", status = TaskStatus.QUEUED, totalBytes = 4_100_000_000, createdAt = now - 2000),
                    TaskEntity(url = "https://example.org/تقرير-المشروع-السنوي.pdf", fileName = "تقرير المشروع السنوي ٢٠٢٥.pdf", dirPath = "/x", status = TaskStatus.PAUSED, totalBytes = 48_000_000, downloadedBytes = 12_500_000, createdAt = now - 3000),
                    TaskEntity(url = "https://files.example.net/backup.zip", fileName = "backup.zip", dirPath = "/x", status = TaskStatus.FAILED, errorCode = "EXPIRED_403", totalBytes = 900_000_000, downloadedBytes = 300_000_000, createdAt = now - 4000),
                    TaskEntity(url = "https://github.com/x/releases/app-arm64-v8a.apk", fileName = "app-arm64-v8a.apk", dirPath = "/x", filePath = "/x/app-arm64-v8a.apk", status = TaskStatus.COMPLETED, totalBytes = 31_400_000, downloadedBytes = 31_400_000, completedAt = now - 60_000, hashOk = true, createdAt = now - 5000),
                    TaskEntity(url = "https://example.org/data.7z", fileName = "بيانات الأرشيف.7z", dirPath = "/x", filePath = "/x/a.7z", status = TaskStatus.COMPLETED, totalBytes = 734_000_000, downloadedBytes = 734_000_000, completedAt = now - 3_600_000, createdAt = now - 6000),
                ),
            )
            val today = com.tadaffuq.app.core.Fmt.day(now)
            for (d in 0..6) {
                val day = com.tadaffuq.app.core.Fmt.day(now - d * 86_400_000L)
                db.usage().add(day, NetKind.WIFI, (1_000_000_000L * (7 - d)) / 3)
                db.usage().add(day, NetKind.CELLULAR, 120_000_000L * (d % 3))
            }
            db.usage().add(today, NetKind.CELLULAR, 80_000_000)
        }
        val settings = SettingsRepository(ctx)
        val manager = DownloadManager(ctx, db, settings, HttpClients.create(), Conditions(ctx), Notifier(ctx))
        vm = MainViewModel(ctx, db, settings, manager)
    }

    private fun shoot(name: String, ar: Boolean, dark: Boolean, popup: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(if (ar) "+ar" else "+en")
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag(if (ar) "ar" else "en"))
        rule.setContent {
            TadaffuqTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        rule.waitForIdle()
        Thread.sleep(300)
        rule.waitForIdle()
        val dir = System.getenv("TADAFFUQ_SHOTS") ?: return
        if (popup) {
            // النوافذ السفلية في نافذة مستقلة: نلتقط آخر جذر
            val img = rule.onAllNodes(androidx.compose.ui.test.isRoot()).onLast().captureToImage()
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { img.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            return
        }
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val pad = PaddingValues()

    @Test fun home_ar_light() = shoot("home_ar_light", true, false) { HomeScreen(vm, "https://example.org/files/archive-2025.zip", {}, {}, pad) }
    @Test fun home_ar_dark() = shoot("home_ar_dark", true, true) { HomeScreen(vm, null, {}, {}, pad) }
    @Test fun home_en_light() = shoot("home_en_light", false, false) { HomeScreen(vm, null, {}, {}, pad) }
    @Test fun downloads_ar_light() = shoot("downloads_ar_light", true, false) { DownloadsScreen(vm, {}, pad) }
    @Test fun downloads_en_dark() = shoot("downloads_en_dark", false, true) { DownloadsScreen(vm, {}, pad) }
    @Test fun files_ar_light() = shoot("files_ar_light", true, false) { FilesScreen(vm, pad) }
    @Test fun more_ar_light() = shoot("more_ar_light", true, false) { MoreScreen(pad) {} }
    @Test fun settings_ar_light() = shoot("settings_ar_light", true, false) { SettingsScreen(vm, pad) {} }
    @Test fun settings_en_dark() = shoot("settings_en_dark", false, true) { SettingsScreen(vm, pad) {} }
    @Test fun reports_ar_dark() = shoot("reports_ar_dark", true, true) { ReportsScreen(vm, pad) {} }

    @Test fun detail_ar_light() = shoot("detail_ar_light", true, false) {
        val t = TaskEntity(id = 99, url = "https://releases.example.org/ubuntu-24.04-desktop-amd64.iso", fileName = "ubuntu-24.04-desktop-amd64.iso", dirPath = "/x", status = TaskStatus.RUNNING, totalBytes = 5_900_000_000, acceptRanges = true, finalUrl = "https://mirror.example.org/ubuntu.iso", retries = 2)
        val total = 5_900_000_000L
        val segs = (0 until 8).map { i -> SegmentState(i, i * total / 8, (i + 1) * total / 8 - 1, (total / 8) * (8 - i) / 9) }
        com.tadaffuq.app.ui.downloads.TaskDetailContent(t, Snapshot(segs.sumOf { it.downloaded }, total, 7_400_000, 6_100_000, 9_800_000, 412, 8, segs), vm) {}
    }
}
