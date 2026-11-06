package com.sayl.downloader

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * بلاطة «نزّل المنسوخ» في الإعدادات السريعة.
 * أندرويد 10+ يمنع قراءة الحافظة من الخلفية، فالبلاطة تفتح سَيْل نفسه، والواجهة تقرأ الرابط وهي في المقدمة.
 */
class ClipTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "سَيْل"
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = pasteIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        const val ACTION_PASTE = "com.sayl.downloader.PASTE"

        fun pasteIntent(ctx: Context): Intent = Intent(ctx, MainActivity::class.java)
            .setAction(ACTION_PASTE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
