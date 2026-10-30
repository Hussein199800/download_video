package com.sayl.downloader

import android.content.Context
import java.io.File

/** ما يتشاركه العامل والجسر: الطابور الوحيد، ومستمع الواجهة، والإعدادات الأصلية. */
object Bus {
    @Volatile
    private var store: JobStore? = null

    @Synchronized
    fun store(ctx: Context): JobStore =
        store ?: JobStore(File(ctx.applicationContext.filesDir, "jobs.json")).also { store = it }

    /** الواجهة تستمع للتقدم وتغيّر الحالة؛ null والتطبيق مغلق */
    @Volatile
    var listener: ((event: String, job: Job) -> Unit)? = null

    fun emit(event: String, job: Job) {
        runCatching { listener?.invoke(event, job) }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("sayl", Context.MODE_PRIVATE)

    fun parallel(ctx: Context): Int = prefs(ctx).getInt("parallel", 1).coerceIn(1, 3)
    fun setParallel(ctx: Context, n: Int) = prefs(ctx).edit().putInt("parallel", n.coerceIn(1, 3)).apply()

    fun maxRetries(ctx: Context): Int = prefs(ctx).getInt("retries", 5).coerceIn(0, 10)

    fun window(ctx: Context): Schedule.Window = prefs(ctx).let {
        Schedule.Window(it.getBoolean("windowOn", false), it.getInt("windowFrom", 60), it.getInt("windowTo", 420))
    }

    fun setConfig(ctx: Context, retries: Int?, window: Schedule.Window?) = prefs(ctx).edit().apply {
        retries?.let { putInt("retries", it.coerceIn(0, 10)) }
        window?.let { putBoolean("windowOn", it.on).putInt("windowFrom", it.fromMin).putInt("windowTo", it.toMin) }
    }.apply()

    /** متصل؟ وعلى شبكة غير محدودة (واي فاي)؟ */
    fun net(ctx: Context): Schedule.Net {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Schedule.Net(false, false)
        val online = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return Schedule.Net(online, online && !cm.isActiveNetworkMetered)
    }

    fun minuteOfDay(now: Long = System.currentTimeMillis()): Int =
        java.util.Calendar.getInstance().apply { timeInMillis = now }.let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }

    fun cookies(ctx: Context): File = File(ctx.applicationContext.filesDir, "cookies.txt")
}
