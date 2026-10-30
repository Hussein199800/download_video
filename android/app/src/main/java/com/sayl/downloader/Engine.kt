package com.sayl.downloader

import android.content.Context
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** تغليف youtubedl-android: معلومات الرابط، والتنزيل، والإلغاء، وتحديث yt-dlp. */
object Engine {
    /** نسخة من قالب الاسم في src/formats.js؛ العامل يعمل والواجهة مغلقة */
    const val OUTPUT_TEMPLATE = "%(title).150B [%(id)s].%(ext)s"

    @Volatile
    private var ready = false

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        val app = ctx.applicationContext
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        Aria2c.getInstance().init(app)
        ready = true
    }

    private fun withCookies(r: YoutubeDLRequest, cookies: File?) {
        if (cookies != null && cookies.exists()) r.addOption("--cookies", cookies.absolutePath)
    }

    /** JSON كما يعيده yt-dlp -J (القوائم بلا تفاصيل كل مقطع) */
    fun info(ctx: Context, url: String, cookies: File?): String {
        init(ctx)
        val r = YoutubeDLRequest(url)
            .addOption("-J")
            .addOption("--flat-playlist")
            .addOption("--no-warnings")
        withCookies(r, cookies)
        return YoutubeDL.getInstance().execute(r, null, null).out
    }

    /** ينزّل إلى outDir ويعيد الملف النهائي بعد الدمج والتحويل */
    fun download(
        ctx: Context, id: String, url: String, args: List<String>, outDir: File, cookies: File?,
        onProgress: (Float, Long, String) -> Unit,
    ): File {
        init(ctx)
        ArgGuard.check(args)?.let { throw IllegalArgumentException("خيار غير مسموح: $it") }
        outDir.mkdirs()
        val r = YoutubeDLRequest(url)
        r.addCommands(args)
        r.addCommands(outputArgs(outDir))
        withCookies(r, cookies)
        val res = YoutubeDL.getInstance().execute(r, id) { p, eta, line -> onProgress(p, eta, line) }
        val path = res.out.lines().map { it.trim() }.lastOrNull { it.startsWith("/") }
            ?: throw IllegalStateException("لم يُعرف مكان الملف المنزّل")
        return File(path)
    }

    /** مكان الحفظ وطباعة المسار النهائي؛ --print يُسكت yt-dlp فنعيد إظهار التقدم بـ --progress */
    fun outputArgs(outDir: File): List<String> = listOf(
        "-o", File(outDir, OUTPUT_TEMPLATE).absolutePath,
        "--print", "after_move:filepath",
        "--progress", "--newline",
    )

    fun cancel(id: String) {
        YoutubeDL.getInstance().destroyProcessById(id)
        Server2.cancel(id)
    }

    /** "DONE" أو "ALREADY_UP_TO_DATE"، على القناة المختارة (مستقرة أو Nightly) */
    fun update(ctx: Context): String {
        init(ctx)
        val ch = if (Bus.channel(ctx) == "nightly") YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE
        val r = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, ch)?.name ?: "ALREADY_UP_TO_DATE"
        Bus.markEngineUpdated(ctx)
        return r
    }

    fun version(ctx: Context): String? {
        init(ctx)
        return YoutubeDL.getInstance().versionName(ctx.applicationContext) ?: YoutubeDL.getInstance().version(ctx.applicationContext)
    }
}
