package com.sayl.downloader

import com.sayl.downloader.MediaArgs.AudioOpts
import com.sayl.downloader.MediaArgs.Level
import com.sayl.downloader.MediaArgs.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaArgsTest {
    private val probeJson = """
        {"streams":[
          {"index":0,"codec_name":"h264","codec_type":"video","width":1920,"height":1080,"avg_frame_rate":"30000/1001","r_frame_rate":"30000/1001","bit_rate":"4000000","disposition":{"attached_pic":0},"side_data_list":[{"rotation":-90}]},
          {"index":1,"codec_name":"aac","codec_type":"audio","sample_rate":"44100","channels":2,"bit_rate":"128000"},
          {"index":2,"codec_name":"mov_text","codec_type":"subtitle"}],
         "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"60.5","size":"31250000","bit_rate":"4132231"}}
    """.trimIndent()

    @Test
    fun parsesProbe() {
        val p = MediaArgs.parseProbe(probeJson)
        assertEquals(60.5, p.duration, 0.001)
        assertEquals("h264", p.video!!.codec)
        assertEquals(1920, p.video!!.width)
        assertEquals(29.97, p.video!!.fps, 0.01)
        assertEquals(-90, p.video!!.rotation)
        assertEquals("aac", p.audio!!.codec)
        assertEquals(44100, p.audio!!.sampleRate)
        assertEquals(1, p.subtitleCount)
    }

    @Test
    fun coverArtIsNotVideo() {
        val p = MediaArgs.parseProbe("""{"streams":[{"codec_type":"audio","codec_name":"mp3","sample_rate":"44100","channels":2},
            {"codec_type":"video","codec_name":"mjpeg","width":500,"height":500,"disposition":{"attached_pic":1}}],"format":{"duration":"10","size":"160000"}}""")
        assertNull(p.video)
        assertTrue(p.hasAudio)
    }

    @Test
    fun estimatesMissingVideoBitrateFromTotal() {
        val p = MediaArgs.parseProbe("""{"streams":[{"codec_type":"video","codec_name":"vp9","width":1280,"height":720,"avg_frame_rate":"25/1"},
            {"codec_type":"audio","codec_name":"opus","sample_rate":"48000","channels":2}],"format":{"duration":"10","size":"2500000","bit_rate":"2000000"}}""")
        assertEquals(1_872_000L, p.video!!.bitrate)
    }

    private fun probe(v: String?, a: String?) = MediaArgs.Probe(10.0, 1, 1, "x",
        v?.let { MediaArgs.VStream(it, 1280, 720, 30.0, 3_000_000) }, a?.let { MediaArgs.AStream(it, 48000, 2, 128_000) })

    @Test
    fun remuxWhenContainerAcceptsCodecs() {
        assertEquals(Mode.COPY, MediaArgs.videoMode("mkv", probe("vp9", "opus")))
        assertEquals(Mode.COPY, MediaArgs.videoMode("mp4", probe("h264", "aac")))
        assertEquals(Mode.COPY, MediaArgs.videoMode("mov", probe("hevc", "aac")))
        assertEquals(Mode.AUDIO_ONLY, MediaArgs.videoMode("avi", probe("h264", "aac")))
        assertEquals(Mode.AUDIO_ONLY, MediaArgs.videoMode("mp4", probe("h264", "vorbis")))
        assertEquals(Mode.ENCODE, MediaArgs.videoMode("webm", probe("h264", "aac")))
        assertEquals(Mode.ENCODE, MediaArgs.videoMode("mp4", probe("wmv3", "aac")))
    }

    @Test
    fun hevcInMp4GetsHvc1Tag() {
        val a = MediaArgs.convertVideo("in.mkv", "out.mp4", "mp4", probe("hevc", "aac"))
        assertTrue(a.joinToString(" ").contains("-c:v copy"))
        assertTrue(a.joinToString(" ").contains("-tag:v hvc1"))
        assertFalse(a.contains("-o"))
    }

    @Test
    fun audioCopyOnlyWhenNothingChanges() {
        assertTrue(MediaArgs.audioCopy(AudioOpts("m4a"), "aac"))
        assertFalse(MediaArgs.audioCopy(AudioOpts("m4a", kbps = 128), "aac"))
        assertFalse(MediaArgs.audioCopy(AudioOpts("mp3"), "aac"))
        // Opus يقبل معدلات عينة محددة فقط: 44100 تصبح 48000
        val a = MediaArgs.audioCodecArgs(AudioOpts("opus", sampleRate = 44100), "aac")
        assertEquals("48000", a[a.indexOf("-ar") + 1])
    }

    @Test
    fun compressionKeepsDimensionsAndNeverExceedsSource() {
        val p = MediaArgs.Probe(60.0, 0, 0, "mp4", MediaArgs.VStream("h264", 1920, 1080, 30.0, 8_000_000), MediaArgs.AStream("aac", 48000, 2, 128_000))
        val smart = MediaArgs.compressPlan(p, Level.SMART, hevc = false)
        val strong = MediaArgs.compressPlan(p, Level.STRONG, hevc = false)
        val light = MediaArgs.compressPlan(p, Level.LIGHT, hevc = false)
        assertTrue(smart.videoBps <= 8_000_000 * 0.7)
        assertTrue(strong.videoBps < light.videoBps)
        assertTrue(smart.worth)
        // التقدير = (صورة + صوت) × المدة ÷ 8
        assertEquals(((smart.videoBps + 128_000) * 60.0 / 8).toLong(), smart.estimate)
        // ملف مضغوط أصلاً: الذكي لا يرى فائدة
        val small = p.copy(video = p.video!!.copy(bitrate = 900_000))
        assertFalse(MediaArgs.compressPlan(small, Level.SMART, hevc = false).worth)
    }

    @Test
    fun ffmpegProgressFromTimeLine() {
        assertEquals(50f, MediaArgs.progressOf("frame=  10 fps=0.0 q=-1.0 size=  1kB time=00:00:30.00 bitrate=", 60.0)!!, 0.01f)
        assertNull(MediaArgs.progressOf("Press [q] to stop", 60.0))
    }

    @Test
    fun outputNeverReplacesSource() {
        assertEquals("مقطع (مضغوط).mp4", MediaArgs.outName("مقطع.mp4", "مضغوط", "mp4"))
        assertEquals("a b (صوت).mp3", MediaArgs.outName("a/b.webm", "صوت", "mp3"))
    }

    @Test
    fun concatListEscapesQuotes() {
        assertEquals("file '/a/it'\\''s.mp4'\n", MediaArgs.concatList(listOf("/a/it's.mp4")))
    }

    @Test
    fun copyConcatNeedsMatchingStreams() {
        val a = probe("h264", "aac")
        assertTrue(MediaArgs.canConcatCopy(listOf(a, a)))
        assertFalse(MediaArgs.canConcatCopy(listOf(a, a.copy(video = a.video!!.copy(width = 640)))))
        assertFalse(MediaArgs.canConcatCopy(listOf(a, probe("h264", null))))
        assertNotNull(MediaArgs.concatEncode(listOf("a", "b"), "o.mp4", listOf(a, probe("h264", null))).find { it.contains("anullsrc") })
    }
}
