package com.sayl.downloader

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** مهمة تنزيل واحدة. state: queued | running | done | failed | canceled */
data class Job(
    val id: String,
    val url: String,
    val title: String,
    val kind: String,
    val args: List<String>,
    val thumb: String? = null,
    val site: String = "other",
    val folder: String? = null,
    val state: String = "queued",
    val pct: Float = 0f,
    val speed: Long = 0,
    val eta: Long = -1,
    val error: String? = null,
    val path: String? = null,
    val uri: String? = null,
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
) {
    val active get() = state == "queued" || state == "running"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("url", url).put("title", title).put("kind", kind).put("args", JSONArray(args))
        .put("thumb", thumb).put("site", site).put("folder", folder).put("state", state).put("pct", pct.toDouble())
        .put("speed", speed).put("eta", eta).put("error", error).put("path", path).put("uri", uri)
        .put("attempts", attempts).put("createdAt", createdAt).put("finishedAt", finishedAt)

    companion object {
        private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)

        fun fromJson(o: JSONObject): Job {
            val a = o.optJSONArray("args") ?: JSONArray()
            return Job(
                id = o.getString("id"), url = o.getString("url"), title = o.optString("title"), kind = o.optString("kind", "video"),
                args = List(a.length()) { a.getString(it) }, thumb = o.str("thumb"), site = o.optString("site", "other"),
                folder = o.str("folder"), state = o.optString("state", "queued"), pct = o.optDouble("pct", 0.0).toFloat(),
                speed = o.optLong("speed"), eta = o.optLong("eta", -1), error = o.str("error"), path = o.str("path"),
                uri = o.str("uri"), attempts = o.optInt("attempts"), createdAt = o.optLong("createdAt"), finishedAt = o.optLong("finishedAt"),
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

    /** يعيد ما أُضيف فعلاً؛ الرابط نفسه بالصيغة نفسها لا يُكرر ما دام في الطابور */
    @Synchronized
    fun add(list: List<Job>): List<Job> {
        val added = list.filter { n -> jobs.values.none { it.active && it.url == n.url && it.args == n.args } && n.id !in jobs }
            .distinctBy { it.url to it.args }
        added.forEach { jobs[it.id] = it }
        if (added.isNotEmpty()) save()
        return added
    }

    @Synchronized
    fun get(id: String): Job? = jobs[id]

    @Synchronized
    fun all(): List<Job> = jobs.values.toList()

    @Synchronized
    fun next(): Job? = jobs.values.filter { it.state == "queued" }.minByOrNull { it.createdAt }

    /** يأخذ أقدم مهمة في الطابور ويعلّمها «تعمل» دفعة واحدة، فلا يأخذها عاملان */
    @Synchronized
    fun claim(): Job? {
        val j = next() ?: return null
        return update(j.id) { it.copy(state = "running", pct = 0f, error = null) }
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

    /** بعد إغلاق التطبيق فجأة: ما كان «يعمل» يعود إلى الطابور */
    @Synchronized
    fun recoverInterrupted() {
        var changed = false
        jobs.replaceAll { _, j -> if (j.state == "running") { changed = true; j.copy(state = "queued", pct = 0f, speed = 0, eta = -1) } else j }
        if (changed) save()
    }

    @Synchronized
    fun remove(id: String): Job? = jobs.remove(id)?.also { save() }

    @Synchronized
    fun clearFinished() {
        if (jobs.values.removeAll { it.state == "failed" || it.state == "canceled" }) save()
    }
}
