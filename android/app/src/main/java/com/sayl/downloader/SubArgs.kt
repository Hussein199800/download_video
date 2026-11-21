package com.sayl.downloader

/** خيارات الترجمة في أوامر yt-dlp (تُبنى في src/formats.js): فشل تنزيل الترجمة لا يُفشل تنزيل المقطع. */
object SubArgs {
    private val FLAGS = setOf("--write-subs", "--write-auto-subs", "--embed-subs")
    private val WITH_VALUE = setOf("--sub-langs", "--convert-subs")

    fun has(args: List<String>) = args.any { it in FLAGS }

    fun strip(args: List<String>): List<String> {
        val out = ArrayList<String>(args.size)
        var skip = false
        for (a in args) {
            if (skip) { skip = false; continue }
            if (a in WITH_VALUE) { skip = true; continue }
            if (a in FLAGS) continue
            out.add(a)
        }
        return out
    }

    fun failed(message: String?) = message?.contains("video subtitles", ignoreCase = true) == true
}
