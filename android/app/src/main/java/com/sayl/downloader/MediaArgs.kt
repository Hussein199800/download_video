package com.sayl.downloader

import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * منطق المعالجة الخالص (بلا أندرويد) حتى يُختبر على الحاسوب بـ FFmpeg حقيقي:
 * قراءة خصائص الملف من ffprobe، وقرار النسخ أو إعادة الترميز، وبناء أوامر FFmpeg، وحساب الضغط.
 * المبدأ: لا يُعاد الترميز ما دام تغيير الحاوية يكفي (Remux)، ولا يُغيَّر حجم الصورة أبداً.
 */
object MediaArgs {

    data class VStream(val codec: String, val width: Int, val height: Int, val fps: Double, val bitrate: Long, val rotation: Int = 0)
    data class AStream(val codec: String, val sampleRate: Int, val channels: Int, val bitrate: Long)

    data class Probe(
        val duration: Double,       // بالثواني
        val size: Long,
        val bitrate: Long,          // الكلي بالبت/ث
        val format: String,         // اسم الحاوية من ffprobe
        val video: VStream?,
        val audio: AStream?,
        val audioCount: Int = if (audio != null) 1 else 0,
        val subtitleCount: Int = 0,
    ) {
        val hasVideo get() = video != null
        val hasAudio get() = audio != null

        fun toJson(): JSONObject = JSONObject()
            .put("duration", duration).put("size", size).put("bitrate", bitrate).put("format", format)
            .put("audioCount", audioCount).put("subtitleCount", subtitleCount)
            .put("video", video?.let { JSONObject().put("codec", it.codec).put("width", it.width).put("height", it.height).put("fps", it.fps).put("bitrate", it.bitrate).put("rotation", it.rotation) })
            .put("audio", audio?.let { JSONObject().put("codec", it.codec).put("sampleRate", it.sampleRate).put("channels", it.channels).put("bitrate", it.bitrate) })
    }

    private fun fps(rate: String?): Double {
        val p = rate?.split('/') ?: return 0.0
        val n = p.getOrNull(0)?.toDoubleOrNull() ?: return 0.0
        val d = p.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        return if (d > 0) n / d else 0.0
    }

    /** خرج «ffprobe -show_format -show_streams -of json» */
    fun parseProbe(json: String): Probe {
        val o = JSONObject(json)
        val f = o.optJSONObject("format") ?: JSONObject()
        val streams = o.optJSONArray("streams")
        var v: VStream? = null
        var a: AStream? = null
        var audioCount = 0
        var subs = 0
        for (i in 0 until (streams?.length() ?: 0)) {
            val s = streams!!.getJSONObject(i)
            val disp = s.optJSONObject("disposition")
            when (s.optString("codec_type")) {
                // صورة الغلاف المدمجة في MP3 أو M4A ليست فيديو
                "video" -> if (v == null && disp?.optInt("attached_pic") != 1) {
                    val rot = s.optJSONObject("tags")?.optString("rotate")?.toIntOrNull()
                        ?: s.optJSONArray("side_data_list")?.let { l -> (0 until l.length()).firstNotNullOfOrNull { l.getJSONObject(it).opt("rotation")?.toString()?.toDoubleOrNull()?.toInt() } }
                        ?: 0
                    val r = fps(s.optString("avg_frame_rate")).takeIf { it > 0 } ?: fps(s.optString("r_frame_rate"))
                    v = VStream(s.optString("codec_name"), s.optInt("width"), s.optInt("height"), r, s.optString("bit_rate").toLongOrNull() ?: 0, rot)
                }
                "audio" -> {
                    audioCount++
                    if (a == null) a = AStream(s.optString("codec_name"), s.optString("sample_rate").toIntOrNull() ?: 0, s.optInt("channels"), s.optString("bit_rate").toLongOrNull() ?: 0)
                }
                "subtitle" -> subs++
            }
        }
        val duration = f.optString("duration").toDoubleOrNull() ?: 0.0
        val size = f.optString("size").toLongOrNull() ?: 0
        val total = f.optString("bit_rate").toLongOrNull() ?: if (duration > 0) (size * 8 / duration).toLong() else 0
        // حاويات مثل MKV وWebM لا تذكر معدل الفيديو: نقدّره من الكلي ناقص الصوت
        if (v != null && v.bitrate <= 0 && total > 0) v = v.copy(bitrate = max(0, total - (a?.bitrate?.takeIf { it > 0 } ?: 128_000)))
        return Probe(duration, size, total, f.optString("format_name"), v, a, audioCount, subs)
    }

    // ---------- الحاويات وما تقبله من ترميزات دون إعادة ترميز ----------
    val VIDEO_TARGETS = listOf("mp4", "mkv", "mov", "webm", "avi")
    val AUDIO_TARGETS = listOf("mp3", "m4a", "aac", "wav", "flac", "opus")

    private val V_OK = mapOf(
        "mp4" to setOf("h264", "hevc", "mpeg4", "av1", "vp9"),
        "mov" to setOf("h264", "hevc", "mpeg4", "mjpeg", "prores"),
        "mkv" to null, // يقبل كل شيء
        "webm" to setOf("vp8", "vp9", "av1"),
        "avi" to setOf("h264", "mpeg4", "mjpeg", "msmpeg4v3"),
    )
    private val A_OK = mapOf(
        "mp4" to setOf("aac", "mp3", "ac3", "eac3", "opus", "alac", "flac"),
        "mov" to setOf("aac", "mp3", "ac3", "alac", "pcm_s16le"),
        "mkv" to null,
        "webm" to setOf("opus", "vorbis"),
        "avi" to setOf("mp3", "ac3", "pcm_s16le"),
    )

    fun videoCopyOk(target: String, codec: String?) = codec != null && (V_OK[target]?.contains(codec) ?: true)
    fun audioCopyOk(target: String, codec: String?) = codec != null && (A_OK[target]?.contains(codec) ?: true)

    /** نوع العملية في تحويل الفيديو: نسخ كامل، أو نسخ الصورة وترميز الصوت، أو ترميز الصورة */
    enum class Mode { COPY, AUDIO_ONLY, ENCODE }

    fun videoMode(target: String, p: Probe): Mode = when {
        !videoCopyOk(target, p.video?.codec) -> Mode.ENCODE
        p.hasAudio && !audioCopyOk(target, p.audio?.codec) -> Mode.AUDIO_ONLY
        else -> Mode.COPY
    }

    private fun audioEncoder(target: String) = when (target) {
        "webm" -> listOf("-c:a", "libopus", "-b:a", "128k")
        "avi" -> listOf("-c:a", "libmp3lame", "-b:a", "192k")
        else -> listOf("-c:a", "aac", "-b:a", "160k")
    }

    private fun videoEncoder(target: String) = when (target) {
        // VP9 بأسرع إعداد مناسب للهاتف؛ الجودة بمعامل ثابت
        "webm" -> listOf("-c:v", "libvpx-vp9", "-b:v", "0", "-crf", "32", "-deadline", "realtime", "-cpu-used", "8", "-row-mt", "1", "-pix_fmt", "yuv420p")
        // MPEG-4 Part 2 هو الأوسع توافقاً داخل AVI
        "avi" -> listOf("-c:v", "mpeg4", "-q:v", "3", "-pix_fmt", "yuv420p")
        else -> listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p")
    }

    private fun containerFlags(target: String) = when (target) {
        "mp4", "mov" -> listOf("-movflags", "+faststart")
        else -> emptyList()
    }

    /** تحويل حاوية الفيديو: Remux إن أمكن، وإلا يُعاد ترميز ما يلزم فقط. الأبعاد والإطارات كما هي. */
    fun convertVideo(input: String, output: String, target: String, p: Probe): List<String> {
        val mode = videoMode(target, p)
        val a = mutableListOf("-y", "-hide_banner", "-i", input, "-map", "0:v:0")
        if (p.hasAudio) a += listOf("-map", "0:a:0?")
        a += listOf("-map_metadata", "0", "-sn", "-dn")
        a += if (mode == Mode.ENCODE) videoEncoder(target) else listOf("-c:v", "copy")
        if (p.hasAudio) a += if (mode == Mode.COPY) listOf("-c:a", "copy") else if (audioCopyOk(target, p.audio?.codec)) listOf("-c:a", "copy") else audioEncoder(target)
        // H.265 داخل MP4/MOV يحتاج وسم hvc1 حتى يعمل على مشغلات أبل وبعض الهواتف
        if (mode != Mode.ENCODE && p.video?.codec == "hevc" && target in setOf("mp4", "mov")) a += listOf("-tag:v", "hvc1")
        a += containerFlags(target)
        a += output
        return a
    }

    /** إعدادات الصوت الناتج: الصيغة، ومعدل البت (ك.ب/ث، 0 = تلقائي)، ومعدل العينة (0 = كما هو) */
    data class AudioOpts(val format: String, val kbps: Int = 0, val sampleRate: Int = 0, val start: Double = -1.0, val end: Double = -1.0)

    private val OPUS_RATES = listOf(48000, 24000, 16000, 12000, 8000)

    /** نسخ الصوت كما هو ممكن فقط إن كانت الصيغة نفسها ولم يُطلب تغيير الجودة */
    fun audioCopy(o: AudioOpts, codec: String?): Boolean {
        if (o.kbps > 0 || o.sampleRate > 0) return false
        return when (o.format) {
            "m4a", "aac" -> codec == "aac"
            "mp3" -> codec == "mp3"
            "opus" -> codec == "opus"
            "flac" -> codec == "flac"
            "wav" -> codec == "pcm_s16le"
            else -> false
        }
    }

    fun audioCodecArgs(o: AudioOpts, codec: String?): List<String> {
        if (audioCopy(o, codec)) return listOf("-c:a", "copy")
        val br = o.kbps.takeIf { it > 0 }
        val a = when (o.format) {
            "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", "${br ?: 192}k")
            "m4a", "aac" -> listOf("-c:a", "aac", "-b:a", "${br ?: 192}k")
            "opus" -> listOf("-c:a", "libopus", "-b:a", "${br ?: 128}k")
            "flac" -> listOf("-c:a", "flac")
            "wav" -> listOf("-c:a", "pcm_s16le")
            else -> throw IllegalArgumentException("صيغة صوت غير مدعومة: ${o.format}")
        }.toMutableList()
        val rate = when {
            o.format == "opus" && o.sampleRate > 0 -> OPUS_RATES.minByOrNull { kotlin.math.abs(it - o.sampleRate) }!!
            o.format == "opus" -> 48000
            else -> o.sampleRate
        }
        if (rate > 0) a += listOf("-ar", rate.toString())
        return a
    }

    private fun sec(t: Double) = String.format(Locale.US, "%.3f", t)

    /** استخراج الصوت أو تحويله، كاملاً أو من مقطع محدد */
    fun audio(input: String, output: String, o: AudioOpts, p: Probe): List<String> {
        if (!p.hasAudio) throw IllegalArgumentException("الملف لا يحتوي صوتاً")
        val a = mutableListOf("-y", "-hide_banner")
        if (o.start > 0) a += listOf("-ss", sec(o.start))
        if (o.end > 0) a += listOf("-to", sec(o.end))
        a += listOf("-i", input, "-map", "0:a:0", "-vn", "-sn", "-dn", "-map_metadata", "0")
        a += audioCodecArgs(o, p.audio?.codec)
        if (o.format == "m4a") a += listOf("-movflags", "+faststart")
        // ADTS لملف .aac الخام
        if (o.format == "aac") a += listOf("-f", "adts")
        a += output
        return a
    }

    /** قص سريع دون إعادة ترميز: يبدأ من أقرب إطار مفتاحي قبل البداية */
    fun trimCopy(input: String, output: String, start: Double, end: Double, target: String): List<String> {
        val a = mutableListOf("-y", "-hide_banner", "-ss", sec(start), "-to", sec(end), "-i", input,
            "-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy", "-sn", "-dn", "-map_metadata", "0", "-avoid_negative_ts", "make_zero")
        a += containerFlags(target)
        a += output
        return a
    }

    /** قص دقيق بإعادة الترميز (بديل المعالج حين لا يتوفر الترميز بالعتاد) */
    fun trimPrecise(input: String, output: String, start: Double, end: Double, p: Probe): List<String> {
        val a = mutableListOf("-y", "-hide_banner", "-ss", sec(start), "-i", input, "-t", sec(max(0.1, end - start)),
            "-map", "0:v:0?", "-map", "0:a:0?", "-sn", "-dn", "-map_metadata", "0")
        if (p.hasVideo) a += listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p")
        if (p.hasAudio) a += listOf("-c:a", "aac", "-b:a", "${max(128, min(320, ((p.audio?.bitrate ?: 0) / 1000).toInt()))}k")
        a += listOf("-movflags", "+faststart", output)
        return a
    }

    /** هل يمكن دمج المقاطع بالنسخ؟ يلزم تطابق الترميز والأبعاد والصوت */
    fun canConcatCopy(list: List<Probe>): Boolean {
        if (list.size < 2) return false
        val f = list.first()
        val fv = f.video ?: return false
        return list.all { p ->
            val v = p.video ?: return@all false
            v.codec == fv.codec && v.width == fv.width && v.height == fv.height && v.rotation == fv.rotation &&
                p.hasAudio == f.hasAudio && (!p.hasAudio || (p.audio!!.codec == f.audio!!.codec && p.audio.sampleRate == f.audio.sampleRate && p.audio.channels == f.audio.channels))
        }
    }

    /** ملف قائمة الدمج لـ concat demuxer؛ الفاصلة العليا في المسار تُهرَّب */
    fun concatList(paths: List<String>): String = paths.joinToString("\n") { "file '" + it.replace("'", "'\\''") + "'" } + "\n"

    fun concatCopy(listFile: String, output: String): List<String> =
        listOf("-y", "-hide_banner", "-f", "concat", "-safe", "0", "-i", listFile, "-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-movflags", "+faststart", output)

    /**
     * دمج مقاطع مختلفة بإعادة الترميز (بديل المعالج): كل مقطع يُحجَّم داخل أبعاد الأول مع حواف،
     * والصوت المفقود يُستبدل بصمت حتى يبقى التوقيت متزامناً.
     */
    fun concatEncode(inputs: List<String>, output: String, list: List<Probe>): List<String> {
        val f = list.first().video ?: throw IllegalArgumentException("الدمج للفيديو فقط")
        // الأبعاد زوجية دائماً لـ yuv420p
        val w = f.width / 2 * 2
        val h = f.height / 2 * 2
        val fps = if (f.fps in 1.0..120.0) String.format(Locale.US, "%.3f", f.fps) else "30"
        val a = mutableListOf("-y", "-hide_banner")
        inputs.forEach { a += listOf("-i", it) }
        val filters = StringBuilder()
        val pairs = StringBuilder()
        list.forEachIndexed { i, p ->
            filters.append("[$i:v:0]scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=$fps,format=yuv420p[v$i];")
            if (p.hasAudio) filters.append("[$i:a:0]aresample=48000,aformat=channel_layouts=stereo[a$i];")
            else filters.append("anullsrc=r=48000:cl=stereo,atrim=duration=${sec(max(0.1, p.duration))}[a$i];")
            pairs.append("[v$i][a$i]")
        }
        filters.append("${pairs}concat=n=${list.size}:v=1:a=1[v][a]")
        a += listOf("-filter_complex", filters.toString(), "-map", "[v]", "-map", "[a]",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", output)
        return a
    }

    /** لقطة ثابتة من لحظة محددة (بديل MediaMetadataRetriever) */
    fun frame(input: String, output: String, at: Double): List<String> {
        val png = output.endsWith(".png", true)
        return listOf("-y", "-hide_banner", "-ss", sec(max(0.0, at)), "-i", input, "-frames:v", "1", "-an", "-sn") +
            (if (png) emptyList() else listOf("-q:v", "2")) + output
    }

    // ---------- الضغط ----------
    enum class Level { SMART, LIGHT, MEDIUM, STRONG }

    data class CompressPlan(val videoBps: Long, val audioBps: Long, val estimate: Long, val worth: Boolean)

    /**
     * معدل البت المستهدف للصورة دون تغيير الأبعاد أو الإطارات.
     * الذكي يحسب ما تحتاجه الدقة فعلاً (بتات لكل بكسل) ولا يتجاوز 70% من الأصل؛
     * والبقية نسب ثابتة من الأصل. لا ينزل أبداً تحت حدّ يحفظ الوضوح.
     */
    fun compressPlan(p: Probe, level: Level, hevc: Boolean): CompressPlan {
        val v = p.video ?: throw IllegalArgumentException("الضغط للفيديو فقط")
        val pixels = v.width.toLong() * v.height
        val fps = if (v.fps in 1.0..240.0) v.fps else 30.0
        val src = if (v.bitrate > 0) v.bitrate else max(500_000, p.bitrate - (p.audio?.bitrate ?: 128_000))
        val bpp = if (hevc) 0.055 else 0.08
        val need = (pixels * fps * bpp).toLong()
        val target = when (level) {
            Level.SMART -> min((src * 0.70).toLong(), need)
            Level.LIGHT -> (src * 0.75).toLong()
            Level.MEDIUM -> (src * 0.50).toLong()
            Level.STRONG -> (src * 0.30).toLong()
        }
        // حد أدنى يتناسب مع الدقة: نحو 0.025 بت لكل بكسل
        val floor = max(250_000L, (pixels * fps * 0.025).toLong())
        val vb = max(floor, target)
        // الصوت: يُنسخ إن كان معقولاً، وإلا AAC بـ 128 ك.ب/ث
        val srcA = p.audio?.bitrate?.takeIf { it > 0 } ?: if (p.hasAudio) 128_000 else 0
        val ab = if (!p.hasAudio) 0 else if (srcA <= 192_000) srcA else 128_000
        val est = ((vb + ab) * p.duration / 8).roundToLong()
        // لا فائدة إن كان التوفير أقل من 10%
        val worth = vb < src * 0.9
        return CompressPlan(vb, ab, est, worth)
    }

    /** ضغط بالمعالج (بديل الترميز بالعتاد): معدل بت مستهدف مع سقف يمنع القفزات */
    fun compressCpu(input: String, output: String, plan: CompressPlan, p: Probe): List<String> {
        val vb = plan.videoBps
        val a = mutableListOf("-y", "-hide_banner", "-i", input, "-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn", "-map_metadata", "0",
            "-c:v", "libx264", "-preset", "veryfast", "-b:v", "$vb", "-maxrate", "${vb * 3 / 2}", "-bufsize", "${vb * 2}", "-pix_fmt", "yuv420p")
        if (p.hasAudio) a += if (p.audio?.codec == "aac" && plan.audioBps == p.audio.bitrate) listOf("-c:a", "copy") else listOf("-c:a", "aac", "-b:a", "${max(64_000, plan.audioBps)}")
        a += listOf("-movflags", "+faststart", output)
        return a
    }

    /** تقدم FFmpeg من سطر «time=00:01:02.50» نسبةً إلى المدة */
    private val TIME = Regex("""time=(\d+):(\d{2}):(\d{2}(?:\.\d+)?)""")

    fun progressOf(line: String, duration: Double): Float? {
        if (duration <= 0) return null
        val m = TIME.find(line) ?: return null
        val t = m.groupValues[1].toInt() * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toDouble()
        return (t / duration * 100).toFloat().coerceIn(0f, 100f)
    }

    /** امتداد الناتج حسب العملية */
    fun extFor(op: String, target: String?): String = when (op) {
        "frame" -> if (target == "png") "png" else "jpg"
        "compress", "merge", "trim" -> target ?: "mp4"
        else -> target ?: "mp4"
    }

    /** اسم ملف ناتج لا يستبدل الأصل أبداً: «اسم (مضغوط).mp4» */
    fun outName(source: String, suffix: String, ext: String): String {
        val base = source.substringBeforeLast('.').ifBlank { "ملف" }.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").trim().take(120)
        return "$base ($suffix).$ext"
    }
}
