package com.sayl.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/** تجربة حية على تيك توك نفسه؛ لا تعمل إلا بـ SAYL_LIVE=1 حتى لا يعتمد CI على الشبكة */
class TikTokLiveTest {
    @Test
    fun readsRealPhotoPostAndDownloadsAnImage() {
        assumeTrue(System.getenv("SAYL_LIVE") == "1")
        val a = TikTokPhotos.read("https://www.tiktok.com/@chillezy/photo/7240568259186019630")
        assertEquals(16, a.items.size)
        assertEquals((1..16).toList(), a.items.map { it.index })
        val dir = Files.createTempDirectory("tt").toFile()
        val f = TikTokPhotos.fetch(a.items[2].urls, dir, "003", { false }) { _, _ -> }
        assertEquals("003.jpg", f.name)
        assertTrue(f.length() > 10_000)
        // رابط منتهي الصلاحية وتوقيعه خاطئ: Expired ليعيد العامل قراءة المنشور
        val broken = a.items[0].urls[0].replace(Regex("x-signature=[^&]+"), "x-signature=AAAA").replace(Regex("x-expires=\\d+"), "x-expires=1000000000")
        val bad = runCatching { TikTokPhotos.fetch(listOf(broken), dir, "x", { false }) { _, _ -> } }.exceptionOrNull()
        assertTrue("$bad", bad is TikTokPhotos.Expired)
    }
}
