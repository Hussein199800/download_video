package com.sayl.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(SaylPlugin::class.java)
        registerPlugin(UpdaterPlugin::class.java)
        registerPlugin(StatusPlugin::class.java)
        super.onCreate(savedInstanceState)
        DownloadWorker.ensureChannels(this)
        askPermissions()
    }

    /** الإشعارات (أندرويد 13+) والحفظ في التنزيلات (أندرويد 9 فما دون) */
    private fun askPermissions() {
        val need = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    override fun onNewIntent(intent: Intent) {
        // قبل super: الجسر يمرر النص المشارَك للواجهة ثم يمسحه من intent
        setIntent(intent)
        super.onNewIntent(intent)
    }
}
