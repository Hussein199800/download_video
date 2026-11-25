package com.tadaffuq.app.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tadaffuq.app.MainActivity
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.core.formatLatin
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.engine.Snapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Notifier @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_ACTIVE, ctx.getString(R.string.channel_active), NotificationManager.IMPORTANCE_LOW).apply {
                    description = ctx.getString(R.string.channel_active_desc); setShowBadge(false)
                },
                NotificationChannel(CH_DONE, ctx.getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = ctx.getString(R.string.channel_done_desc)
                },
            ),
        )
    }

    private fun canPost() = android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(action: String? = null, requestCode: Int = 0): PendingIntent =
        PendingIntent.getActivity(
            ctx, requestCode,
            Intent(ctx, MainActivity::class.java).setAction(action ?: MainActivity.ACTION_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun action(name: String) = PendingIntent.getBroadcast(
        ctx, name.hashCode(), Intent(ctx, NotificationActionReceiver::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE,
    )

    /** إشعار موحد لكل المهام العاملة، بأرقام حقيقية فقط. */
    fun summary(live: Map<Long, Snapshot>, running: Int, hold: Hold?): Notification {
        val speed = live.values.sumOf { it.speedBps }
        val done = live.values.sumOf { it.downloaded }
        val total = live.values.takeIf { v -> v.isNotEmpty() && v.all { it.total > 0 } }?.sumOf { it.total } ?: -1
        val title = if (running > 0) formatLatin(ctx.resources.getQuantityString(R.plurals.notif_running, running), arrayOf(running))
        else ctx.getString(R.string.notif_preparing)
        val text = buildString {
            append(Fmt.speed(speed))
            if (total > 0) append(" · ").append(Fmt.percent(done, total))
            if (hold != null) append(" · ").append(ctx.getString(R.string.notif_held))
        }
        return NotificationCompat.Builder(ctx, CH_ACTIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .apply {
                if (total > 0) setProgress(1000, (done * 1000 / total).toInt(), false) else if (running > 0) setProgress(0, 0, true)
            }
            .addAction(0, ctx.getString(R.string.action_pause_all), action(NotificationActionReceiver.PAUSE_ALL))
            .build()
    }

    fun completed(t: TaskEntity) {
        if (!canPost()) return
        val file = t.filePath?.let(::File)
        val open = file?.takeIf { it.exists() }?.let {
            runCatching {
                PendingIntent.getActivity(ctx, t.id.toInt(), StorageDirs.openIntent(ctx, it), PendingIntent.FLAG_IMMUTABLE)
            }.getOrNull()
        } ?: openApp()
        val text = if (t.hashOk == false) ctx.getString(R.string.notif_hash_bad) else Fmt.bytes(t.totalBytes)
        nm.notify(
            (DONE_BASE + t.id).toInt(),
            NotificationCompat.Builder(ctx, CH_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.notif_done, t.fileName))
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(open)
                .setGroup(GROUP_DONE)
                .build(),
        )
    }

    fun failed(t: TaskEntity) {
        if (!canPost()) return
        nm.notify(
            (DONE_BASE + t.id).toInt(),
            NotificationCompat.Builder(ctx, CH_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.notif_failed, t.fileName))
                .setContentText(ctx.getString(R.string.notif_failed_text))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .setGroup(GROUP_DONE)
                .build(),
        )
    }

    /** حين يمنع النظام بدء خدمة أمامية من الخلفية: نطلب من المستخدم لمسة للاستئناف بدل الادعاء بالعمل. */
    fun tapToResume() {
        if (!canPost()) return
        nm.notify(
            RESUME_ID,
            NotificationCompat.Builder(ctx, CH_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.notif_tap_resume))
                .setContentText(ctx.getString(R.string.notif_tap_resume_text))
                .setAutoCancel(true)
                .setContentIntent(openApp(MainActivity.ACTION_RESUME_ALL, 7))
                .build(),
        )
    }

    fun timedOut() {
        if (!canPost()) return
        nm.notify(
            RESUME_ID,
            NotificationCompat.Builder(ctx, CH_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.notif_timeout))
                .setContentText(ctx.getString(R.string.notif_tap_resume_text))
                .setAutoCancel(true)
                .setContentIntent(openApp(MainActivity.ACTION_RESUME_ALL, 8))
                .build(),
        )
    }

    companion object {
        const val CH_ACTIVE = "active"
        const val CH_DONE = "done"
        const val FG_ID = 1
        const val RESUME_ID = 2
        const val DONE_BASE = 1000L
        const val GROUP_DONE = "done"
    }
}
