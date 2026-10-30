package com.sayl.downloader

import android.app.Application

class SaylApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // بدء العملية من جديد: ما كان «يعمل» انقطع، فيعود إلى الطابور ويكمل ملفه الجزئي
        val store = Bus.store(this)
        store.recoverInterrupted()
        if (store.all().any { it.pending }) DownloadWorker.kick(this)
        EngineUpdateWorker.schedule(this)
    }
}
