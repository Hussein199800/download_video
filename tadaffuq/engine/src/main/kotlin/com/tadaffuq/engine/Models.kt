package com.tadaffuq.engine

/** طلب تنزيل: الرابط وكل ما يرافقه من ترويسات. لا يُسجَّل محتوى الترويسات الحساسة أبداً. */
data class RequestSpec(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val referer: String? = null,
    val cookies: String? = null,
)

/** نتيجة فحص الرابط قبل التنزيل (RFC 9110). */
data class ProbeResult(
    val finalUrl: String,
    /** -1 إن لم يُعلن الخادم الحجم. */
    val totalBytes: Long,
    val acceptRanges: Boolean,
    val etag: String?,
    val lastModified: String?,
    val fileName: String,
    val mimeType: String?,
) {
    val resumable: Boolean get() = acceptRanges && totalBytes > 0
    /** المعرّف الذي يصلح لـ If-Range: ETag قوي، وإلا Last-Modified. */
    val ifRangeValidator: String? get() = etag?.takeUnless { it.startsWith("W/") } ?: lastModified
}

/** حالة جزء محفوظة. end شاملة؛ ‎-1 = حجم غير معروف. */
data class SegmentState(val index: Int, val start: Long, val end: Long, val downloaded: Long) {
    val position: Long get() = start + downloaded
    val done: Boolean get() = end >= 0 && position > end
}

/** جزء حي أثناء التنزيل. كل تعديل على end/downloaded يتم داخل synchronized(this). */
class Segment(val index: Int, val start: Long, end: Long, downloaded: Long = 0) {
    @Volatile var end: Long = end
        internal set
    @Volatile var downloaded: Long = downloaded
        internal set
    @Volatile var active: Boolean = false
        internal set

    @Volatile private var streamEnded = false

    val position: Long get() = start + downloaded
    val done: Boolean get() = streamEnded || (end >= 0 && position > end)

    /** لملف مجهول الحجم: انتهى البث طبيعياً. */
    internal fun markStreamEnded() { streamEnded = true }

    /** المتبقي بالبايت، أو Long.MAX_VALUE للحجم المجهول. */
    val remaining: Long get() = if (end < 0) Long.MAX_VALUE else (end - position + 1).coerceAtLeast(0)

    fun state() = synchronized(this) { SegmentState(index, start, end, downloaded) }

    companion object {
        fun of(s: SegmentState) = Segment(s.index, s.start, s.end, s.downloaded)
    }
}

enum class SpeedMode { NORMAL, MAX, WEAK_NETWORK, SAVER }

data class DownloadConfig(
    /** 0 = تلقائي حسب الحجم وقدرة الخادم، وإلا 1..32. */
    val connections: Int = 0,
    val maxConnections: Int = 8,
    val minSplitBytes: Long = 512L * 1024,
    val maxRetries: Int = 8,
    val baseRetryDelayMs: Long = 1_000,
    val maxRetryDelayMs: Long = 30_000,
    val persistIntervalMs: Long = 2_000,
    val bufferBytes: Int = 64 * 1024,
    /** يضيف اتصالات تدريجياً ما دامت السرعة تتحسن بنسبة معقولة. */
    val autoTune: Boolean = true,
) {
    init {
        require(connections in 0..MAX_CONNECTIONS) { "connections out of range" }
        require(maxConnections in 1..MAX_CONNECTIONS) { "maxConnections out of range" }
    }

    companion object {
        const val MAX_CONNECTIONS = 32

        fun forMode(mode: SpeedMode, userMax: Int): DownloadConfig = when (mode) {
            SpeedMode.NORMAL -> DownloadConfig(maxConnections = userMax.coerceIn(1, MAX_CONNECTIONS))
            SpeedMode.MAX -> DownloadConfig(maxConnections = MAX_CONNECTIONS.coerceAtMost(maxOf(userMax, 16)), minSplitBytes = 256L * 1024)
            SpeedMode.WEAK_NETWORK -> DownloadConfig(
                maxConnections = 2, maxRetries = 30, baseRetryDelayMs = 2_000, maxRetryDelayMs = 60_000,
                persistIntervalMs = 1_000, bufferBytes = 16 * 1024, autoTune = false,
            )
            SpeedMode.SAVER -> DownloadConfig(maxConnections = 2, autoTune = false)
        }
    }
}

/** قراءة لحظية حقيقية للمهمة، تُبنى من البايتات المكتوبة فعلاً. */
data class Snapshot(
    val downloaded: Long,
    val total: Long,
    val speedBps: Long,
    val averageBps: Long,
    val maxBps: Long,
    /** -1 إن تعذّر التقدير. */
    val etaSeconds: Long,
    val connections: Int,
    val segments: List<SegmentState>,
)

sealed class DownloadError(val retryable: Boolean) {
    data class Network(val detail: String) : DownloadError(true)
    data class Http(val code: Int) : DownloadError(code >= 500 || code == 408 || code == 429)
    /** 401/403/410 بعد بدء التنزيل غالباً = رابط منتهي الصلاحية. */
    data class LinkExpired(val code: Int) : DownloadError(false)
    data object NotFound : DownloadError(false)
    /** الخادم أعاد ملفاً مختلفاً (ETag/Last-Modified/الحجم)؛ الاستئناف غير آمن. */
    data object ChangedOnServer : DownloadError(false)
    data object RangeNotSupported : DownloadError(false)
    data class InsufficientSpace(val needed: Long, val available: Long) : DownloadError(false)
    data class Storage(val detail: String) : DownloadError(false)
    data class ChecksumMismatch(val expected: String, val actual: String) : DownloadError(false)
}

class DownloadException(val error: DownloadError, cause: Throwable? = null) :
    Exception(error.toString(), cause)
