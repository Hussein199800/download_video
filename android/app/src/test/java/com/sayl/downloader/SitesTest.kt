package com.sayl.downloader

import org.junit.Assert.assertEquals
import org.junit.Test

class SitesTest {
    @Test
    fun platformFromPostLink() {
        assertEquals("tiktok", Sites.of("https://vm.tiktok.com/ZMabc/"))
        assertEquals("youtube", Sites.of("https://youtu.be/x"))
        assertEquals("youtube", Sites.of("https://m.youtube.com/watch?v=1"))
        assertEquals("facebook", Sites.of("https://fb.watch/abc"))
        assertEquals("x", Sites.of("https://twitter.com/a/status/1"))
        // نطاق يشبه اسم المنصة دون أن يكون منها
        assertEquals("other", Sites.of("https://notyoutube.com/v"))
        assertEquals("other", Sites.of("not a url"))
    }

    @Test
    fun folderPrefersKnownSiteThenUrl() {
        assertEquals("تيك توك", Sites.folder("tiktok", "https://example.com"))
        // طابور قديم بلا هوية أو بهوية «أخرى»: من الرابط
        assertEquals("يوتيوب", Sites.folder(null, "https://youtu.be/x"))
        assertEquals("يوتيوب", Sites.folder("other", "https://youtu.be/x"))
        assertEquals("مواقع أخرى", Sites.folder("other", "https://vimeo.com/1"))
    }

    @Test
    fun segmentsAreSanitizedSeparately() {
        assertEquals("سيل/يوتيوب/قائمة أ ب", Saver.subPath(listOf("يوتيوب", "قائمة أ/ب")))
        assertEquals("سيل/فيسبوك", Saver.subPath(listOf("فيسبوك", null, "  ")))
        assertEquals(80, Saver.subPath(listOf("إكس", "ط".repeat(200))).substringAfterLast('/').length)
    }
}
