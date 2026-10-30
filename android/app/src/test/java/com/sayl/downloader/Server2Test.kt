package com.sayl.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Server2Test {
    private fun v(h: Int, ext: String, codec: String = if (ext == "mp4") "avc1.4d401f" else "vp9", br: Int = h * 1000) =
        StreamPicker.Media(url = "v$h$ext$codec", height = h, ext = ext, codec = codec, bitrate = br)
    private fun a(ext: String, br: Int) = StreamPicker.Media(url = "a$ext$br", height = 0, ext = ext, codec = "", bitrate = br)

    @Test
    fun picksBestVideoAtOrBelowHeightPreferringH264() {
        val list = listOf(v(1080, "mp4"), v(1080, "webm"), v(720, "webm"), v(720, "mp4"), v(360, "mp4"))
        assertEquals("v720mp4avc1.4d401f", StreamPicker.video(list, 720)!!.url)
        assertEquals("v1080mp4avc1.4d401f", StreamPicker.video(list, 0)!!.url)
        // لا توجد دقة ≤ المطلوبة: أقل المتاح بدل الفشل
        assertEquals("v360mp4avc1.4d401f", StreamPicker.video(list, 240)!!.url)
        assertNull(StreamPicker.video(emptyList(), 720))
    }

    @Test
    fun picksBestM4aAudio() {
        val list = listOf(a("webma", 160_000), a("m4a", 128_000), a("m4a", 48_000))
        assertEquals("am4a128000", StreamPicker.audio(list, preferM4a = true)!!.url)
        assertEquals("awebma160000", StreamPicker.audio(list, preferM4a = false)!!.url)
    }

    @Test
    fun supportedServices() {
        assertTrue(Server2.supports("https://www.youtube.com/watch?v=x"))
        assertTrue(Server2.supports("https://youtu.be/x"))
        assertTrue(Server2.supports("https://m.youtube.com/shorts/x"))
        assertTrue(Server2.supports("https://soundcloud.com/a/b"))
        assertTrue(Server2.supports("https://artist.bandcamp.com/track/x"))
        assertTrue(Server2.supports("https://media.ccc.de/v/x"))
        assertFalse(Server2.supports("https://www.tiktok.com/@a/video/1"))
        assertFalse(Server2.supports("https://www.youtube.com/playlist?list=PL1"))
        assertFalse(Server2.supports("ليس رابطاً"))
    }

    @Test
    fun switchingPolicy() {
        val yt = "https://youtu.be/x"
        val tt = "https://www.tiktok.com/@a/video/1"
        val h = 3_600_000L
        // لم يُحدَّث منذ 6 ساعات: حدّث ثم أعد سيرفر 1
        assertEquals(ServerPolicy.Step.UPDATE_AND_RETRY, ServerPolicy.next(Failure.Kind.EXTRACT, yt, lastUpdateAt = 0, now = 10 * h, triedUpdate = false, triedServer2 = false))
        // حُدّث مؤخراً: انتقل إلى سيرفر 2 إن كان يدعم الرابط
        assertEquals(ServerPolicy.Step.SERVER2, ServerPolicy.next(Failure.Kind.EXTRACT, yt, 9 * h, 10 * h, false, false))
        assertEquals(ServerPolicy.Step.SERVER2, ServerPolicy.next(Failure.Kind.TEMPORARY, yt, 0, 10 * h, true, false))
        // لا يدعمه سيرفر 2، أو جُرّب: إعادة مؤجلة
        assertEquals(ServerPolicy.Step.DEFER, ServerPolicy.next(Failure.Kind.EXTRACT, tt, 9 * h, 10 * h, false, false))
        assertEquals(ServerPolicy.Step.DEFER, ServerPolicy.next(Failure.Kind.EXTRACT, yt, 9 * h, 10 * h, true, true))
        // الشبكة والدائم لا يبدّلان
        assertEquals(ServerPolicy.Step.FAIL, ServerPolicy.next(Failure.Kind.PERMANENT, yt, 0, 10 * h, false, false))
        assertEquals(ServerPolicy.Step.DEFER, ServerPolicy.next(Failure.Kind.NETWORK, yt, 0, 10 * h, false, false))
    }
}
