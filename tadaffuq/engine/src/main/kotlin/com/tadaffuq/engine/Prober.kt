package com.tadaffuq.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * يفحص الرابط بطلب GET لبايت واحد (Range: bytes=0-0) لأن خوادم كثيرة لا تدعم HEAD جيداً.
 * 206 مع Content-Range = يدعم الاستئناف والتقسيم.
 */
class Prober(private val client: OkHttpClient) {

    suspend fun probe(spec: RequestSpec): ProbeResult = withContext(Dispatchers.IO) {
        val request = spec.toRequest().header("Range", "bytes=0-0").get().build()
        val response = try {
            client.executeCancellable(request)
        } catch (e: IOException) {
            throw DownloadException(DownloadError.Network(e.javaClass.simpleName), e)
        }
        response.use { r ->
            when (r.code) {
                206 -> Unit
                200 -> Unit
                401, 403 -> throw DownloadException(DownloadError.LinkExpired(r.code))
                404, 410 -> throw DownloadException(DownloadError.NotFound)
                416 -> Unit // ملف فارغ على بعض الخوادم
                else -> throw DownloadException(DownloadError.Http(r.code))
            }
            val range = parseContentRange(r.header("Content-Range"))
            val ranged = r.code == 206 && range != null && range.first == 0L
            val total = when {
                ranged -> range!!.total ?: -1
                r.code == 416 -> parseContentRange(r.header("Content-Range"))?.total ?: 0
                else -> r.body?.contentLength() ?: -1
            }
            val mime = r.header("Content-Type")
            val finalUrl = r.request.url.toString()
            ProbeResult(
                finalUrl = finalUrl,
                totalBytes = total,
                acceptRanges = ranged && total > 0,
                etag = r.header("ETag"),
                lastModified = r.header("Last-Modified"),
                fileName = FileNames.resolve(r.header("Content-Disposition"), finalUrl, mime),
                mimeType = mime?.substringBefore(';')?.trim(),
            )
        }
    }

    companion object {
        /**
         * هل الملف على الخادم هو نفسه المحفوظ؟ يُرفض الاستئناف إن تغيّر الحجم أو أي معرّف
         * أعلنه الخادم في المرتين. غياب المعرّفين معاً مع تطابق الحجم يُقبل (لا دليل على التغيّر).
         */
        fun sameContent(saved: ProbeResult, now: ProbeResult): Boolean {
            if (saved.totalBytes != now.totalBytes) return false
            if (saved.etag != null && now.etag != null && saved.etag != now.etag) return false
            if (saved.lastModified != null && now.lastModified != null && saved.lastModified != now.lastModified) return false
            return true
        }
    }
}
