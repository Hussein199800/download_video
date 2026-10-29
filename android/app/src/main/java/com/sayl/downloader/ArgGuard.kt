package com.sayl.downloader

/** يمنع أي خيار يغيّر مكان الحفظ أو ينفّذ أوامر؛ مسار الإخراج يضيفه Engine وحده. */
object ArgGuard {
    private val LONG = setOf("--output", "--paths", "--exec", "--exec-before-download", "--config-location", "--config-locations", "--batch-file")
    private val SHORT = setOf("-o", "-P", "-a")

    /** null = مسموح، وإلا الخيار المرفوض */
    fun check(args: List<String>): String? {
        for (t in args) {
            if (t.startsWith("--")) {
                val name = t.substringBefore('=')
                if (name in LONG) return name
            } else if (t.startsWith("-") && t.length >= 2) {
                val name = t.substring(0, 2)
                if (name in SHORT) return name
            }
        }
        return null
    }
}
