package com.sayl.downloader

import java.net.URI

/**
 * المنصة من رابط المنشور الأصلي (لا من رابط الوسائط أو CDN)، ومجلد حفظها.
 * المضيفات مطابقة لـ src/links.js حرفياً؛ اختبار وحدة في الواجهة يتحقق من تطابقهما.
 */
object Sites {
    val HOSTS = linkedMapOf(
        "youtube" to listOf("youtube.com", "youtu.be", "youtube-nocookie.com"),
        "facebook" to listOf("facebook.com", "fb.watch", "fb.com"),
        "tiktok" to listOf("tiktok.com"),
        "instagram" to listOf("instagram.com", "instagr.am"),
        "x" to listOf("x.com", "twitter.com"),
    )

    val FOLDERS = mapOf(
        "youtube" to "يوتيوب", "facebook" to "فيسبوك", "tiktok" to "تيك توك",
        "instagram" to "إنستغرام", "x" to "إكس", "other" to "مواقع أخرى",
    )

    fun of(url: String?): String {
        val host = runCatching { URI(url!!.trim()).host?.lowercase() }.getOrNull() ?: return "other"
        return HOSTS.entries.firstOrNull { (_, hs) -> hs.any { host == it || host.endsWith(".$it") } }?.key ?: "other"
    }

    /** مجلد المنصة: هوية الواجهة إن كانت معروفة، وإلا من الرابط (طوابير قديمة بلا هوية) */
    fun folder(site: String?, url: String?): String =
        FOLDERS[site?.takeIf { it in FOLDERS && it != "other" } ?: of(url)]!!
}
