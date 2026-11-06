package com.sayl.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media3.common.MimeTypes
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.sayl.downloader.MediaArgs.Level
import com.sayl.downloader.MediaArgs.Mode
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * ينفّذ عمليات المعالجة واحدة بعد الأخرى (رحمةً بالبطارية والحرارة)، في الخلفية مع إشعار تقدم وزر إلغاء.
 * الأصل لا يُعدَّل ولا يُستبدل أبداً: كل ناتج ملف جديد في مجلد الإخراج.
 */
class MediaWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    private val store = Bus.tasks(applicationContext)
    private var lastEmit = 0L
    private var lastNotif = 0L

    override fun doWork(): Result {
        ensureChannel(applicationContext)
        if (store.all().none { it.state == "queued" }) return Result.success()
        runCatching { setForegroundAsync(foreground(null)).get() }
        while (!isStopped) {
            coolDown()
            val t = store.claim() ?: break
            Bus.emitTask(t)
            run(t)
        }
        return Result.success()
    }

    /** إن كان الهاتف ساخناً جداً ننتظر حتى يبرد قليلاً قبل العملية التالية */
    private fun coolDown() {
        if (Build.VERSION.SDK_INT < 29) return
        val pm = applicationContext.getSystemService(PowerManager::class.java)
        var waited = 0
        while (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE && waited < 120 && !isStopped) {
            Thread.sleep(5000)
            waited += 5
        }
    }

    private fun canceled(id: String) = isStopped || CANCELED.containsKey(id) || store.get(id)?.state == "canceled"

    private fun stage(id: String, text: String, pct: Float = -1f, engine: String? = null) {
        val t = store.live(id) { it.copy(stage = text, pct = if (pct >= 0) pct.coerceIn(0f, 100f) else it.pct, engine = engine ?: it.engine) } ?: return
        val now = System.currentTimeMillis()
        if (now - lastEmit >= 400 || pct >= 100f) { lastEmit = now; Bus.emitTask(t) }
        if (now - lastNotif >= 1000) { lastNotif = now; runCatching { setForegroundAsync(foreground(t)) } }
    }

    private fun run(task: MediaTask) {
        val work = File(applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir, "media/${task.id}").apply { deleteRecursively(); mkdirs() }
        try {
            val outs = Ops(applicationContext, task, work, { canceled(task.id) }, { text, pct, engine -> stage(task.id, text, pct, engine) }).run()
            val published = outs.map { MediaIO.publish(applicationContext, it).also { r -> r.path?.let { p -> MediaIO.scan(applicationContext, p) } } }
            finish(store.update(task.id) { it.copy(state = "done", pct = 100f, stage = "", outputs = published, error = null, finishedAt = System.currentTimeMillis()) })
        } catch (e: FFmpeg.Canceled) {
            finish(store.update(task.id) { it.copy(state = "canceled", stage = "", finishedAt = System.currentTimeMillis()) })
        } catch (e: Exception) {
            if (canceled(task.id)) finish(store.update(task.id) { it.copy(state = "canceled", stage = "", finishedAt = System.currentTimeMillis()) })
            else finish(store.update(task.id) { it.copy(state = "failed", stage = "", error = friendly(e), finishedAt = System.currentTimeMillis()) })
        } finally {
            CANCELED.remove(task.id)
            work.deleteRecursively()
        }
    }

    private fun finish(t: MediaTask?) {
        t ?: return
        Bus.emitTask(t)
        if (t.state == "canceled") return
        val ok = t.state == "done"
        val saved = if (ok && t.srcSize > 0 && t.outSize > 0 && t.op == "compress") " · وُفّر ${((1 - t.outSize.toDouble() / t.srcSize) * 100).toInt().coerceAtLeast(0)}%" else ""
        val n = NotificationCompat.Builder(applicationContext, CH_MEDIA_DONE)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (ok) "${OP_NAMES[t.op] ?: "المعالجة"}: اكتملت$saved" else "تعذّرت المعالجة")
            .setContentText(if (ok) t.outputs.firstOrNull()?.name ?: t.title else t.error ?: t.title)
            .setContentIntent(DownloadWorker.openApp(applicationContext))
            .setAutoCancel(true)
            .build()
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(t.id.hashCode(), n) }
    }

    private fun foreground(t: MediaTask?): ForegroundInfo {
        val left = store.all().count { it.state == "queued" }
        val n = NotificationCompat.Builder(applicationContext, CH_MEDIA)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(t?.let { "${OP_NAMES[it.op] ?: "معالجة"}: ${it.title}" } ?: "جارٍ المعالجة")
            .setContentText(listOfNotNull(t?.stage?.ifBlank { null }, if (left > 0) "ينتظر $left" else null).joinToString(" · ").ifBlank { "سَيْل" })
            .setProgress(100, t?.pct?.toInt() ?: 0, t == null || t.pct <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(DownloadWorker.openApp(applicationContext))
            .apply { if (t != null) addAction(0, "إلغاء", ActionReceiver.intent(applicationContext, ActionReceiver.MEDIA_CANCEL, t.id)) }
            .build()
        return when {
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 -> ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(NOTIF_ID, n)
        }
    }

    companion object {
        private const val WORK = "sayl-media"
        private const val NOTIF_ID = 7101
        const val CH_MEDIA = "media"
        const val CH_MEDIA_DONE = "media-done"
        private val CANCELED = ConcurrentHashMap<String, Boolean>()

        val OP_NAMES = mapOf("convert" to "تحويل الصيغة", "audio" to "استخراج الصوت", "compress" to "الضغط", "trim" to "القص", "merge" to "الدمج", "frame" to "لقطة")

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_MEDIA, "المعالجة الجارية", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_MEDIA_DONE, "انتهاء المعالجة", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun kick(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<MediaWorker>().build())
        }

        fun cancel(ctx: Context, id: String) {
            CANCELED[id] = true
            Bus.tasks(ctx).update(id) { if (it.active) it.copy(state = "canceled", stage = "", finishedAt = System.currentTimeMillis()) else it }?.let { Bus.emitTask(it) }
        }

        /** سبب الفشل بالعربية بدل رسالة FFmpeg الخام (الخام يبقى في سجل التشخيص) */
        fun friendly(e: Exception): String {
            val m = e.message ?: e.toString()
            return when {
                Regex("No space left|ENOSPC|errno 28", RegexOption.IGNORE_CASE).containsMatchIn(m) -> "المساحة غير كافية. احذف بعض الملفات ثم أعد المحاولة."
                Regex("Invalid data found|moov atom not found|could not find codec|EBML header parsing failed", RegexOption.IGNORE_CASE).containsMatchIn(m) -> "الملف تالف أو غير مكتمل أو بصيغة غير مدعومة."
                Regex("Permission denied|EACCES|SecurityException", RegexOption.IGNORE_CASE).containsMatchIn(m) -> "لا توجد صلاحية لقراءة الملف. اختره مرة أخرى."
                Regex("FileNotFound|No such file", RegexOption.IGNORE_CASE).containsMatchIn(m) -> "الملف لم يعد موجوداً؛ ربما حُذف أو نُقل."
                m.length < 160 && Regex("[؀-ۿ]").containsMatchIn(m) -> m
                else -> "تعذّرت المعالجة: ${m.lines().lastOrNull { it.isNotBlank() }?.take(160) ?: m.take(160)}"
            }
        }
    }
}

/** تنفيذ عملية واحدة: يعيد ملفات الناتج في مجلد العمل، والعامل ينشرها. */
private class Ops(
    private val ctx: Context,
    private val t: MediaTask,
    private val work: File,
    private val canceled: () -> Boolean,
    private val stage: (String, Float, String?) -> Unit,
) {
    private val p = t.params
    private val src get() = t.inputs.first()

    fun run(): List<File> = when (t.op) {
        "convert" -> listOf(convert())
        "audio" -> listOf(audio())
        "compress" -> listOf(compress())
        "trim" -> listOf(trim())
        "merge" -> listOf(merge())
        "frame" -> listOf(frame())
        else -> throw IllegalArgumentException("عملية غير معروفة: ${t.op}")
    }

    private fun input(i: Int = 0): File {
        val ref = t.inputs[i]
        stage("تجهيز الملف", 0f, null)
        return MediaIO.localInput(ctx, ref, work, i, canceled) { stage("تجهيز الملف ${it.toInt()}%", 0f, null) }
    }

    private fun probe(f: File) = FFmpeg.probe(ctx, f.path)

    private fun out(suffix: String, ext: String) = File(work, MediaArgs.outName(src.name, suffix, ext)).also { it.delete() }

    private fun ffmpeg(args: List<String>, duration: Double, label: String, engine: String, from: Float = 0f, to: Float = 100f) {
        stage(label, from, engine)
        FFmpeg.exec(ctx, args, canceled) { line -> MediaArgs.progressOf(line, duration)?.let { stage(label, from + (to - from) * it / 100f, engine) } }
    }

    private fun hw(composition: androidx.media3.transformer.Composition, out: File, mime: String, bitrate: Long, label: String) {
        stage(label, 0f, "hw")
        Hw.export(ctx, composition, out, mime, bitrate, canceled) { stage(label, it, "hw") }
    }

    /** التحقق من سلامة الناتج: يُقرأ، وفيه ما يُنتظر، ومدته قريبة من المتوقعة */
    private fun verify(f: File, expectDuration: Double, video: Boolean, audio: Boolean): MediaArgs.Probe {
        if (!f.isFile || f.length() == 0L) throw IllegalStateException("لم يُنتج ملف")
        val r = runCatching { probe(f) }.getOrElse { throw IllegalStateException("الملف الناتج غير سليم") }
        if (video && !r.hasVideo) throw IllegalStateException("الملف الناتج بلا صورة")
        if (audio && !r.hasAudio) throw IllegalStateException("الملف الناتج بلا صوت")
        if (expectDuration > 1 && r.duration > 0 && kotlin.math.abs(r.duration - expectDuration) > maxOf(1.5, expectDuration * 0.05))
            throw IllegalStateException("مدة الناتج لا تطابق الأصل")
        return r
    }

    private fun convert(): File {
        val target = p.optString("target", "mp4")
        val inp = input()
        val pr = probe(inp)
        if (!pr.hasVideo) throw IllegalStateException("الملف صوتي فقط؛ استعمل «استخراج الصوت» لتحويله")
        val out = out("محوّل", target)
        val mode = MediaArgs.videoMode(target, pr)
        // إعادة ترميز الصورة إلى H.264 بالعتاد ثم تغيير الحاوية بلا ترميز
        if (mode == Mode.ENCODE && target in setOf("mp4", "mov", "mkv") && Hw.hasAvc()) {
            val tmp = File(work, "hw.mp4")
            val ok = runCatching { hw(Hw.sequence(listOf(Hw.edited(Hw.item(src.uri)))), tmp, MimeTypes.VIDEO_H264, pr.video!!.bitrate.takeIf { it > 0 } ?: 0, "تحويل بالعتاد") }
            if (ok.isFailure && ok.exceptionOrNull() is FFmpeg.Canceled) throw ok.exceptionOrNull()!!
            if (ok.isSuccess) {
                val tp = probe(tmp)
                if (target == "mp4") { tmp.renameTo(out) } else ffmpeg(MediaArgs.convertVideo(tmp.path, out.path, target, tp), tp.duration, "تغيير الحاوية", "copy", 90f, 100f)
                verify(out, pr.duration, true, pr.hasAudio)
                return out
            }
        }
        ffmpeg(MediaArgs.convertVideo(inp.path, out.path, target, pr), pr.duration, if (mode == Mode.COPY) "تغيير الحاوية دون إعادة ترميز" else "تحويل", if (mode == Mode.COPY) "copy" else "cpu")
        verify(out, pr.duration, true, pr.hasAudio)
        return out
    }

    private fun audio(): File {
        val o = MediaArgs.AudioOpts(p.optString("format", "mp3"), p.optInt("kbps", 0), p.optInt("sampleRate", 0), p.optDouble("start", -1.0), p.optDouble("end", -1.0))
        val inp = input()
        val pr = probe(inp)
        if (!pr.hasAudio) throw IllegalStateException("الملف لا يحتوي صوتاً")
        val out = out("صوت", o.format)
        val dur = if (o.end > 0) o.end - maxOf(0.0, o.start) else pr.duration - maxOf(0.0, o.start)
        ffmpeg(MediaArgs.audio(inp.path, out.path, o, pr), dur, if (MediaArgs.audioCopy(o, pr.audio?.codec)) "استخراج الصوت دون إعادة ترميز" else "تحويل الصوت",
            if (MediaArgs.audioCopy(o, pr.audio?.codec)) "copy" else "cpu")
        verify(out, dur, false, true)
        return out
    }

    private fun compress(): File {
        val level = runCatching { Level.valueOf(p.optString("level", "SMART").uppercase()) }.getOrDefault(Level.SMART)
        val codec = p.optString("codec", "auto")
        val inp = input()
        val pr = probe(inp)
        if (!pr.hasVideo) throw IllegalStateException("الضغط للفيديو فقط")
        val hevc = when (codec) { "h265" -> true; "h264" -> false; else -> Hw.hasHevc() }
        val plan = MediaArgs.compressPlan(pr, level, hevc)
        if (!plan.worth && level == Level.SMART) throw IllegalStateException("الملف مضغوط أصلاً، ولن يصغر أكثر دون خفض واضح في الجودة. جرّب «متوسط» أو «قوي» إن أردت.")
        val out = out("مضغوط", "mp4")
        val srcSize = inp.length()
        if (Hw.hasAvc()) {
            val r = runCatching { hw(Hw.sequence(listOf(Hw.edited(Hw.item(src.uri)))), out, if (hevc && Hw.hasHevc()) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264, plan.videoBps, "ضغط بالعتاد") }
            if (r.exceptionOrNull() is FFmpeg.Canceled) throw r.exceptionOrNull()!!
            if (r.isSuccess && out.length() in 1 until srcSize) {
                val v = verify(out, pr.duration, true, pr.hasAudio)
                // الأبعاد لا تتغير أبداً (مع مراعاة الدوران)
                val same = (v.video!!.width == pr.video!!.width && v.video.height == pr.video.height) || (v.video.width == pr.video.height && v.video.height == pr.video.width)
                if (same) return out
            }
            out.delete()
        }
        ffmpeg(MediaArgs.compressCpu(inp.path, out.path, plan, pr), pr.duration, "ضغط بالمعالج", "cpu")
        verify(out, pr.duration, true, pr.hasAudio)
        if (out.length() >= srcSize) throw IllegalStateException("لم يصغر الملف بهذا المستوى؛ جرّب مستوى أقوى")
        return out
    }

    private fun trim(): File {
        val start = p.optDouble("start", 0.0).coerceAtLeast(0.0)
        val end = p.optDouble("end", 0.0)
        val precise = p.optBoolean("precise", false)
        if (end <= start) throw IllegalStateException("نهاية المقطع يجب أن تكون بعد بدايته")
        val inp = input()
        val pr = probe(inp)
        val ext = src.name.substringAfterLast('.', "mp4").lowercase().let { if (it in MediaArgs.VIDEO_TARGETS || it in MediaArgs.AUDIO_TARGETS) it else "mp4" }
        if (!precise || !pr.hasVideo) {
            val out = out("مقطع", ext)
            ffmpeg(MediaArgs.trimCopy(inp.path, out.path, start, minOf(end, pr.duration), ext), end - start, "قص سريع دون إعادة ترميز", "copy")
            verify(out, 0.0, pr.hasVideo, pr.hasAudio)
            return out
        }
        val out = out("مقطع", "mp4")
        if (Hw.hasAvc()) {
            val r = runCatching {
                hw(Hw.sequence(listOf(Hw.edited(Hw.item(src.uri, (start * 1000).toLong(), (end * 1000).toLong())))), out, MimeTypes.VIDEO_H264,
                    pr.video!!.bitrate.takeIf { it > 0 } ?: 0, "قص دقيق بالعتاد")
            }
            if (r.exceptionOrNull() is FFmpeg.Canceled) throw r.exceptionOrNull()!!
            if (r.isSuccess) { verify(out, end - start, true, pr.hasAudio); return out }
            out.delete()
        }
        ffmpeg(MediaArgs.trimPrecise(inp.path, out.path, start, end, pr), end - start, "قص دقيق", "cpu")
        verify(out, end - start, true, pr.hasAudio)
        return out
    }

    private fun merge(): File {
        if (t.inputs.size < 2) throw IllegalStateException("اختر مقطعين على الأقل للدمج")
        val files = t.inputs.indices.map { i -> if (canceled()) throw FFmpeg.Canceled(); input(i) }
        val probes = files.map { probe(it) }
        if (probes.any { !it.hasVideo }) throw IllegalStateException("الدمج للفيديو فقط؛ أحد الملفات صوتي")
        val total = probes.sumOf { it.duration }
        val anyAudio = probes.any { it.hasAudio }
        val out = out("مدموج", "mp4")
        if (MediaArgs.canConcatCopy(probes)) {
            val list = File(work, "list.txt").apply { writeText(MediaArgs.concatList(files.map { it.path })) }
            ffmpeg(MediaArgs.concatCopy(list.path, out.path), total, "دمج دون إعادة ترميز", "copy")
            verify(out, total, true, anyAudio)
            return out
        }
        val first = probes.first().video!!
        // كل المقاطع فيها صوت: الدمج بالعتاد؛ وإلا FFmpeg يملأ الصامت منها بصمت حتى لا يختل التوقيت
        if (Hw.hasAvc() && probes.all { it.hasAudio }) {
            val (w, h) = if (first.rotation % 180 != 0) first.height to first.width else first.width to first.height
            val r = runCatching { hw(Hw.sequence(t.inputs.map { Hw.edited(Hw.item(it.uri), w, h) }), out, MimeTypes.VIDEO_H264, first.bitrate.takeIf { it > 0 } ?: 0, "دمج بالعتاد") }
            if (r.exceptionOrNull() is FFmpeg.Canceled) throw r.exceptionOrNull()!!
            if (r.isSuccess) { verify(out, total, true, true); return out }
            out.delete()
        }
        ffmpeg(MediaArgs.concatEncode(files.map { it.path }, out.path, probes), total, "دمج", "cpu")
        verify(out, total, true, true)
        return out
    }

    private fun frame(): File {
        val at = p.optDouble("at", 0.0).coerceAtLeast(0.0)
        val fmt = if (p.optString("format") == "png") "png" else "jpg"
        val out = out("لقطة ${at.toInt()}ث", fmt)
        stage("استخراج اللقطة", 10f, "copy")
        // MediaMetadataRetriever: أسرع، ويقرأ رابط المحتوى مباشرة دون نسخ
        val ok = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(ctx, Uri.parse(src.uri))
                    // بالدقة الأصلية كاملة
                    val bmp = getFrameAtTime((at * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) ?: throw IllegalStateException("لا إطار")
                    out.outputStream().use { bmp.compress(if (fmt == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }
                } finally { release() }
            }
        }
        if (ok.isSuccess && out.length() > 0) return out
        val inp = input()
        ffmpeg(MediaArgs.frame(inp.path, out.path, at), 0.0, "استخراج اللقطة", "cpu")
        if (!out.isFile || out.length() == 0L) throw IllegalStateException("تعذّر استخراج اللقطة من هذه اللحظة")
        return out
    }
}
