package com.sayl.downloader

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * مهمة تنزيل واحدة.
 * state: queued | running | paused | waiting_network | retrying | done | failed | canceled
 */
data class Job(
    val id: String,
    val url: String,
    val title: String,
    val kind: String,
    val args: List<String>,
    val thumb: String? = null,
    val site: String = "other",
    val folder: String? = null,
    /** اختيار المستخدم (النوع والدقة والصيغة) كما أرسلته الواجهة؛ السيرفر 2 يحتاجه لأنه لا يفهم أوامر yt-dlp */
    val choice: String? = null,
    val state: String = "queued",
    val pct: Float = 0f,
    val speed: Long = 0,
    val downloaded: Long = 0,
    val total: Long = 0,
    val eta: Long = -1,
    val error: String? = null,
    val path: String? = null,
    val uri: String? = null,
    val attempts: Int = 0,
    val retryAt: Long = 0,
    /** 1 = yt-dlp، 2 = NewPipe */
    val engine: Int = 1,
    val scheduledAt: Long = 0,
    val wifiOnly: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    val order: Long = createdAt,
) {
    /** ما زال في الطابور أو يعمل */
    val active get() = state == "queued" || state == "running" || state == "waiting_network" || state == "retrying"

    /** ينتظر دوره (قد يكون مجدولاً أو بانتظار إعادة المحاولة أو الشبكة) */
    val pending get() = state == "queued" || state == "waiting_network" || state == "retrying"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("url", url).put("title", title).put("kind", kind).put("args", JSONArray(args))
        .put("thumb", thumb).put("site", site).put("folder", folder).put("choice", choice).put("state", state).put("pct", pct.toDouble())
        .put("speed", speed).put("downloaded", downloaded).put("total", total).put("eta", eta).put("error", error)
        .put("path", path).put("uri", uri).put("attempts", attempts).put("retryAt", retryAt).put("engine", engine)
        .put("scheduledAt", scheduledAt).put("wifiOnly", wifiOnly).put("createdAt", createdAt).put("startedAt", startedAt)
        .put("finishedAt", finishedAt).put("order", order)

    companion object {
        private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)

        fun fromJson(o: JSONObject): Job {
            val a = o.optJSONArray("args") ?: JSONArray()
            val created = o.optLong("createdAt")
            return Job(
                id = o.getString("id"), url = o.getString("url"), title = o.optString("title"), kind = o.optString("kind", "video"),
                args = List(a.length()) { a.getString(it) }, thumb = o.str("thumb"), site = o.optString("site", "other"),
                folder = o.str("folder"), choice = o.str("choice"), state = o.optString("state", "queued"),
                pct = o.optDouble("pct", 0.0).toFloat(), speed = o.optLong("speed"), downloaded = o.optLong("downloaded"),
                total = o.optLong("total"), eta = o.optLong("eta", -1), error = o.str("error"), path = o.str("path"),
                uri = o.str("uri"), attempts = o.optInt("attempts"), retryAt = o.optLong("retryAt"), engine = o.optInt("engine", 1),
                scheduledAt = o.optLong("scheduledAt"), wifiOnly = o.optBoolean("wifiOnly"), createdAt = created,
                startedAt = o.optLong("startedAt"), finishedAt = o.optLong("finishedAt"), order = o.optLong("order", created),
            )
        }
    }
}

/** الطابور والسجل في ملف JSON واحد داخل ملفات التطبيق. */
class JobStore(private val file: File) {
    private val jobs = LinkedHashMap<String, Job>()

    init {
        if (file.exists()) runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) Job.fromJson(arr.getJSONObject(i)).let { jobs[it.id] = it }
        }
    }

    private fun save() {
        val arr = JSONArray()
        jobs.values.forEach { arr.put(it.toJson()) }
        val tmp = File(file.path + ".tmp")
        file.parentFile?.mkdirs()
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    /** يعيد ما أُضيف فعلاً؛ الرابط نفسه بالصيغة نفسها لا يُكرر ما دام في الطابور أو موقوفاً */
    @Synchronized
    fun add(list: List<Job>): List<Job> {
        val added = list.filter { n -> jobs.values.none { (it.active || it.state == "paused") && it.url == n.url && it.args == n.args } && n.id !in jobs }
            .distinctBy { it.url to it.args }
        added.forEach { jobs[it.id] = it }
        if (added.isNotEmpty()) save()
        return added
    }

    @Synchronized
    fun get(id: String): Job? = jobs[id]

    @Synchronized
    fun all(): List<Job> = jobs.values.toList()

    /** المعرّفات المنتظرة بترتيب التنفيذ */
    @Synchronized
    fun queueOrder(): List<String> = jobs.values.filter { it.pending }.sortedWith(compareBy({ it.order }, { it.createdAt })).map { it.id }

    /** أول مهمة مستحقة الآن بحسب الترتيب */
    @Synchronized
    fun next(isDue: (Job) -> Boolean = { it.state == "queued" }): Job? =
        jobs.values.filter { it.pending && isDue(it) }.minWithOrNull(compareBy({ it.order }, { it.createdAt }))

    /** يأخذ أول مهمة مستحقة ويعلّمها «تعمل» دفعة واحدة، فلا يأخذها عاملان */
    @Synchronized
    fun claim(isDue: (Job) -> Boolean = { it.state == "queued" }): Job? {
        val j = next(isDue) ?: return null
        return update(j.id) { it.copy(state = "running", error = null, retryAt = 0, startedAt = if (it.startedAt > 0) it.startedAt else System.currentTimeMillis()) }
    }

    /** تحديث التقدم في الذاكرة فقط؛ يُحفظ مع أول تغيير حالة */
    @Synchronized
    fun live(id: String, fn: (Job) -> Job): Job? {
        val j = jobs[id] ?: return null
        return fn(j).also { jobs[id] = it }
    }

    @Synchronized
    fun update(id: String, fn: (Job) -> Job): Job? {
        val j = jobs[id] ?: return null
        val n = fn(j)
        jobs[id] = n
        save()
        return n
    }

    @Synchronized
    fun pause(id: String): Job? = update(id) { if (it.active) it.copy(state = "paused", speed = 0, eta = -1) else it }

    @Synchronized
    fun resume(id: String): Job? = update(id) { if (it.state == "paused") it.copy(state = "queued", retryAt = 0) else it }

    /** delta سالب = للأعلى */
    @Synchronized
    fun move(id: String, delta: Int) {
        val ids = queueOrder().toMutableList()
        val i = ids.indexOf(id)
        if (i < 0) return
        val to = (i + delta).coerceIn(0, ids.size - 1)
        if (to == i) return
        ids.add(to, ids.removeAt(i))
        renumber(ids)
    }

    @Synchronized
    fun toTop(id: String) {
        val ids = queueOrder().toMutableList()
        if (!ids.remove(id)) return
        ids.add(0, id)
        renumber(ids)
    }

    private fun renumber(ids: List<String>) {
        val base = jobs.values.filter { it.pending }.minOfOrNull { it.order } ?: 0
        ids.forEachIndexed { n, id -> jobs[id]?.let { jobs[id] = it.copy(order = base + n) } }
        save()
    }

    /** بعد إغلاق التطبيق فجأة: ما كان «يعمل» يعود إلى الطابور ويكمل ملفه الجزئي */
    @Synchronized
    fun recoverInterrupted() {
        var changed = false
        jobs.replaceAll { _, j -> if (j.state == "running") { changed = true; j.copy(state = "queued", speed = 0, eta = -1) } else j }
        if (changed) save()
    }

    @Synchronized
    fun remove(id: String): Job? = jobs.remove(id)?.also { save() }

    @Synchronized
    fun clearFinished() {
        if (jobs.values.removeAll { it.state == "failed" || it.state == "canceled" }) save()
    }
}
