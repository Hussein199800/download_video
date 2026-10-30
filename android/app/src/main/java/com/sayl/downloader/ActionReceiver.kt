package com.sayl.downloader

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** أزرار الإشعار: إيقاف مؤقت، واستكمال، وإلغاء. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        Actions.apply(ctx, intent.action ?: return, id)
    }

    companion object {
        const val PAUSE = "com.sayl.downloader.PAUSE"
        const val RESUME = "com.sayl.downloader.RESUME"
        const val CANCEL = "com.sayl.downloader.CANCEL"

        fun intent(ctx: Context, action: String, id: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, (action + id).hashCode(),
            Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** الإجراءات نفسها من الإشعار ومن الواجهة. */
object Actions {
    fun apply(ctx: Context, action: String, id: String): Job? {
        val store = Bus.store(ctx)
        val j = when (action) {
            ActionReceiver.PAUSE -> store.pause(id).also { Engine.cancel(id) }
            ActionReceiver.CANCEL -> store.update(id) { if (it.active || it.state == "paused") it.copy(state = "canceled", finishedAt = System.currentTimeMillis()) else it }
                .also { Engine.cancel(id); if (it?.state == "canceled") DownloadWorker.workDir(ctx, id).deleteRecursively() }
            ActionReceiver.RESUME -> store.resume(id).also { DownloadWorker.kick(ctx); DownloadWorker.scheduleNext(ctx) }
            else -> null
        }
        j?.let { Bus.emit("state", it) }
        return j
    }
}
