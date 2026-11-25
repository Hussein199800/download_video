package com.tadaffuq.app.core

/** بحث عربي متسامح: يوحّد الهمزات والتاء المربوطة والألف المقصورة ويحذف التشكيل والتطويل. */
object Search {
    private val diacritics = Regex("[\\u064B-\\u0652\\u0670\\u0640]")

    fun normalize(s: String): String = s.lowercase()
        .replace(diacritics, "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
        .replace('ة', 'ه').replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي')

    fun matches(text: String, query: String): Boolean {
        val q = normalize(query.trim())
        return q.isEmpty() || normalize(text).contains(q)
    }
}
