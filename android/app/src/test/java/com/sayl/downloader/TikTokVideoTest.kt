package com.sayl.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TikTokVideoTest {
    private fun fixture() = javaClass.classLoader!!.getResource("tiktok-video.html")!!.readText()

    @Test
    fun parsesRealPage() {
        val v = TikTokVideo.parse(fixture())
        assertEquals("6742501081818877190", v.id)
        assertEquals("patrox", v.author)
        assertEquals(27, v.duration)
        assertEquals(960, v.height)
        assertTrue(v.title.startsWith("Tag 1 Friend"))
        assertTrue(v.urls.isNotEmpty() && v.urls.all { it.startsWith("https://") })
        // رابط التشغيل (بلا علامة مائية) قبل رابط التنزيل
        assertTrue(v.urls.indexOfFirst { it.contains("tos-alisg") } < v.urls.indexOfFirst { it.contains("tos-maliva") })
        assertTrue(TikTokVideo.toJson(v).contains("\"server\":3"))
    }

    @Test
    fun unavailableAndPhotoPosts() {
        val deleted = """<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">{"__DEFAULT_SCOPE__":{"webapp.reflow.video.detail":{"statusCode":10204}}}</script>"""
        val e = runCatching { TikTokVideo.parse(deleted) }.exceptionOrNull()
        assertTrue(e is TikTokVideo.Unavailable && e.message!!.contains("محذوف"))
        val photo = javaClass.classLoader!!.getResource("tiktok-photo.html")!!.readText()
        assertTrue(runCatching { TikTokVideo.parse(photo) }.exceptionOrNull()!!.message!!.contains("صور"))
        assertTrue(runCatching { TikTokVideo.parse("<html></html>") }.exceptionOrNull() is TikTokVideo.Unavailable)
    }

    @Test
    fun supportsAndCookies() {
        assertTrue(TikTokVideo.supports("https://www.tiktok.com/@a/video/1"))
        assertTrue(TikTokVideo.supports("https://vm.tiktok.com/ZMabc/"))
        assertFalse(TikTokVideo.supports("https://www.tiktok.com/@a/photo/1"))
        assertFalse(TikTokVideo.supports("https://youtu.be/x"))
        val m = TikTokVideo.mergeCookies(mapOf("ttwid" to "1"), listOf("tt_chain_token=abc; Path=/; Secure", "ttwid=2; HttpOnly", "bad"))
        assertEquals(mapOf("ttwid" to "2", "tt_chain_token" to "abc"), m)
    }

    /** تجربة حية على تيك توك نفسه: SAYL_LIVE=1 */
    @Test
    fun liveDownload() {
        assumeTrue(System.getenv("SAYL_LIVE") == "1")
        val s = TikTokVideo.read("https://www.tiktok.com/@patroxofficial/video/6742501081818877190")
        assertTrue(s.cookie.contains("tt_chain_token"))
        val dir = Files.createTempDirectory("tt").toFile()
        val f = TikTokVideo.download(s, dir, { false }) { _, _ -> }
        assertTrue(f.length() > 500_000)
        assertEquals("ftyp", String(f.readBytes().copyOfRange(4, 8)))
        dir.deleteRecursively()
    }
}
