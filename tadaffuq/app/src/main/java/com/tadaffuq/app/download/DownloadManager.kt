package com.tadaffuq.app.download

import android.content.Context
import android.os.Build
import com.tadaffuq.app.BuildConfig
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.SegmentEntity
import com.tadaffuq.data.Settings
import com.tadaffuq.data.SettingsRepository
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus
import com.tadaffuq.engine.DownloadConfig
import com.tadaffuq.engine.DownloadError
import com.tadaffuq.engine.DownloadException
import com.tadaffuq.engine.DownloadListener
import com.tadaffuq.engine.FileNames
import com.tadaffuq.engine.Hashes
import com.tadaffuq.engine.HttpDownloader
import com.tadaffuq.engine.Prober
import com.tadaffuq.engine.ProbeResult
import com.tadaffuq.engine.RateLimiter
import com.tadaffuq.engine.RequestSpec
import com.tadaffuq.engine.ResumeState
import com.tadaffuq.engine.SegmentState
import com.tadaffuq.engine.Snapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** طلب إضافة مهمة من الواجهة. */
data class NewTask(
    val url: String,
    val fileName: String,
    val dirPath: String,
    val probe: ProbeResult? = null,
    val connections: Int = 0,
    val expectedHash: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val cookies: String? = null,
    val priority: Int = 0,
    val packageId: Long? = null,
    val wifiOnly: Boolean = false,
    val speedLimitBps: Long = 0,
    /** يبدأ متوقفاً (للحفظ في الطابور دون بدء). */
    val startPaused: Boolean = false,
)

/**
 * منسّق التنزيلات: Room مصدر الحقيقة، وهذا الكائن يشغّل ما في الطابور حسب الأولوية
 * وحدّ التنزيلات المتوازية وحالة الجهاز، ويحفظ التقدم ليُستأنف بعد أي انقطاع.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    db: AppDatabase,
    private val settingsRepo: SettingsRepository,
    private val client: OkHttpClient,
    val conditions: Conditions,
    private val notifier: Notifier,
) {
    private val dao = db.tasks()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val usage = UsageMeter(db.usage(), conditions)

    private val _live = MutableStateFlow<Map<Long, Snapshot>>(emptyMap())
    /** القراءات اللحظية الحقيقية لكل مهمة تعمل. */
    val live: StateFlow<Map<Long, Snapshot>> = _live

    private val _speedHistory = MutableStateFlow(List(HISTORY) { 0L })
    /** السرعة الإجمالية في آخر ٢ دقيقة، عيّنة كل ثانية. */
    val speedHistory: StateFlow<List<Long>> = _speedHistory

    private val _hold = MutableStateFlow<Hold?>(null)
    val hold: StateFlow<Hold?> = _hold

    private val _running = MutableStateFlow<Set<Long>>(emptySet())
    val runningIds: StateFlow<Set<Long>> = _running

    val globalLimiter = RateLimiter()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val stopAs = ConcurrentHashMap<Long, TaskStatus>()
    private val limiters = ConcurrentHashMap<Long, RateLimiter>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val scheduleLock = Mutex()
    @Volatile private var settings = Settings()
    @Volatile private var started = false
    @Volatile private var pendingCount = 0

    fun start() {
        synchronized(this) { if (started) return; started = true }
        conditions.start()
        notifier.ensureChannels()
        scope.launch {
            // ما كان يعمل عند توقف العملية (إغلاق أو إعادة تشغيل) يعود للطابور ويُستأنف من آخر حفظ.
            dao.moveStatus(listOf(TaskStatus.RUNNING, TaskStatus.VERIFYING), TaskStatus.QUEUED)
            settingsRepo.settings.collect {
                settings = it
                globalLimiter.bytesPerSecond = it.globalLimitBps
                wake()
            }
        }
        scope.launch { conditions.state.collect { wake() } }
        scope.launch { for (w in wake) scheduleLock.withLock { schedule() } }
        scope.launch {
            while (true) {
                delay(1_000)
                val total = _live.value.values.sumOf { it.speedBps }
                _speedHistory.update { (it.drop(1) + total) }
            }
        }
        usage.start(scope)
    }

    fun wake() { wake.trySend(Unit) }

    /** للاختبارات: يحاكي موت العملية (يلغي كل شيء دون تحديث الحالة). */
    @androidx.annotation.VisibleForTesting
    fun kill() { scope.coroutineContext[Job]?.cancel() }

    private suspend fun schedule() {
        val s = settings
        val d = conditions.state.value
        val hold = Policy.hold(s, d, usage.cellularToday, usage.cellularMonth)
        _hold.value = hold
        val waiting = dao.withStatus(listOf(TaskStatus.QUEUED, TaskStatus.WAITING_NETWORK))
        pendingCount = waiting.size
        if (hold != null) {
            jobs.keys.toList().forEach { stop(it, TaskStatus.WAITING_NETWORK) }
            if (waiting.isNotEmpty() || jobs.isNotEmpty()) scheduleResumeWhenConnected()
            updateService()
            return
        }
        // الحد الأقصى للمتوازي: الزائد عن الحد (بعد تقليل الإعداد) ينتظر دوره
        val over = jobs.size - s.maxParallel
        if (over > 0) jobs.keys.toList().take(over).forEach { stop(it, TaskStatus.QUEUED) }
        val slots = s.maxParallel - jobs.size
        if (slots > 0) {
            waiting.asSequence()
                .filter { !jobs.containsKey(it.id) && Policy.hold(s, d, usage.cellularToday, usage.cellularMonth, it.wifiOnly) == null }
                .take(slots)
                .forEach { launchTask(it) }
        }
        updateService()
    }

    private suspend fun launchTask(t: TaskEntity) {
        dao.setStatus(t.id, TaskStatus.RUNNING)
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { execute(t) }
        jobs[t.id] = job
        _running.value = jobs.keys.toSet()
        job.invokeOnCompletion {
            jobs.remove(t.id)
            _running.value = jobs.keys.toSet()
            _live.update { it - t.id }
            wake()
        }
        job.start()
    }

    private fun updateService() {
        if (jobs.isNotEmpty()) DownloadService.ensureRunning(ctx, this)
    }

    /** هل بقي عمل؟ تستعمله الخدمة وعامل الاستئناف ليتوقفا عند الفراغ. */
    fun hasWork(): Boolean = jobs.isNotEmpty() || (pendingCount > 0 && _hold.value == null)

    private fun stop(id: Long, status: TaskStatus) {
        val job = jobs[id] ?: return
        stopAs[id] = status
        job.cancel()
    }

    private fun scheduleResumeWhenConnected() {
        if (!DownloadService.workerInForeground) ResumeWorker.enqueue(ctx)
    }

    private suspend fun execute(t: TaskEntity) {
        val segs = dao.segments(t.id)
        val saved = if (segs.isNotEmpty() && t.totalBytes > 0) ResumeState(
            ProbeResult(t.finalUrl ?: t.url, t.totalBytes, t.acceptRanges, t.etag, t.lastModified, t.fileName, t.mimeType),
            segs.map { SegmentState(it.idx, it.start, it.endInclusive, it.downloaded) },
        ) else null
        val s = settings
        val base = DownloadConfig.forMode(s.mode, s.maxConnections)
        val cap = Policy.connectionCap(conditions.state.value, base.maxConnections)
        val cfg = base.copy(connections = t.connections.coerceAtMost(cap), maxConnections = cap)
        val limiter = limiters.getOrPut(t.id) { RateLimiter(t.speedLimitBps) }
        val counters = IntArray(3).also { it[0] = t.retries; it[1] = t.networkRetries; it[2] = t.serverRetries }
        var last: Snapshot? = null
        val spec = RequestSpec(
            url = t.url,
            userAgent = t.userAgent ?: s.userAgent.ifBlank { null } ?: DEFAULT_UA,
            referer = t.referer,
            cookies = t.cookies,
        )
        val part = StorageDirs.partFile(t.dirPath, t.fileName)
        val listener = object : DownloadListener {
            override fun onProgress(snapshot: Snapshot) {
                last = snapshot
                _live.update { it + (t.id to snapshot) }
            }

            override suspend fun persist(state: ResumeState) {
                val p = state.probe
                dao.saveProgress(
                    t.id,
                    state.segments.map { SegmentEntity(t.id, it.index, it.start, it.end, it.downloaded) },
                    state.segments.sumOf { it.downloaded }, p.totalBytes, p.etag, p.lastModified, p.acceptRanges, p.finalUrl,
                )
                last?.let { dao.progress(t.id, it.downloaded, p.totalBytes, it.averageBps, it.maxBps) }
            }

            override fun onBytes(count: Long) = usage.add(count)

            override fun onRetry(segment: Int, attempt: Int, error: DownloadError, delayMs: Long) {
                counters[0]++
                when (error) {
                    is DownloadError.Network -> counters[1]++
                    is DownloadError.Http -> counters[2]++
                    else -> Unit
                }
            }
        }
        try {
            val probe = HttpDownloader(client, spec, part, cfg, saved, limiter, globalLimiter, listener).run()
            val dir = File(t.dirPath)
            val finalName = FileNames.uniqueIn(dir, t.fileName)
            val dest = File(dir, finalName)
            if (!part.renameTo(dest)) throw DownloadException(DownloadError.Storage("rename"))
            dao.clearSegments(t.id)
            var hashOk: Boolean? = null
            var actual: String? = null
            if (!t.expectedHash.isNullOrBlank()) {
                dao.setStatus(t.id, TaskStatus.VERIFYING)
                Hashes.verify(dest, t.expectedHash!!)?.let { (ok, a) -> hashOk = ok; actual = a }
            }
            val current = dao.get(t.id) ?: return
            val done = current.copy(
                status = TaskStatus.COMPLETED,
                fileName = finalName,
                filePath = dest.path,
                totalBytes = dest.length(),
                downloadedBytes = dest.length(),
                finalUrl = probe.finalUrl,
                mimeType = probe.mimeType ?: current.mimeType,
                completedAt = System.currentTimeMillis(),
                actualHash = actual,
                hashOk = hashOk,
                errorCode = if (hashOk == false) "CHECKSUM" else null,
                errorDetail = null,
                retries = counters[0], networkRetries = counters[1], serverRetries = counters[2],
                averageBps = last?.averageBps ?: current.averageBps,
                maxBps = maxOf(last?.maxBps ?: 0, current.maxBps),
            )
            dao.update(done)
            limiters.remove(t.id)
            StorageDirs.scan(ctx, dest)
            if (settings.notifyOnComplete) notifier.completed(done)
        } catch (e: CancellationException) {
            val status = stopAs.remove(t.id) ?: TaskStatus.PAUSED
            withContext(NonCancellable) {
                dao.get(t.id)?.let { dao.update(it.copy(status = status, retries = counters[0], networkRetries = counters[1], serverRetries = counters[2])) }
            }
            throw e
        } catch (e: DownloadException) {
            withContext(NonCancellable) {
                val offline = e.error is DownloadError.Network && !conditions.state.value.connected
                dao.get(t.id)?.let {
                    val failed = it.copy(
                        status = if (offline) TaskStatus.WAITING_NETWORK else TaskStatus.FAILED,
                        errorCode = ErrorCodes.of(e.error),
                        errorDetail = (e.error as? DownloadError.Storage)?.detail,
                        retries = counters[0], networkRetries = counters[1], serverRetries = counters[2],
                    )
                    dao.update(failed)
                    if (!offline) notifier.failed(failed)
                }
            }
        } catch (e: Exception) {
            withContext(NonCancellable) {
                dao.setStatus(t.id, TaskStatus.FAILED, "INTERNAL", e.javaClass.simpleName)
            }
        }
    }

    // ===== واجهة الاستخدام =====

    suspend fun probe(spec: RequestSpec): ProbeResult = Prober(client).probe(spec.copy(userAgent = spec.userAgent ?: settings.userAgent.ifBlank { null } ?: DEFAULT_UA))

    suspend fun enqueue(tasks: List<NewTask>): List<Long> {
        val ids = dao.insertAll(tasks.map { n ->
            TaskEntity(
                url = n.url, fileName = FileNames.sanitize(n.fileName), dirPath = n.dirPath,
                status = if (n.startPaused) TaskStatus.PAUSED else TaskStatus.QUEUED,
                totalBytes = n.probe?.totalBytes ?: -1, acceptRanges = n.probe?.acceptRanges ?: false,
                etag = n.probe?.etag, lastModified = n.probe?.lastModified, mimeType = n.probe?.mimeType,
                finalUrl = n.probe?.finalUrl, connections = n.connections.coerceIn(0, DownloadConfig.MAX_CONNECTIONS),
                expectedHash = n.expectedHash?.trim()?.ifBlank { null }, userAgent = n.userAgent, referer = n.referer,
                cookies = n.cookies, priority = n.priority, packageId = n.packageId, wifiOnly = n.wifiOnly,
                speedLimitBps = n.speedLimitBps,
            )
        })
        wake()
        return ids
    }

    suspend fun pause(ids: Collection<Long>) {
        ids.forEach { id ->
            if (jobs.containsKey(id)) stop(id, TaskStatus.PAUSED)
            else dao.get(id)?.takeIf { it.status.isWaiting }?.let { dao.setStatus(id, TaskStatus.PAUSED) }
        }
    }

    suspend fun resume(ids: Collection<Long>) {
        ids.forEach { id ->
            dao.get(id)?.takeIf { it.status == TaskStatus.PAUSED || it.status == TaskStatus.FAILED || it.status == TaskStatus.WAITING_NETWORK }
                ?.let { dao.update(it.copy(status = TaskStatus.QUEUED, errorCode = null, errorDetail = null)) }
        }
        wake()
    }

    /** إعادة من الصفر: تُحذف الأجزاء والملف الجزئي. */
    suspend fun restart(id: Long) {
        val t = dao.get(id) ?: return
        jobs[id]?.let { stop(id, TaskStatus.PAUSED); it.join() }
        dao.clearSegments(id)
        StorageDirs.partFile(t.dirPath, t.fileName).delete()
        dao.update(t.copy(status = TaskStatus.QUEUED, downloadedBytes = 0, errorCode = null, errorDetail = null, retries = 0, networkRetries = 0, serverRetries = 0))
        wake()
    }

    suspend fun pauseAll() = pause(jobs.keys + dao.withStatus(listOf(TaskStatus.QUEUED, TaskStatus.WAITING_NETWORK)).map { it.id })
    suspend fun resumeAll() = resume(dao.withStatus(listOf(TaskStatus.PAUSED, TaskStatus.WAITING_NETWORK)).map { it.id })

    /** حذف مهام؛ withFiles يحذف الملف المكتمل أيضاً، والملف الجزئي يُحذف دائماً. */
    suspend fun delete(ids: Collection<Long>, withFiles: Boolean) {
        ids.forEach { id ->
            jobs[id]?.let { stop(id, TaskStatus.PAUSED); it.join() }
            val t = dao.get(id) ?: return@forEach
            StorageDirs.partFile(t.dirPath, t.fileName).delete()
            if (withFiles) t.filePath?.let { File(it).delete() }
            limiters.remove(id)
        }
        dao.delete(ids.toList())
    }

    suspend fun setPriority(id: Long, priority: Int) { dao.setPriority(id, priority); wake() }

    suspend fun setSpeedLimit(id: Long, bps: Long) {
        dao.setSpeedLimit(id, bps)
        limiters.getOrPut(id) { RateLimiter() }.bytesPerSecond = bps
    }

    /** تحديث رابط منتهٍ: يبقى التقدم، والمحرك يتحقق عند الاستئناف أن الملف نفسه (الحجم وETag). */
    suspend fun updateUrl(id: Long, url: String) {
        val t = dao.get(id) ?: return
        jobs[id]?.let { stop(id, TaskStatus.PAUSED); it.join() }
        dao.update(t.copy(url = url, finalUrl = null, status = TaskStatus.QUEUED, errorCode = null))
        wake()
    }

    suspend fun duplicates(url: String, dir: String, name: String): List<TaskEntity> =
        (dao.byUrl(url) + dao.byName(dir, name)).distinctBy { it.id }

    companion object {
        const val HISTORY = 120
        val DEFAULT_UA = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) Tadaffuq/${BuildConfig.VERSION_NAME}"
    }
}

/** رموز الأخطاء المحفوظة في قاعدة البيانات، وتحويلها للطبيب والواجهة. */
object ErrorCodes {
    fun of(e: DownloadError): String = when (e) {
        is DownloadError.Network -> "NETWORK"
        is DownloadError.Http -> "HTTP_${e.code}"
        is DownloadError.LinkExpired -> "EXPIRED_${e.code}"
        DownloadError.NotFound -> "NOT_FOUND"
        DownloadError.ChangedOnServer -> "CHANGED"
        DownloadError.RangeNotSupported -> "NO_RANGE"
        is DownloadError.InsufficientSpace -> "SPACE"
        is DownloadError.Storage -> "STORAGE"
        is DownloadError.ChecksumMismatch -> "CHECKSUM"
    }

    fun toError(code: String?): DownloadError? = when {
        code == null -> null
        code == "NETWORK" -> DownloadError.Network("")
        code.startsWith("HTTP_") -> DownloadError.Http(code.removePrefix("HTTP_").toIntOrNull() ?: 0)
        code.startsWith("EXPIRED_") -> DownloadError.LinkExpired(code.removePrefix("EXPIRED_").toIntOrNull() ?: 403)
        code == "NOT_FOUND" -> DownloadError.NotFound
        code == "CHANGED" -> DownloadError.ChangedOnServer
        code == "NO_RANGE" -> DownloadError.RangeNotSupported
        code == "SPACE" -> DownloadError.InsufficientSpace(0, 0)
        code == "STORAGE" || code == "INTERNAL" -> DownloadError.Storage(code)
        code == "CHECKSUM" -> DownloadError.ChecksumMismatch("", "")
        else -> null
    }
}
