package com.sayl.downloader

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** ملف مُدخل أو ناتج: رابط المحتوى، والاسم الظاهر، والحجم، والمسار الظاهر للمستخدم */
data class MediaRef(val uri: String, val name: String, val size: Long = 0, val path: String? = null, val mime: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("uri", uri).put("name", name).put("size", size).put("path", path).put("mime", mime)

    companion object {
        fun fromJson(o: JSONObject) = MediaRef(
            o.getString("uri"), o.optString("name", "ملف"), o.optLong("size"),
            if (o.isNull("path")) null else o.optString("path").ifEmpty { null },
            if (o.isNull("mime")) null else o.optString("mime").ifEmpty { null },
        )
    }
}

/**
 * عملية معالجة واحدة.
 * op: convert | audio | compress | trim | merge | frame
 * state: queued | running | done | failed | canceled
 * params: إعدادات العملية كما أرسلتها الواجهة (الصيغة، والجودة، والبداية والنهاية…)
 */
data class MediaTask(
    val id: String,
    val op: String,
    val title: String,
    val inputs: List<MediaRef>,
    val params: JSONObject = JSONObject(),
    val state: String = "queued",
    val pct: Float = 0f,
    val stage: String = "",
    /** copy = بلا إعادة ترميز، hw = العتاد، cpu = المعالج */
    val engine: String = "",
    val outputs: List<MediaRef> = emptyList(),
    val error: String? = null,
    val batch: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
) {
    val active get() = state == "queued" || state == "running"
    val srcSize get() = inputs.sumOf { it.size }
    val outSize get() = outputs.sumOf { it.size }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("op", op).put("title", title).put("inputs", JSONArray(inputs.map { it.toJson() }))
        .put("params", params).put("state", state).put("pct", pct.toDouble()).put("stage", stage).put("engine", engine)
        .put("outputs", JSONArray(outputs.map { it.toJson() })).put("error", error).put("batch", batch)
        .put("srcSize", srcSize).put("outSize", outSize)
        .put("createdAt", createdAt).put("startedAt", startedAt).put("finishedAt", finishedAt)

    companion object {
        private fun refs(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { MediaRef.fromJson(a!!.getJSONObject(it)) }

        fun fromJson(o: JSONObject) = MediaTask(
            id = o.getString("id"), op = o.getString("op"), title = o.optString("title"),
            inputs = refs(o.optJSONArray("inputs")), params = o.optJSONObject("params") ?: JSONObject(),
            state = o.optString("state", "queued"), pct = o.optDouble("pct", 0.0).toFloat(), stage = o.optString("stage"),
            engine = o.optString("engine"), outputs = refs(o.optJSONArray("outputs")),
            error = if (o.isNull("error")) null else o.optString("error").ifEmpty { null },
            batch = if (o.isNull("batch")) null else o.optString("batch").ifEmpty { null },
            createdAt = o.optLong("createdAt"), startedAt = o.optLong("startedAt"), finishedAt = o.optLong("finishedAt"),
        )
    }
}

/** طابور المعالجة على القرص؛ مصدر الحقيقة لعمليات المعالجة كما JobStore للتنزيلات. */
class TaskStore(private val file: File) {
    private val tasks = LinkedHashMap<String, MediaTask>()

    init {
        runCatching {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) MediaTask.fromJson(a.getJSONObject(i)).let { tasks[it.id] = it }
        }
    }

    private fun save() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray(tasks.values.map { it.toJson() }).toString())
        tmp.renameTo(file)
    }

    @Synchronized fun all(): List<MediaTask> = tasks.values.toList()
    @Synchronized fun get(id: String) = tasks[id]

    @Synchronized fun add(list: List<MediaTask>) { list.forEach { tasks[it.id] = it }; save() }

    @Synchronized fun update(id: String, fn: (MediaTask) -> MediaTask): MediaTask? {
        val t = tasks[id] ?: return null
        return fn(t).also { tasks[id] = it; save() }
    }

    /** تحديث التقدم في الذاكرة فقط؛ يُحفظ مع أول تغيير للحالة */
    @Synchronized fun live(id: String, fn: (MediaTask) -> MediaTask): MediaTask? {
        val t = tasks[id] ?: return null
        return fn(t).also { tasks[id] = it }
    }

    /** أقدم مهمة منتظرة تُحجز للتشغيل */
    @Synchronized fun claim(): MediaTask? {
        val t = tasks.values.filter { it.state == "queued" }.minByOrNull { it.createdAt } ?: return null
        return t.copy(state = "running", startedAt = System.currentTimeMillis(), pct = 0f, error = null).also { tasks[t.id] = it; save() }
    }

    @Synchronized fun remove(id: String) { tasks.remove(id); save() }

    /** ما انتهى (المكتمل والفاشل والملغى) يُمسح من القائمة؛ الملفات الناتجة تبقى */
    @Synchronized fun clearFinished() { tasks.values.removeAll { !it.active }; save() }

    /** بعد توقف العملية فجأة: ما كان «يعمل» يعود إلى الطابور */
    @Synchronized fun recoverInterrupted() {
        var changed = false
        tasks.replaceAll { _, t -> if (t.state == "running") { changed = true; t.copy(state = "queued", pct = 0f, stage = "") } else t }
        if (changed) save()
    }
}
