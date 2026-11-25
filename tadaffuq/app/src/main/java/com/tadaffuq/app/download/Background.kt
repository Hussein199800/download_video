package com.tadaffuq.app.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tadaffuq.app.R
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.TaskStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * يستأنف الطابور بعد إعادة تشغيل الجهاز أو عودة الشبكة والتطبيق مغلق.
 * يعمل كخدمة أمامية عبر WorkManager ما دام هناك عمل؛ إن رفض النظام ذلك يطلب لمسة من المستخدم.
 */
@HiltWorker
class ResumeWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val manager: DownloadManager,
    private val notifier: Notifier,
    private val db: AppDatabase,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val pending = db.tasks().withStatus(listOf(TaskStatus.QUEUED, TaskStatus.WAITING_NETWORK, TaskStatus.RUNNING))
        if (pending.isEmpty()) return Result.success()
        try {
            setForeground(foregroundInfo())
        } catch (e: Exception) {
            notifier.tapToResume()
            return Result.success()
        }
        DownloadService.workerInForeground = true
        try {
            manager.start()
            manager.wake()
            delay(3_000)
            while (manager.hasWork()) {
                setForeground(foregroundInfo())
                delay(2_000)
            }
        } finally {
            DownloadService.workerInForeground = false
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val n = notifier.summary(manager.live.value, manager.runningIds.value.size, manager.hold.value)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(Notifier.FG_ID + 10, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(Notifier.FG_ID + 10, n)
    }

    companion object {
        const val NAME = "resume"

        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<ResumeWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
        }

        fun enqueueExpedited(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<ResumeWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ResumeWorker.enqueue(context)
        }
    }
}

@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var manager: DownloadManager

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                manager.start()
                when (intent.action) {
                    PAUSE_ALL -> manager.pauseAll()
                    RESUME_ALL -> manager.resumeAll()
                }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val PAUSE_ALL = "com.tadaffuq.app.PAUSE_ALL"
        const val RESUME_ALL = "com.tadaffuq.app.RESUME_ALL"
    }
}

/** بلاطة الإعدادات السريعة: إيقاف أو استئناف كل التنزيلات. */
@AndroidEntryPoint
class QuickTileService : TileService() {
    @Inject lateinit var manager: DownloadManager
    @Inject lateinit var db: AppDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() { refresh() }

    private fun refresh() {
        val tile = qsTile ?: return
        val active = manager.runningIds.value.isNotEmpty()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = getString(if (active) R.string.tile_active else R.string.tile_idle)
        tile.updateTile()
    }

    override fun onClick() {
        scope.launch {
            manager.start()
            if (manager.runningIds.value.isNotEmpty()) manager.pauseAll() else manager.resumeAll()
            delay(400)
            refresh()
        }
    }
}
