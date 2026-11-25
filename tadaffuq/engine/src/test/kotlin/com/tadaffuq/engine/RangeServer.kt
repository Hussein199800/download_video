package com.tadaffuq.engine

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.concurrent.atomic.AtomicInteger

/** خادم اختبار حقيقي يطبّق Range وIf-Range وفق RFC 9110، مع أعطال قابلة للحقن. */
class RangeServer(
    @Volatile var content: ByteArray,
    @Volatile var supportRanges: Boolean = true,
    @Volatile var etag: String? = "\"v1\"",
    @Volatile var lastModified: String? = "Wed, 01 Jan 2025 00:00:00 GMT",
    @Volatile var sendLength: Boolean = true,
) : Dispatcher() {
    val server = MockWebServer()
    val requests = AtomicInteger()
    val rangeRequests = AtomicInteger()
    /** عدد الطلبات التالية التي تُقطع بعد عدد بايتات محدد. */
    @Volatile var cutAfterBytes: Long = -1
    val cutsRemaining = AtomicInteger(0)
    val errorsRemaining = AtomicInteger(0)
    @Volatile var errorCode = 503
    @Volatile var statusOverride: Int? = null
    /** تأخير لكل 64KB لمحاكاة شبكة بطيئة. */
    @Volatile var throttleMsPer64k: Long = 0

    init { server.dispatcher = this; server.start() }

    fun url(path: String = "/files/test.bin") = server.url(path).toString()

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests.incrementAndGet()
        statusOverride?.let { return MockResponse().setResponseCode(it) }
        if (errorsRemaining.getAndDecrement() > 0) return MockResponse().setResponseCode(errorCode)
        val data = content
        val range = request.getHeader("Range")
        val ifRange = request.getHeader("If-Range")
        val validatorOk = ifRange == null || ifRange == etag || ifRange == lastModified
        val base = MockResponse().apply {
            etag?.let { setHeader("ETag", it) }
            lastModified?.let { setHeader("Last-Modified", it) }
            setHeader("Content-Type", "application/octet-stream")
        }
        if (supportRanges && range != null && validatorOk) {
            rangeRequests.incrementAndGet()
            val spec = range.removePrefix("bytes=")
            val start = spec.substringBefore('-').toLong()
            val endReq = spec.substringAfter('-').toLongOrNull() ?: (data.size - 1L)
            if (start >= data.size) return base.setResponseCode(416).setHeader("Content-Range", "bytes */${data.size}")
            val end = minOf(endReq, data.size - 1L)
            val slice = data.copyOfRange(start.toInt(), end.toInt() + 1)
            return body(base.setResponseCode(206).setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Range", "bytes $start-$end/${data.size}"), slice)
        }
        return body(base.setResponseCode(200), data)
    }

    private fun body(resp: MockResponse, bytes: ByteArray): MockResponse {
        val cut = cutAfterBytes
        val doCut = cut >= 0 && cutsRemaining.getAndDecrement() > 0 && bytes.size > cut
        val payload = if (doCut) bytes.copyOf(cut.toInt()) else bytes
        resp.setBody(Buffer().write(payload))
        if (doCut) {
            // نعلن الطول الكامل ثم نقطع الاتصال: انقطاع حقيقي في منتصف البث.
            resp.setHeader("Content-Length", bytes.size)
            resp.socketPolicy = okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END
        } else if (!sendLength) {
            resp.removeHeader("Content-Length")
            resp.setChunkedBody(Buffer().write(payload), 8192)
        }
        if (throttleMsPer64k > 0) resp.throttleBody(64 * 1024, throttleMsPer64k, java.util.concurrent.TimeUnit.MILLISECONDS)
        return resp
    }

    fun close() = server.shutdown()
}
