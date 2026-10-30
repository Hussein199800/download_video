package com.sayl.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** ينفّذ الطابور في الخلفية حتى ينتهي، بحد أقصى «parallel» تنزيلات معاً. */
class DownloadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    private val store = Bus.store(applicationContext)
    private val lastEmit = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun doWork(): Result {
        ensureChannels(applicationContext)
        runCatching { setForegroundAsync(foreground(null)).get() }
        // لا يعمل إلا عامل واحد، فما بقي «يعمل» هو من تشغيل انقطع
        store.recoverInterrupted()
        val n = Bus.parallel(applicationContext)
        val pool = Executors.newFixedThreadPool(n)
        repeat(n) { pool.execute { while (!isStopped) { val j = store.claim() ?: break; run(j) } } }
        pool.shutdown()
        while (!pool.awaitTermination(1, TimeUnit.SECONDS)) if (isStopped) store.all().filter { it.state == "running" }.forEach { Engine.cancel(it.id) }
        return Result.success()
    }

    private fun run(job: Job) {
        Bus.emit("state", job)
        val work = workDir(applicationContext, job.id)
        try {
            val cookies = Bus.cookies(applicationContext).takeIf { it.exists() }
            val file = Engine.download(applicationContext, job.id, job.url, job.args, work, cookies) { pct, eta, line ->
                val p = Progress.parse(line)
                val j = store.live(job.id) {
                    it.copy(pct = if (pct >= 0) pct.coerceIn(0f, 100f) else it.pct, eta = eta, speed = p.speed ?: it.speed,
                        downloaded = p.downloaded ?: it.downloaded, total = p.total ?: it.total)
                } ?: return@download
                val now = System.currentTimeMillis()
                if (now - (lastEmit[job.id] ?: 0) >= 500) {
                    lastEmit[job.id] = now
                    Bus.emit("progress", j)
                    notifyProgress(j)
                }
            }
            val saved = Saver.publish(applicationContext, file, job.folder)
            finish(store.update(job.id) { it.copy(state = "done", pct = 100f, path = saved.path, uri = saved.uri, error = null, finishedAt = System.currentTimeMillis()) })
        } catch (e: YoutubeDL.CanceledException) {
            // الإيقاف المؤقت يقتل العملية أيضاً؛ نُبقي حالته وملفه الجزئي
            if (store.get(job.id)?.state != "paused") finish(store.update(job.id) { it.copy(state = "canceled", finishedAt = System.currentTimeMillis()) })
            else Bus.emit("state", store.get(job.id)!!)
        } catch (e: Exception) {
            val st = store.get(job.id)?.state
            if (st == "canceled" || st == "paused") return
            val msg = (e.message ?: e.toString()).take(4000)
            val retry = NETWORK.containsMatchIn(msg) && job.attempts < 2
            if (retry) {
                Thread.sleep(3000)
                finish(store.update(job.id) { it.copy(state = "queued", attempts = it.attempts + 1, error = msg) })
            } else {
                finish(store.update(job.id) { it.copy(state = "failed", error = msg, finishedAt = System.currentTimeMillis()) })
            }
        } finally {
            // الملف الجزئي يبقى للاستكمال ما دامت المهمة موقوفة أو ستُعاد
            val st = store.get(job.id)?.state
            if (st == null || st == "done" || st == "canceled" || st == "failed") work.deleteRecursively()
        }
    }

    private fun finish(j: Job?) {
        j ?: return
        Bus.emit("state", j)
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (j.state == "done" || j.state == "failed") {
            val n = NotificationCompat.Builder(applicationContext, CH_DONE)
                .setSmallIcon(if (j.state == "done") android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(if (j.state == "done") "اكتمل التنزيل" else "تعذّر التنزيل")
                .setContentText(j.title)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(j.id.hashCode(), n) }
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        applicationContext, 0,
        Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun foreground(j: Job?): ForegroundInfo {
        val active = store.all().count { it.active }
        val n = NotificationCompat.Builder(applicationContext, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (active > 1) "يُنزَّل $active ملفات" else "جارٍ التنزيل")
            .setContentText(j?.title ?: "سَيْل")
            .setProgress(100, j?.pct?.toInt() ?: 0, j == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .apply {
                if (j != null) {
                    addAction(0, "إيقاف مؤقت", ActionReceiver.intent(applicationContext, ActionReceiver.PAUSE, j.id))
                    addAction(0, "إلغاء", ActionReceiver.intent(applicationContext, ActionReceiver.CANCEL, j.id))
                }
            }
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    private fun notifyProgress(j: Job) {
        runCatching { setForegroundAsync(foreground(j)) }
    }

    companion object {
        private const val WORK = "sayl-queue"
        private const val NOTIF_ID = 7001
        const val CH_PROGRESS = "downloads"
        const val CH_DONE = "done"
        private val NETWORK = Regex("timed out|network is unreachable|connection (reset|refused|aborted)|temporary failure in name resolution|unable to download webpage", RegexOption.IGNORE_CASE)

        /** مجلد العمل للمهمة؛ يبقى بين المحاولات ليكمل التنزيل الملف الجزئي */
        fun workDir(ctx: Context, id: String) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "work/$id")

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "التنزيلات الجارية", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "اكتمال التنزيل", NotificationManager.IMPORTANCE_DEFAULT))
        }

        /** يبدأ العامل إن لم يكن يعمل؛ وإن كان يعمل يُلحق تشغيلاً بعده يلتقط ما أُضيف متأخراً */
        fun kick(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<DownloadWorker>().build())
        }
    }
}
