package com.sayl.downloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * السيرفر 2: NewPipe Extractor يقرأ روابط الوسائط مباشرة دون yt-dlp،
 * ثم ننزّلها بـ HTTP مع الاستكمال، ونستعمل FFmpeg للدمج أو التحويل إلى MP3.
 */
object Server2 {
    const val VERSION = "NewPipe Extractor 0.26.5"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    private const val CHUNK = 10L * 1024 * 1024

    class Canceled : Exception()

    private val canceled = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var ready = false

    private val HOSTS = Regex("""(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com|soundcloud\.com|bandcamp\.com|media\.ccc\.de)$""")

    /** مقطع واحد من خدمة يدعمها NewPipe (القوائم يتولاها السيرفر 1) */
    fun supports(url: String): Boolean {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase() ?: return false
        if (!HOSTS.containsMatchIn(host)) return false
        if (host.contains("youtube") && u.path.orEmpty().startsWith("/playlist")) return false
        return true
    }

    @Synchronized
    private fun init() {
        if (ready) return
        NewPipe.init(Http)
        ready = true
    }

    /** نفس شكل ما يعيده yt-dlp -J للحقول التي تستعملها الواجهة */
    fun info(url: String): String {
        init()
        val i = StreamInfo.getInfo(url)
        val formats = JSONArray()
        (i.videoOnlyStreams + i.videoStreams).filter { it.height > 0 }.forEach {
            formats.put(JSONObject().put("height", it.height).put("vcodec", it.codec ?: "video"))
        }
        return JSONObject()
            .put("id", i.id).put("title", i.name).put("uploader", i.uploaderName).put("duration", i.duration)
            .put("thumbnail", i.thumbnails.maxByOrNull { it.height }?.url).put("webpage_url", url)
            .put("formats", formats).put("_server", 2)
            .toString()
    }

    private fun progressive(s: Stream) = s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP

    private fun media(s: Stream, height: Int, codec: String?, bitrate: Int) =
        StreamPicker.Media(s.content, height, s.format?.suffix ?: "", codec ?: "", bitrate)

    fun cancel(id: String) {
        canceled.add(id)
    }

    /** ينزّل إلى outDir ويعيد الملف النهائي. onProgress(النسبة، الثواني المتبقية، المنزّل، الكلي، السرعة) */
    fun download(ctx: Context, job: Job, outDir: File, onProgress: (Float, Long, Long, Long, Long) -> Unit): File {
        init()
        canceled.remove(job.id)
        outDir.mkdirs()
        val choice = job.choice?.let { JSONObject(it) } ?: JSONObject().put("type", job.kind)
        val audioOnly = choice.optString("type") == "audio"
        val height = choice.optString("height").toIntOrNull() ?: 0
        val i = StreamInfo.getInfo(job.url)
        val name = safeName(i.name) + " [" + i.id + "]"

        val audios = i.audioStreams.filter(::progressive).map { media(it, 0, it.codec, it.averageBitrate) }
        val audio = StreamPicker.audio(audios, preferM4a = true)
        // خدمات مثل ساوندكلاود لا تعطي إلا HLS: يقرؤه FFmpeg مباشرة وينسخه دون إعادة ترميز
        if (audios.isEmpty() && i.videoOnlyStreams.none(::progressive)) return hls(ctx, i, audioOnly, choice, outDir, name)
        val parts = mutableListOf<Pair<StreamPicker.Media, File>>()
        var muxed = false
        if (!audioOnly) {
            val v = StreamPicker.video(i.videoOnlyStreams.filter(::progressive).map { media(it, it.height, it.codec, it.bitrate) }, height)
            if (v != null && audio != null) {
                parts += v to File(outDir, "video.part")
                parts += audio to File(outDir, "audio.part")
            } else {
                // لا توجد ملفات منفصلة: ملف مدمج جاهز
                val m = StreamPicker.video(i.videoStreams.filter { progressive(it) && !it.isVideoOnly }.map { media(it, it.height, it.codec, it.bitrate) }, height)
                    ?: throw IllegalStateException("Unable to extract: no playable stream (server 2)")
                parts += m to File(outDir, "muxed.part")
                muxed = true
            }
        } else {
            parts += (audio ?: throw IllegalStateException("Unable to extract: no audio stream (server 2)")) to File(outDir, "audio.part")
        }

        // الحجم الكلي لحساب التقدم المشترك
        val sizes = parts.map { (m, _) -> Http.size(m.url) }
        val total = sizes.sum()
        var before = 0L
        val started = System.currentTimeMillis()
        val startBytes = parts.sumOf { it.second.length() }
        parts.forEachIndexed { n, (m, f) ->
            val base = before
            fetch(job.id, m.url, f, sizes[n]) { got ->
                val done = base + got
                val secs = (System.currentTimeMillis() - started) / 1000.0
                val speed = if (secs > 0.5) ((done - startBytes) / secs).toLong() else 0L
                val eta = if (speed > 0 && total > 0) (total - done) / speed else -1
                onProgress(if (total > 0) done * 100f / total else -1f, eta, done, total, speed)
            }
            before += sizes[n]
        }

        val out: File
        when {
            muxed -> out = File(outDir, "$name.mp4").also { parts[0].second.renameTo(it) }
            !audioOnly -> {
                out = File(outDir, "$name.mp4")
                FFmpeg.run(ctx, listOf("-y", "-i", parts[0].second.path, "-i", parts[1].second.path, "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-movflags", "+faststart", out.path))
            }
            choice.optString("format") == "m4a" && audio!!.ext == "m4a" -> out = File(outDir, "$name.m4a").also { parts[0].second.renameTo(it) }
            choice.optString("format") == "m4a" -> {
                out = File(outDir, "$name.m4a")
                FFmpeg.run(ctx, listOf("-y", "-i", parts[0].second.path, "-vn", "-c:a", "aac", "-b:a", "192k", out.path))
            }
            else -> {
                out = File(outDir, "$name.mp3")
                val kbps = choice.optInt("kbps", 192).coerceIn(64, 320)
                FFmpeg.run(ctx, listOf("-y", "-i", parts[0].second.path, "-vn", "-c:a", "libmp3lame", "-b:a", "${kbps}k", out.path))
            }
        }
        parts.forEach { it.second.delete() }
        return out
    }

    private fun hls(ctx: Context, i: StreamInfo, audioOnly: Boolean, choice: JSONObject, outDir: File, name: String): File {
        val hlsUrl = (if (audioOnly) i.audioStreams else i.videoStreams + i.audioStreams)
            .firstOrNull { it.isUrl && it.deliveryMethod == DeliveryMethod.HLS }?.content
            ?: throw IllegalStateException("Unable to extract: no playable stream (server 2)")
        val mp3 = audioOnly && choice.optString("format") != "m4a"
        val out = File(outDir, name + if (!audioOnly) ".mp4" else if (mp3) ".mp3" else ".m4a")
        val codec = if (mp3) listOf("-vn", "-c:a", "libmp3lame", "-b:a", "${choice.optInt("kbps", 192).coerceIn(64, 320)}k") else listOf("-c", "copy")
        FFmpeg.run(ctx, listOf("-y", "-user_agent", UA, "-i", hlsUrl) + codec + out.path)
        return out
    }

    /** تنزيل على قطع 10 م.ب (يتجنب خنق يوتيوب) مع الاستكمال من حجم الملف الجزئي */
    private fun fetch(id: String, url: String, dest: File, size: Long, onBytes: (Long) -> Unit) {
        RandomAccessFile(dest, "rw").use { raf ->
            var pos = raf.length()
            if (size in 1..pos) { onBytes(pos); return }
            raf.seek(pos)
            val buf = ByteArray(128 * 1024)
            while (size <= 0 || pos < size) {
                val end = if (size > 0) minOf(pos + CHUNK, size) - 1 else pos + CHUNK - 1
                val c = Http.open(url, "bytes=$pos-$end")
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP Error ${c.responseCode} (server 2)")
                var read = 0L
                c.inputStream.use { input ->
                    while (true) {
                        if (id in canceled) throw Canceled()
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        pos += n
                        read += n
                        onBytes(pos)
                    }
                }
                c.disconnect()
                if (read == 0L) break
            }
        }
    }

    // نفس قواعد --windows-filenames تقريباً، مع قصّ الطول
    private fun safeName(s: String) = s.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().take(120).ifEmpty { "video" }

    /** اتصال HTTP لـ NewPipe ولتنزيل الملفات */
    object Http : Downloader() {
        fun open(url: String, range: String? = null, method: String = "GET"): HttpURLConnection {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", userAgent(url))
            range?.let { c.setRequestProperty("Range", it) }
            return c
        }

        /** روابط يوتيوب مرتبطة بالعميل الذي أصدرها؛ نرسل User-Agent العميل نفسه كما يفعل تطبيق NewPipe */
        private fun userAgent(url: String): String = when {
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(null)
            YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(null)
            YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(null)
            else -> UA
        }

        /** الحجم من Content-Range أو Content-Length؛ 0 إن لم يُعرف */
        fun size(url: String): Long {
            val c = open(url, "bytes=0-0")
            return try {
                c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: c.contentLengthLong.coerceAtLeast(0)
            } finally {
                c.disconnect()
            }
        }

        override fun execute(request: Request): Response {
            val c = open(request.url(), method = request.httpMethod())
            request.headers().forEach { (k, vs) -> c.setRequestProperty(k, vs.joinToString(", ")) }
            request.dataToSend()?.let { body ->
                c.doOutput = true
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val headers = c.headerFields.filterKeys { it != null }
            return Response(code, c.responseMessage ?: "", headers, text, c.url.toString()).also { c.disconnect() }
        }
    }
}

/** تشغيل FFmpeg المضمّن في youtubedl-android مباشرة، بالبيئة نفسها التي يستعملها yt-dlp. */
object FFmpeg {
    fun run(ctx: Context, args: List<String>) {
        Engine.init(ctx)
        val app = ctx.applicationContext
        val base = File(app.noBackupFilesDir, "youtubedl-android/packages")
        val bin = File(app.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val pb = ProcessBuilder(listOf(bin.path, "-hide_banner", "-loglevel", "error") + args).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(base, "$it/usr/lib").path }
        pb.environment()["TMPDIR"] = app.cacheDir.path
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw IllegalStateException("FFmpeg: ${out.takeLast(500)}")
    }
}
