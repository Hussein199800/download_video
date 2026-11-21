package com.sayl.downloader

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * منشورات الصور في تيك توك (لا يدعمها yt-dlp): تُقرأ من بيانات الصفحة نفسها
 * (`__UNIVERSAL_DATA_FOR_REHYDRATION__`) دون تسجيل دخول، بترتيب المصدر.
 * التحليل دالة خالصة مختبرة على نسخة من صفحة حقيقية (tiktok-photo.html).
 */
object TikTokPhotos {
    const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private val SCRIPT = Regex("""<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    data class Item(val index: Int, val urls: List<String>, val width: Int, val height: Int)
    data class Album(val id: String, val title: String, val author: String, val url: String, val items: List<Item>, val audio: String?)

    class NotPhoto(msg: String) : Exception(msg)

    fun isPhotoUrl(url: String) = Sites.of(url) == "tiktok" && url.contains("/photo/")

    /** null = الصفحة ليست منشور صور (فيديو مثلاً)؛ NotPhoto برسالة عربية = محذوف أو خاص أو صيغة غير مفهومة */
    fun parse(html: String): Album? {
        val raw = SCRIPT.find(html)?.groupValues?.get(1) ?: throw NotPhoto("تعذّرت قراءة صفحة المنشور؛ ربما تغيّرت صفحات تيك توك")
        val scope = JSONObject(raw).optJSONObject("__DEFAULT_SCOPE__") ?: throw NotPhoto("تعذّرت قراءة صفحة المنشور")
        val detail = scope.keys().asSequence().filter { it.contains("video") && it.contains("detail") }.map { scope.optJSONObject(it) }.firstOrNull { it != null }
            ?: throw NotPhoto("المنشور غير موجود")
        val item = detail.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
            ?: throw NotPhoto("المنشور محذوف أو خاص")
        val images = item.optJSONObject("imagePost")?.optJSONArray("images") ?: return null
        val id = item.optString("id")
        val author = item.optJSONObject("author")?.optString("uniqueId").orEmpty()
        val items = (0 until images.length()).mapNotNull { i ->
            val im = images.getJSONObject(i)
            val list = im.optJSONObject("imageURL")?.optJSONArray("urlList") ?: return@mapNotNull null
            val urls = (0 until list.length()).map { list.getString(it) }.filter { it.startsWith("https://") }
            if (urls.isEmpty()) null else Item(i + 1, urls, im.optInt("imageWidth"), im.optInt("imageHeight"))
        }
        if (items.isEmpty()) throw NotPhoto("لا صور في هذا المنشور")
        val desc = item.optString("desc").trim()
        val audio = item.optJSONObject("music")?.optString("playUrl")?.takeIf { it.startsWith("https://") }
        return Album(id, desc.ifEmpty { if (author.isNotEmpty()) "منشور @$author" else "منشور تيك توك" }, author,
            "https://www.tiktok.com/@$author/photo/$id", items, audio)
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20000
        readTimeout = 30000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", UA)
        setRequestProperty("Accept-Language", "ar,en;q=0.8")
        setRequestProperty("Referer", "https://www.tiktok.com/")
    }

    /** يقرأ المنشور (يتبع الرابط المختصر حتى صفحته) */
    fun read(url: String): Album {
        var c = open(url)
        // تحويل بين نطاقين (vm.tiktok.com ← www.tiktok.com) لا يتبعه HttpURLConnection دائماً
        repeat(4) {
            if (c.responseCode !in 300..399) return@repeat
            val loc = c.getHeaderField("Location") ?: return@repeat
            c.disconnect()
            c = open(URL(URL(url), loc).toString())
        }
        if (c.responseCode == 404) throw NotPhoto("المنشور محذوف")
        if (c.responseCode !in 200..299) throw NotPhoto("تعذّر فتح المنشور (${c.responseCode})")
        val html = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        return parse(html) ?: throw NotPhoto("هذا منشور فيديو وليس صوراً")
    }

    /** نتيجة القراءة للواجهة: نوع album بترتيب المصدر */
    fun toJson(a: Album): String = JSONObject()
        .put("_type", "album").put("id", a.id).put("title", a.title).put("uploader", a.author).put("webpage_url", a.url).put("site", "tiktok")
        .put("audio", a.audio ?: JSONObject.NULL)
        .put("entries", JSONArray(a.items.map { JSONObject().put("index", it.index).put("urls", JSONArray(it.urls)).put("width", it.width).put("height", it.height) }))
        .toString()

    private val EXT = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/heic" to "heic", "audio/mpeg" to "mp3", "audio/mp4" to "m4a")

    /** امتداد من بايتات الملف الفعلية (لا من الرابط) */
    fun sniff(head: ByteArray): String? = when {
        head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "jpg"
        head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> "png"
        head.size >= 12 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        head.size >= 12 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> if (String(head, 8, 4, Charsets.ISO_8859_1).startsWith("hei")) "heic" else "m4a"
        head.size >= 3 && (String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" || (head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0)) -> "mp3"
        else -> null
    }

    class Expired : Exception("انتهت صلاحية رابط الصورة")

    /**
     * ينزّل أول رابط يعمل من القائمة إلى work باسم base والامتداد الفعلي.
     * Expired إن رفضت كل الروابط (403/404/410) ليعيد العامل قراءة المنشور.
     */
    fun fetch(urls: List<String>, work: File, base: String, canceled: () -> Boolean, onProgress: (Long, Long) -> Unit): File {
        work.mkdirs()
        var last: Exception = Expired()
        for (u in urls) {
            val c = open(u)
            val code = runCatching { c.responseCode }.getOrElse { last = it as? Exception ?: Expired(); -1 }
            if (code in listOf(403, 404, 410)) { c.disconnect(); last = Expired(); continue }
            if (code !in 200..299) { c.disconnect(); last = IllegalStateException("تعذّر تنزيل الصورة ($code)"); continue }
            val total = c.contentLengthLong
            val tmp = File(work, "$base.part")
            var done = 0L
            var head = ByteArray(0)
            c.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        if (canceled()) throw Server2.Canceled()
                        val n = inp.read(buf)
                        if (n < 0) break
                        if (head.size < 16) head += buf.copyOf(minOf(n, 16 - head.size))
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (done == 0L || (total > 0 && done < total)) { tmp.delete(); last = IllegalStateException("انقطع تنزيل الصورة"); continue }
            val ext = sniff(head) ?: EXT[c.contentType?.substringBefore(';')?.trim()] ?: "jpg"
            val out = File(work, "$base.$ext")
            tmp.renameTo(out)
            return out
        }
        throw last
    }
}
