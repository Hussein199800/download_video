package com.sayl.downloader

/** متى تصبح المهمة مستحقة: الوقت المجدول، وإعادة المحاولة، والشبكة، ونافذة التنزيل. */
object Schedule {
    /** scheduledAt = FORCE: «ابدأ الآن» يتجاوز نافذة التنزيل */
    const val FORCE = -1L

    /** fromMin/toMin دقائق من منتصف الليل؛ قد تعبر النافذة منتصف الليل */
    data class Window(val on: Boolean, val fromMin: Int, val toMin: Int)

    data class Net(val online: Boolean, val wifi: Boolean)

    fun inWindow(w: Window, minute: Int): Boolean = when {
        !w.on || w.fromMin == w.toMin -> true
        w.fromMin < w.toMin -> minute >= w.fromMin && minute < w.toMin
        else -> minute >= w.fromMin || minute < w.toMin
    }

    private fun waitsForState(j: Job) = j.state != "queued" && j.state != "retrying" && j.state != "waiting_network"

    fun due(j: Job, now: Long, w: Window, minute: Int, net: Net): Boolean {
        if (waitsForState(j)) return false
        if (j.state == "waiting_network" && !net.online) return false
        if (j.state == "retrying" && j.retryAt > now) return false
        if (j.scheduledAt > now) return false
        if (j.wifiOnly && !net.wifi) return false
        return j.scheduledAt == FORCE || inWindow(w, minute)
    }

    /** أقرب وقت قد تصبح فيه مستحقة دون اعتبار الشبكة؛ null للموقوفة والمنتهية */
    fun nextAt(j: Job, now: Long, w: Window, minute: Int): Long? {
        if (waitsForState(j)) return null
        var t = maxOf(now, j.scheduledAt, if (j.state == "retrying") j.retryAt else 0L)
        if (j.scheduledAt != FORCE && w.on) {
            val m = ((minute + (t - now) / 60_000) % 1440).toInt()
            if (!inWindow(w, m)) t += ((w.fromMin - m + 1440) % 1440) * 60_000L
        }
        return t
    }
}
