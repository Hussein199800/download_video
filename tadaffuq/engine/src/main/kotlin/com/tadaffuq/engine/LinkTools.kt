package com.tadaffuq.engine

/** أدوات الروابط: الاستخراج من نص حر، والاستيراد من TXT/CSV، والروابط المتسلسلة. */
object LinkTools {
    private val urlRegex = Regex("""(?i)\b(?:https?|ftps?|sftp)://[^\s<>"'`{}|\\^]+""")
    private val trailing = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '»', '،', '؛')
    private val sequence = Regex("""\[(\d+)-(\d+)(?::(\d+))?]""")
    const val MAX_GENERATED = 10_000

    /** يستخرج الروابط بترتيب ظهورها دون تكرار، ويزيل علامات الترقيم الملتصقة بنهايتها. */
    fun extract(text: String): List<String> =
        urlRegex.findAll(text).map { m ->
            var u = m.value.trimEnd(*trailing)
            // قوس إغلاق جزء من الرابط فقط إن كان له قوس فتح داخله
            if (m.value.endsWith(')') && u.count { it == '(' } > u.count { it == ')' }) u += ")"
            u
        }.distinct().toList()

    /** CSV: أول خلية تبدو رابطاً في كل سطر؛ TXT: أي رابط في النص. */
    fun fromCsv(csv: String): List<String> = csv.lineSequence().mapNotNull { line ->
        line.split(',', ';', '\t').map { it.trim().trim('"') }.firstOrNull { urlRegex.matches(it) }
    }.distinct().toList()

    /**
     * «file[001-120].zip» ← 120 رابطاً بأصفار بادئة بعرض الرقم الأول، و«[1-50:5]» بخطوة 5.
     * نمط واحد فقط في الرابط، وحد أقصى [MAX_GENERATED].
     */
    fun expandSequence(pattern: String): List<String> {
        val m = sequence.find(pattern) ?: return listOf(pattern)
        val (a, b, stepRaw) = m.destructured
        val from = a.toLongOrNull() ?: return listOf(pattern)
        val to = b.toLongOrNull() ?: return listOf(pattern)
        val step = stepRaw.toLongOrNull()?.coerceAtLeast(1) ?: 1
        val width = if (a.length > 1 && a.startsWith('0')) a.length else 0
        val range = if (from <= to) (from..to step step) else (from downTo to step step)
        return range.asSequence().take(MAX_GENERATED).map { n ->
            val num = if (width > 0) n.toString().padStart(width, '0') else n.toString()
            pattern.replaceRange(m.range, num)
        }.toList()
    }

    fun hasSequence(pattern: String) = sequence.containsMatchIn(pattern)

    fun isSupported(url: String) = url.trim().lowercase().let { it.startsWith("http://") || it.startsWith("https://") }
}
