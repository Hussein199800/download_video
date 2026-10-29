package com.sayl.downloader

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** تحديث yt-dlp تلقائياً مرة كل أسبوع، لأن المواقع تغيّر طريقة عملها باستمرار. */
class EngineUpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        // لا نحدّث أثناء تنزيل جارٍ حتى لا يتبدل الملف تحته
        if (Bus.store(applicationContext).all().any { it.state == "running" }) return Result.retry()
        return runCatching { Engine.update(applicationContext) }.fold({ Result.success() }, { Result.retry() })
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<EngineUpdateWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sayl-engine-update", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
