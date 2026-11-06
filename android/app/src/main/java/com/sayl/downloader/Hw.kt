@file:OptIn(UnstableApi::class)

package com.sayl.downloader

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * الترميز بالعتاد عبر Media3 Transformer (MediaCodec): أسرع بكثير من المعالج وأقل حرارة واستهلاكاً للبطارية.
 * يُستعمل للضغط والقص الدقيق والدمج المختلف؛ وعند فشله لأي سبب يعود العامل إلى FFmpeg.
 */
object Hw {
    /** هل في الهاتف مرمّز H.265 بالعتاد؟ (أصغر حجماً بالجودة نفسها) */
    fun hasHevc(): Boolean = runCatching { EncoderUtil.getSupportedEncoders(MimeTypes.VIDEO_H265).isNotEmpty() }.getOrDefault(false)

    fun hasAvc(): Boolean = runCatching { EncoderUtil.getSupportedEncoders(MimeTypes.VIDEO_H264).isNotEmpty() }.getOrDefault(false)

    fun item(uri: String, startMs: Long = -1, endMs: Long = -1): MediaItem {
        val b = MediaItem.Builder().setUri(uri)
        if (startMs >= 0 || endMs > 0) {
            val c = MediaItem.ClippingConfiguration.Builder()
            if (startMs >= 0) c.setStartPositionMs(startMs)
            if (endMs > 0) c.setEndPositionMs(endMs)
            b.setClippingConfiguration(c.build())
        }
        return b.build()
    }

    fun edited(item: MediaItem, fitW: Int = 0, fitH: Int = 0): EditedMediaItem {
        val b = EditedMediaItem.Builder(item)
        // في الدمج: كل مقطع يُحجَّم داخل أبعاد الأول مع حواف دون قص
        if (fitW > 0 && fitH > 0) b.setEffects(Effects(listOf(), listOf(Presentation.createForWidthAndHeight(fitW, fitH, Presentation.LAYOUT_SCALE_TO_FIT))))
        return b.build()
    }

    fun sequence(items: List<EditedMediaItem>): Composition =
        Composition.Builder(EditedMediaItemSequence.Builder(items).build()).build()

    /**
     * تصدير إلى MP4. videoMime: H.264 أو H.265؛ bitrate بالبت/ث أو 0 لترك القرار للمرمّز.
     * يتوقف عند canceled() ويرمي FFmpeg.Canceled.
     */
    fun export(ctx: Context, composition: Composition, out: File, videoMime: String, bitrate: Long, canceled: () -> Boolean, onProgress: (Float) -> Unit) {
        val thread = HandlerThread("sayl-hw").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        var error: Exception? = null
        var transformer: Transformer? = null
        handler.post {
            try {
                val settings = VideoEncoderSettings.Builder().apply { if (bitrate > 0) setBitrate(bitrate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }.build()
                val enc = DefaultEncoderFactory.Builder(ctx.applicationContext)
                    .setRequestedVideoEncoderSettings(settings)
                    .setEnableFallback(true)
                    .build()
                transformer = Transformer.Builder(ctx.applicationContext)
                    .setLooper(thread.looper)
                    .setVideoMimeType(videoMime)
                    .setEncoderFactory(enc)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.countDown() }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            error = exportException
                            done.countDown()
                        }
                    })
                    .build()
                    .also { it.start(composition, out.path) }
            } catch (e: Exception) {
                error = e
                done.countDown()
            }
        }
        val holder = ProgressHolder()
        try {
            while (!done.await(400, TimeUnit.MILLISECONDS)) {
                if (canceled()) {
                    handler.post { transformer?.cancel() }
                    throw FFmpeg.Canceled()
                }
                handler.post {
                    val t = transformer ?: return@post
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress.toFloat())
                }
            }
        } finally {
            handler.post { thread.quitSafely() }
        }
        error?.let { throw IllegalStateException("الترميز بالعتاد: ${it.message}", it) }
        if (!out.isFile || out.length() == 0L) throw IllegalStateException("لم يُنتج الترميز بالعتاد ملفاً")
    }
}
