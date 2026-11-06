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
        // المعالجة كذلك: ما انقطع يعود إلى الطابور ويبدأ من جديد (الأصل لم يُمس)
        val tasks = Bus.tasks(this)
        tasks.recoverInterrupted()
        if (tasks.all().any { it.state == "queued" }) MediaWorker.kick(this)
    }
}
