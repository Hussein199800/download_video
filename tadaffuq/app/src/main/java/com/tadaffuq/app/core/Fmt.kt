package com.tadaffuq.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * تنسيق الأرقام والوحدات. الأرقام لاتينية دائماً (كبقية تطبيقات صاحب المشروع)،
 * وكل رقم مع وحدته معزول اتجاهياً حتى لا ينقلب ترتيبه داخل نص عربي.
 */
object Fmt {
    private const val LRI = "⁦"
    private const val PDI = "⁩"

    val arabic: Boolean get() = Locale.getDefault().language == "ar"

    fun iso(s: String) = "$LRI$s$PDI"

    private fun num(v: Double, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", v).trimEnd('0').trimEnd('.')

    fun bytes(b: Long, ar: Boolean = arabic): String {
        if (b < 0) return if (ar) "غير معروف" else "Unknown"
        val units = if (ar) listOf("بايت", "ك.ب", "م.ب", "غ.ب", "ت.ب") else listOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        val n = when { i == 0 -> v.toLong().toString(); v >= 100 -> num(v, 0); v >= 10 -> num(v, 1); else -> num(v, 2) }
        return if (ar) "$n ${units[i]}".let(::isoUnit) else iso("$n ${units[i]}")
    }

    /** في العربية: الرقم معزول LTR والوحدة العربية بعده. */
    private fun isoUnit(s: String): String {
        val n = s.substringBefore(' ')
        return iso(n) + " " + s.substringAfter(' ')
    }

    fun speed(bps: Long, ar: Boolean = arabic): String = if (ar) bytes(bps, true) + "/ث" else bytes(bps, false).dropLast(1) + "/s" + PDI

    fun duration(seconds: Long, ar: Boolean = arabic): String {
        if (seconds < 0) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (ar) when {
            h > 0 -> "${iso(h.toString())} س ${iso(m.toString())} د"
            m > 0 -> "${iso(m.toString())} د ${iso(s.toString())} ث"
            else -> "${iso(s.toString())} ث"
        } else when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    fun percent(done: Long, total: Long): String = if (total <= 0) "—" else iso("${(done * 100 / total).coerceIn(0, 100)}%")

    fun date(ms: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ROOT).format(Date(ms)).let(::iso)

    fun day(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(ms))

    /** يقبل الأرقام العربية والفاصلة العشرية العربية «٫». */
    fun parseNumber(s: String): Double? {
        val latin = s.trim().map { c ->
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                '٫', ',' -> '.'
                else -> c
            }
        }.joinToString("")
        return latin.toDoubleOrNull()
    }
}
