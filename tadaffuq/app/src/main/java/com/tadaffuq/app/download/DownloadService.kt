package com.tadaffuq.app.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * خدمة أمامية من نوع dataSync تُبقي العملية حية ما دامت هناك تنزيلات تعمل،
 * مع إشعار موحد يتحدث كل ثانية. تتوقف ذاتياً عند فراغ الطابور.
 */
@AndroidEntryPoint
class DownloadService : Service() {
    @Inject lateinit var manager: DownloadManager
    @Inject lateinit var notifier: Notifier
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notifier.summary(manager.live.value, manager.runningIds.value.size, manager.hold.value)
        ServiceCompat.startForeground(this, Notifier.FG_ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tadaffuq:download").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        if (loop?.isActive != true) loop = scope.launch {
            var idleTicks = 0
            while (isActive) {
                delay(1_000)
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm.notify(Notifier.FG_ID, notifier.summary(manager.live.value, manager.runningIds.value.size, manager.hold.value))
                idleTicks = if (manager.hasWork()) 0 else idleTicks + 1
                if (idleTicks >= 3) { stopSelfCleanly(); break }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopSelfCleanly() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15: حد زمني يومي لخدمات dataSync. نوقف بأمان (التقدم محفوظ) ونبلغ المستخدم. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.launch {
            manager.pauseAll()
            notifier.timedOut()
            stopSelfCleanly()
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile private var lastStart = 0L
        /** عامل الاستئناف يعمل كخدمة أمامية بنفسه؛ لا حاجة لبدء الخدمة. */
        @Volatile var workerInForeground = false

        fun ensureRunning(ctx: Context, manager: DownloadManager) {
            if (workerInForeground) return
            val now = System.currentTimeMillis()
            if (now - lastStart < 1_500) return
            lastStart = now
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: التطبيق في الخلفية. عامل WorkManager
                // بأولوية عالية يحاول، وإلا يُطلب من المستخدم لمسة للاستئناف.
                ResumeWorker.enqueueExpedited(ctx)
            }
        }
    }
}
