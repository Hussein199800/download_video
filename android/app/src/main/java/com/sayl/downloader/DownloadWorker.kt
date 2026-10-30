package com.sayl.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** ينفّذ المستحق من الطابور حتى ينتهي، بحد أقصى «parallel» تنزيلات معاً، ثم يجدول نفسه لما بقي. */
class DownloadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    private val store = Bus.store(applicationContext)
    private val lastEmit = ConcurrentHashMap<String, Long>()
    private val doneCount = AtomicInteger()
    private val failCount = AtomicInteger()
    private var batch = false

    private fun isDue(j: Job): Boolean {
        val now = System.currentTimeMillis()
        return Schedule.due(j, now, Bus.window(applicationContext), Bus.minuteOfDay(now), Bus.net(applicationContext))
    }

    override fun doWork(): Result {
        ensureChannels(applicationContext)
        if (store.next(::isDue) == null) { scheduleNext(applicationContext); return Result.success() }
        runCatching { setForegroundAsync(foreground(null)).get() }
        // قائمة تشغيل كبيرة: إشعار ملخص واحد بدل إشعار لكل ملف
        batch = store.all().count { it.pending } > 3
        val n = Bus.parallel(applicationContext)
        val pool = Executors.newFixedThreadPool(n)
        repeat(n) { pool.execute { while (!isStopped) { val j = store.claim(::isDue) ?: break; run(j) } } }
        pool.shutdown()
        while (!pool.awaitTermination(1, TimeUnit.SECONDS)) if (isStopped) store.all().filter { it.state == "running" }.forEach { Engine.cancel(it.id) }
        if (batch && doneCount.get() + failCount.get() > 0) summary()
        scheduleNext(applicationContext)
        return Result.success()
    }

    private fun run(job: Job) {
        Bus.emit("state", job)
        val work = workDir(applicationContext, job.id)
        try {
            val cookies = Bus.cookies(applicationContext).takeIf { it.exists() }
            val file = Engine.download(applicationContext, job.id, job.url, job.args, work, cookies) { pct, eta, line -> progress(job.id, pct, eta, line) }
            val saved = Saver.publish(applicationContext, file, job.folder)
            finish(store.update(job.id) { it.copy(state = "done", pct = 100f, path = saved.path, uri = saved.uri, error = null, speed = 0, finishedAt = System.currentTimeMillis()) })
        } catch (e: YoutubeDL.CanceledException) {
            // الإيقاف المؤقت يقتل العملية أيضاً؛ نُبقي حالته وملفه الجزئي
            if (store.get(job.id)?.state != "paused") finish(store.update(job.id) { it.copy(state = "canceled", finishedAt = System.currentTimeMillis()) })
            else Bus.emit("state", store.get(job.id)!!)
        } catch (e: Exception) {
            val st = store.get(job.id)?.state
            if (st == "canceled" || st == "paused") return
            failed(job, (e.message ?: e.toString()).take(4000))
        } finally {
            // الملف الجزئي يبقى للاستكمال ما دامت المهمة موقوفة أو ستُعاد
            val st = store.get(job.id)?.state
            if (st == null || st == "done" || st == "canceled" || st == "failed") work.deleteRecursively()
        }
    }

    private fun progress(id: String, pct: Float, eta: Long, line: String) {
        val p = Progress.parse(line)
        val j = store.live(id) {
            it.copy(pct = if (pct >= 0) pct.coerceIn(0f, 100f) else it.pct, eta = eta, speed = p.speed ?: it.speed,
                downloaded = p.downloaded ?: it.downloaded, total = p.total ?: it.total)
        } ?: return
        val now = System.currentTimeMillis()
        if (now - (lastEmit[id] ?: 0) >= 500) {
            lastEmit[id] = now
            Bus.emit("progress", j)
            runCatching { setForegroundAsync(foreground(j)) }
        }
    }

    private fun failed(job: Job, msg: String) {
        val kind = Failure.classify(msg)
        val max = Bus.maxRetries(applicationContext)
        val now = System.currentTimeMillis()
        val n = when {
            // انقطاع الشبكة لا يُحسب محاولة: ننتظر عودتها ثم نكمل
            kind == Failure.Kind.NETWORK -> store.update(job.id) { it.copy(state = "waiting_network", error = msg, speed = 0, eta = -1) }
            Retry.shouldRetry(kind, job.attempts, max) -> store.update(job.id) {
                it.copy(state = "retrying", attempts = it.attempts + 1, retryAt = now + Retry.delayMs(it.attempts + 1), error = msg, speed = 0, eta = -1)
            }
            else -> store.update(job.id) { it.copy(state = "failed", error = msg, speed = 0, finishedAt = now) }
        }
        finish(n)
    }

    private fun finish(j: Job?) {
        j ?: return
        Bus.emit("state", j)
        when (j.state) {
            "done" -> doneCount.incrementAndGet()
            "failed" -> failCount.incrementAndGet()
            else -> return
        }
        if (batch) return
        val n = NotificationCompat.Builder(applicationContext, CH_DONE)
            .setSmallIcon(if (j.state == "done") android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (j.state == "done") "اكتمل التنزيل" else "تعذّر التنزيل")
            .setContentText(j.title)
            .setContentIntent(openApp(applicationContext))
            .setAutoCancel(true)
            .build()
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(j.id.hashCode(), n) }
    }

    private fun summary() {
        val d = doneCount.get()
        val f = failCount.get()
        val n = NotificationCompat.Builder(applicationContext, CH_DONE)
            .setSmallIcon(if (f == 0) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle("انتهى الطابور")
            .setContentText(if (f == 0) "اكتمل $d" else "اكتمل $d، وتعذّر $f")
            .setContentIntent(openApp(applicationContext))
            .setAutoCancel(true)
            .build()
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(SUMMARY_ID, n) }
    }

    private fun foreground(j: Job?): ForegroundInfo {
        val active = store.all().count { it.state == "running" }
        val n = NotificationCompat.Builder(applicationContext, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (active > 1) "يُنزَّل $active ملفات" else "جارٍ التنزيل")
            .setContentText(j?.title ?: "سَيْل")
            .setProgress(100, j?.pct?.toInt() ?: 0, j == null || j.pct <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(applicationContext))
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

    companion object {
        private const val WORK = "sayl-queue"
        private const val WAKE = "sayl-wake"
        private const val NOTIF_ID = 7001
        private const val SUMMARY_ID = 7002
        const val CH_PROGRESS = "downloads"
        const val CH_DONE = "done"

        fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** مجلد العمل للمهمة؛ يبقى بين المحاولات ليكمل التنزيل الملف الجزئي */
        fun workDir(ctx: Context, id: String) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "work/$id")

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "التنزيلات الجارية", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "اكتمال التنزيل", NotificationManager.IMPORTANCE_DEFAULT))
        }

        /** يبدأ العامل إن لم يكن يعمل؛ وإن كان يعمل يُلحق تشغيلاً بعده يلتقط ما أُضيف متأخراً */
        fun kick(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<DownloadWorker>().build())
        }

        /**
         * يجدول إيقاظاً لأقرب مهمة مجدولة أو مؤجلة، بشرط الشبكة المناسبة.
         * الإيقاظ عامل صغير يستدعي kick فقط، فلا يعمل عاملا تنزيل معاً.
         */
        fun scheduleNext(ctx: Context) {
            val store = Bus.store(ctx)
            val now = System.currentTimeMillis()
            val w = Bus.window(ctx)
            val minute = Bus.minuteOfDay(now)
            val pending = store.all().mapNotNull { j -> Schedule.nextAt(j, now, w, minute)?.let { j to it } }
            val wm = WorkManager.getInstance(ctx)
            if (pending.isEmpty()) { wm.cancelUniqueWork(WAKE); return }
            val at = pending.minOf { it.second }
            val wifi = pending.filter { it.second == at }.all { it.first.wifiOnly }
            val req = OneTimeWorkRequestBuilder<WakeWorker>()
                .setInitialDelay((at - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniqueWork(WAKE, ExistingWorkPolicy.REPLACE, req)
        }
    }
}

/** يوقظ الطابور في الوقت المجدول أو عند عودة الشبكة. */
class WakeWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        DownloadWorker.kick(applicationContext)
        return Result.success()
    }
}
