package com.sayl.downloader

/** اختيار أنسب ملف فيديو وصوت للسيرفر 2، دون اعتماد على أنواع NewPipe لتسهيل الاختبار. */
object StreamPicker {
    data class Media(val url: String, val height: Int, val ext: String, val codec: String, val bitrate: Int)

    private fun h264(m: Media) = m.ext == "mp4" && m.codec.startsWith("avc")

    /** أعلى دقة ≤ المطلوبة (0 = أفضل) مع تفضيل H.264؛ وإن لم توجد فأقل دقة متاحة */
    fun video(list: List<Media>, height: Int): Media? {
        if (list.isEmpty()) return null
        val fit = if (height > 0) list.filter { it.height <= height } else list
        if (fit.isEmpty()) return list.sortedWith(compareBy<Media> { it.height }.thenByDescending { h264(it) }).first()
        return fit.sortedWith(compareByDescending<Media> { it.height }.thenByDescending { h264(it) }.thenByDescending { it.bitrate }).first()
    }

    fun audio(list: List<Media>, preferM4a: Boolean): Media? {
        val m4a = list.filter { it.ext == "m4a" }
        return (if (preferM4a && m4a.isNotEmpty()) m4a else list).maxByOrNull { it.bitrate }
    }
}

/** متى نبدّل من السيرفر 1 إلى السيرفر 2. */
object ServerPolicy {
    enum class Step { UPDATE_AND_RETRY, SERVER2, DEFER, FAIL }

    private const val UPDATE_EVERY = 6 * 3_600_000L

    fun next(kind: Failure.Kind, url: String, lastUpdateAt: Long, now: Long, triedUpdate: Boolean, triedServer2: Boolean): Step = when {
        kind == Failure.Kind.PERMANENT -> Step.FAIL
        kind == Failure.Kind.NETWORK -> Step.DEFER
        !triedUpdate && now - lastUpdateAt > UPDATE_EVERY -> Step.UPDATE_AND_RETRY
        !triedServer2 && Server2.supports(url) -> Step.SERVER2
        else -> Step.DEFER
    }
}
