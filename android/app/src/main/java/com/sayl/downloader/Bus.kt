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

    fun cookies(ctx: Context): File = File(ctx.applicationContext.filesDir, "cookies.txt")
}
