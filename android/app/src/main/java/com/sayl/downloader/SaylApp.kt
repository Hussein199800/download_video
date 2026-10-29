package com.sayl.downloader

import android.app.Application

class SaylApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // إن أُغلق التطبيق أثناء التنزيل يكمل العامل ما بقي في الطابور
        if (Bus.store(this).all().any { it.active }) DownloadWorker.kick(this)
    }
}
