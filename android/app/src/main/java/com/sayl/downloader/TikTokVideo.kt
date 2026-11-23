package com.sayl.downloader

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * السيرفر 3 لفيديو تيك توك: يقرأ صفحة المنشور نفسها (`__UNIVERSAL_DATA_FOR_REHYDRATION__`) ثم ينزّل
 * رابط التشغيل بالكوكيز التي أعطتها الصفحة (دونها يرد تيك توك 403). بلا طرف ثالث.
 * التحليل دالة خالصة مختبرة على نسخة من صفحة حقيقية (tiktok-video.html).
 */
object TikTokVideo {
    private val SCRIPT = Regex("""<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    data class Video(
        val id: String, val title: String, val author: String, val url: String, val duration: Int,
        val width: Int, val height: Int, val cover: String?, val urls: List<String>,
    )

    class Unavailable(msg: String) : Exception(msg)

    fun supports(url: String) = Sites.of(url) == "tiktok" && !url.contains("/photo/")

    /** الروابط بترتيب الجودة: أعلى معدل بت، ثم رابط التشغيل (بلا علامة مائية)، ثم رابط التنزيل (بعلامة) */
    fun parse(html: String): Video {
        val raw = SCRIPT.find(html)?.groupValues?.get(1) ?: throw Unavailable("تعذّرت قراءة صفحة الفيديو؛ ربما تغيّرت صفحات تيك توك")
        val scope = JSONObject(raw).optJSONObject("__DEFAULT_SCOPE__") ?: throw Unavailable("تعذّرت قراءة صفحة الفيديو")
        val detail = scope.keys().asSequence().filter { it.contains("video") && it.contains("detail") }.map { scope.optJSONObject(it) }.firstOrNull { it != null }
            ?: throw Unavailable("الفيديو غير موجود")
        val item = detail.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
            ?: throw Unavailable(if (detail.optInt("statusCode") == 10204) "الفيديو محذوف أو غير متاح في منطقتك" else "الفيديو محذوف أو خاص")
        if (item.optJSONObject("imagePost") != null) throw Unavailable("هذا منشور صور؛ افتحه من «الصور»")
        val v = item.optJSONObject("video") ?: throw Unavailable("لا فيديو في هذا المنشور")
        val rates = v.optJSONArray("bitrateInfo") ?: JSONArray()
        val byRate = (0 until rates.length()).mapNotNull { rates.optJSONObject(it) }
            .sortedByDescending { it.optLong("Bitrate") }
            .mapNotNull { it.optJSONObject("PlayAddr")?.optJSONArray("UrlList")?.optString(0) }
        val urls = (byRate + listOf(v.optString("playAddr"), v.optString("downloadAddr"))).filter { it.startsWith("https://") }.distinct()
        if (urls.isEmpty()) throw Unavailable("تيك توك لم يُتح رابط هذا الفيديو")
        val id = item.optString("id")
        val author = item.optJSONObject("author")?.optString("uniqueId").orEmpty()
        val desc = item.optString("desc").trim()
        return Video(id, desc.ifEmpty { if (author.isNotEmpty()) "فيديو @$author" else "فيديو تيك توك" }, author,
            "https://www.tiktok.com/@$author/video/$id", v.optInt("duration"), v.optInt("width"), v.optInt("height"),
            v.optString("cover").takeIf { it.startsWith("https://") }, urls)
    }

    /** معلومات بصيغة yt-dlp للمعاينة */
    fun toJson(v: Video): String = JSONObject()
        .put("id", v.id).put("title", v.title).put("uploader", v.author).put("duration", v.duration)
        .put("thumbnail", v.cover ?: JSONObject.NULL).put("webpage_url", v.url).put("server", 3)
        .put("formats", JSONArray().put(JSONObject().put("height", v.height).put("width", v.width).put("vcodec", "h264")))
        .toString()

    data class Session(val video: Video, val cookie: String)

    private fun open(url: String, cookie: String? = null): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20000
        readTimeout = 30000
        instanceFollowRedirects = false
        setRequestProperty("User-Agent", TikTokPhotos.UA)
        setRequestProperty("Accept-Language", "ar,en;q=0.8")
        setRequestProperty("Referer", "https://www.tiktok.com/")
        cookie?.let { setRequestProperty("Cookie", it) }
    }

    /** «اسم=قيمة» من ترويسات Set-Cookie، والأحدث يغلب */
    fun mergeCookies(old: Map<String, String>, setCookies: List<String>?): Map<String, String> {
        val m = LinkedHashMap(old)
        setCookies.orEmpty().forEach { h ->
            val kv = h.substringBefore(';').trim()
            val k = kv.substringBefore('=', "").trim()
            if (k.isNotEmpty()) m[k] = kv.substringAfter('=')
        }
        return m
    }

    private fun header(m: Map<String, String>) = m.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** يفتح الصفحة (ويتبع الرابط المختصر) ويحفظ كوكيزها لطلب الفيديو */
    fun read(url: String): Session {
        var cookies = emptyMap<String, String>()
        var target = url
        repeat(6) {
            val c = open(target, header(cookies).ifEmpty { null })
            cookies = mergeCookies(cookies, c.headerFields["Set-Cookie"] ?: c.headerFields["set-cookie"])
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw Unavailable("تعذّر فتح الفيديو")
                c.disconnect()
                target = URL(URL(target), loc).toString()
                return@repeat
            }
            if (code == 404) throw Unavailable("الفيديو محذوف")
            if (code !in 200..299) throw Unavailable("تعذّر فتح الفيديو ($code)")
            val html = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return Session(parse(html), header(cookies))
        }
        throw Unavailable("تحويلات كثيرة عند فتح الفيديو")
    }

    /** ينزّل أول رابط يعمل إلى work؛ يكمل الملف الجزئي إن وُجد (إيقاف ثم استكمال) */
    fun download(s: Session, work: File, canceled: () -> Boolean, onProgress: (Long, Long) -> Unit): File {
        work.mkdirs()
        val base = "${s.video.title.lines().first().replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().take(120)} [${s.video.id}]"
        val part = File(work, "$base.mp4.part")
        var last: Exception = Unavailable("تيك توك رفض تنزيل الفيديو")
        for (u in s.video.urls) {
            val have = if (part.exists()) part.length() else 0L
            val c = open(u, s.cookie).apply { instanceFollowRedirects = true; if (have > 0) setRequestProperty("Range", "bytes=$have-") }
            val code = runCatching { c.responseCode }.getOrElse { last = it as? Exception ?: last; -1 }
            if (code !in 200..299) { c.disconnect(); if (code > 0) last = Unavailable("تيك توك رفض تنزيل الفيديو ($code)"); continue }
            val append = code == 206 && have > 0
            val total = c.contentLengthLong.let { if (it > 0) it + (if (append) have else 0) else -1 }
            var done = if (append) have else 0L
            c.inputStream.use { inp ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (canceled()) throw Server2.Canceled()
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (done == 0L || (total > 0 && done < total)) { last = IllegalStateException("انقطع تنزيل الفيديو"); continue }
            val out = File(work, "$base.mp4")
            part.renameTo(out)
            return out
        }
        throw last
    }
}
