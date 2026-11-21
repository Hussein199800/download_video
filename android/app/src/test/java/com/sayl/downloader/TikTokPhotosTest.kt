package com.sayl.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TikTokPhotosTest {
    private val page = javaClass.getResource("/tiktok-photo.html")!!.readText()

    @Test
    fun readsImagesInSourceOrderWithPostData() {
        val a = TikTokPhotos.parse(page)!!
        assertEquals("7553302113757990166", a.id)
        assertEquals("hullcity", a.author)
        assertEquals("https://www.tiktok.com/@hullcity/photo/7553302113757990166", a.url)
        assertEquals(listOf(1, 2), a.items.map { it.index })
        assertTrue(a.items.all { it.urls.isNotEmpty() && it.urls.all { u -> u.startsWith("https://") } })
        // الوصف العربي وأسطره كما وردت
        assertEquals("وصف عربي للمنشور\nسطر ثانٍ #تجربة", a.title)
        val json = org.json.JSONObject(TikTokPhotos.toJson(a))
        assertEquals("album", json.getString("_type"))
        assertEquals(2, json.getJSONArray("entries").length())
    }

    @Test
    fun videoPageIsNotAnAlbum() {
        val video = page.replace("\"imagePost\"", "\"video\"")
        assertNull(TikTokPhotos.parse(video))
    }

    @Test
    fun deletedOrPrivatePostGivesArabicReason() {
        val gone = """<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">{"__DEFAULT_SCOPE__":{"webapp.reflow.video.detail":{"statusCode":10204}}}</script>"""
        val e = runCatching { TikTokPhotos.parse(gone) }.exceptionOrNull()
        assertTrue(e is TikTokPhotos.NotPhoto)
        assertTrue(e!!.message!!.contains("محذوف") || e.message!!.contains("غير موجود"))
        assertTrue(runCatching { TikTokPhotos.parse("<html>challenge</html>") }.exceptionOrNull() is TikTokPhotos.NotPhoto)
    }

    @Test
    fun recognisesPhotoLinks() {
        assertTrue(TikTokPhotos.isPhotoUrl("https://www.tiktok.com/@a/photo/1"))
        assertTrue(!TikTokPhotos.isPhotoUrl("https://www.tiktok.com/@a/video/1"))
        assertTrue(!TikTokPhotos.isPhotoUrl("https://example.com/photo/1"))
    }

    @Test
    fun extensionFromBytes() {
        assertEquals("jpg", TikTokPhotos.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("webp", TikTokPhotos.sniff("RIFF0000WEBPVP8 ".toByteArray()))
        assertEquals("png", TikTokPhotos.sniff(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)))
        assertEquals("mp3", TikTokPhotos.sniff("ID3abc".toByteArray()))
        assertNull(TikTokPhotos.sniff("<html>".toByteArray()))
    }
}
