package com.sayl.downloader

/** يقرأ الحجم المنزّل والسرعة من سطور yt-dlp وaria2c. */
object Progress {
    private val YTDLP = Regex("""\[download]\s+([\d.]+)% of ~?\s*([\d.]+)([KMGT]?i?B)(?:\s+at\s+([\d.]+)([KMGT]?i?B)/s)?""")
    private val ARIA = Regex("""\[#\w+\s+([\d.]+)([KMGT]?i?B)/([\d.]+)([KMGT]?i?B)\(\d+%\).*?DL:([\d.]+)([KMGT]?i?B)""")

    private fun bytes(n: String, unit: String): Long {
        val m = when (unit.firstOrNull()) {
            'K' -> 1024.0
            'M' -> 1024.0 * 1024
            'G' -> 1024.0 * 1024 * 1024
            'T' -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (n.toDouble() * m).toLong()
    }

    /** (المنزّل بالبايت، السرعة بالبايت/ث) أو null لما لا يُعرف */
    fun parse(line: String): Pair<Long?, Long?> {
        YTDLP.find(line)?.let { m ->
            val (pct, total, tu, sp, su) = m.destructured
            val done = (bytes(total, tu) * pct.toDouble() / 100).toLong()
            return done to (if (sp.isNotEmpty()) bytes(sp, su) else null)
        }
        ARIA.find(line)?.let { m ->
            val g = m.groupValues
            return bytes(g[1], g[2]) to bytes(g[5], g[6])
        }
        return null to null
    }
}
