package com.tadaffuq.app.download

import android.os.PowerManager
import com.tadaffuq.data.NetKind
import com.tadaffuq.data.Settings

/** سبب إيقاف الطابور مؤقتاً؛ منطق خالص قابل للاختبار. */
enum class Hold { NO_NETWORK, WIFI_ONLY, LOW_BATTERY, NOT_CHARGING, TOO_HOT, DAILY_LIMIT, MONTHLY_LIMIT }

object Policy {
    /**
     * @param cellularToday/Month استهلاك بيانات الجوال الفعلي (الحدود تخص بيانات الجوال، لا Wi-Fi).
     */
    fun hold(s: Settings, d: DeviceState, cellularToday: Long, cellularMonth: Long, taskWifiOnly: Boolean = false): Hold? {
        if (!d.connected) return Hold.NO_NETWORK
        val onCellular = d.net == NetKind.CELLULAR || (d.net == NetKind.OTHER && d.metered)
        if ((s.wifiOnly || taskWifiOnly) && (onCellular || d.metered)) return Hold.WIFI_ONLY
        if (s.onlyWhileCharging && !d.charging) return Hold.NOT_CHARGING
        if (s.pauseOnLowBattery > 0 && !d.charging && d.batteryPercent <= s.pauseOnLowBattery) return Hold.LOW_BATTERY
        if (s.pauseWhenHot && d.thermal >= PowerManager.THERMAL_STATUS_SEVERE) return Hold.TOO_HOT
        if (onCellular && s.dailyLimitBytes > 0 && cellularToday >= s.dailyLimitBytes) return Hold.DAILY_LIMIT
        if (onCellular && s.monthlyLimitBytes > 0 && cellularMonth >= s.monthlyLimitBytes) return Hold.MONTHLY_LIMIT
        return null
    }

    /** الحرارة المرتفعة (دون الحرجة) تخفض عدد الاتصالات بدل الإيقاف. */
    fun connectionCap(d: DeviceState, configured: Int): Int = when {
        d.thermal >= PowerManager.THERMAL_STATUS_MODERATE -> minOf(configured, 2)
        d.thermal >= PowerManager.THERMAL_STATUS_LIGHT -> minOf(configured, 4)
        !d.charging && d.batteryPercent in 1..25 -> minOf(configured, 4)
        else -> configured
    }
}
