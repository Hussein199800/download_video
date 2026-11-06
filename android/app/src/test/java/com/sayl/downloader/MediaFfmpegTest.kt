package com.sayl.downloader

import com.sayl.downloader.MediaArgs.AudioOpts
import com.sayl.downloader.MediaArgs.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * تشغيل الأوامر نفسها التي يبنيها MediaArgs بـ FFmpeg حقيقي على ملفات مولّدة بصيغ وأحجام مختلفة،
 * ثم التحقق بـ ffprobe من الأبعاد والمدة والترميز ووجود الصوت. يُتجاوز إن لم يكن FFmpeg مثبّتاً.
 */
class MediaFfmpegTest {
    companion object {
        private lateinit var dir: File
        private var ok = false

        private fun run(args: List<String>, bin: String = "ffmpeg"): String {
            val p = ProcessBuilder(listOf(bin) + args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "$bin ${args.joinToString(" ")}\n${out.takeLast(1500)}" }
            return out
        }

        fun probe(f: File) = MediaArgs.parseProbe(run(listOf("-v", "error", "-show_format", "-show_streams", "-of", "json", f.path), "ffprobe"))

        private fun gen(name: String, seconds: Int, w: Int, h: Int, v: List<String>, a: List<String>?): File {
            val f = File(dir, name)
            val args = mutableListOf("-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=${w}x$h:rate=30:duration=$seconds")
            if (a != null) args += listOf("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=$seconds")
            args += v
            if (a != null) args += a
            args += listOf("-shortest", f.path)
            run(args)
            return f
        }

        @BeforeClass
        @JvmStatic
        fun setup() {
            ok = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)
            if (!ok) return
            dir = createTempDir("sayl-media")
            gen("a.mp4", 4, 640, 360, listOf("-c:v", "libx264", "-preset", "ultrafast", "-g", "30", "-pix_fmt", "yuv420p"), listOf("-c:a", "aac", "-b:a", "128k"))
            gen("b.webm", 3, 640, 360, listOf("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "500k"), listOf("-c:a", "libopus"))
            gen("c.avi", 3, 320, 240, listOf("-c:v", "mpeg4", "-q:v", "5"), listOf("-c:a", "libmp3lame"))
            gen("d.mp4", 2, 1280, 720, listOf("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p"), null)
            gen("big.mp4", 6, 1920, 1080, listOf("-c:v", "libx264", "-preset", "ultrafast", "-b:v", "6M", "-pix_fmt", "yuv420p"), listOf("-c:a", "aac", "-b:a", "192k"))
        }
    }

    private fun f(n: String) = File(dir, n)
    private fun out(n: String) = File(dir, n).also { it.delete() }

    @Test
    fun convertsBetweenContainersKeepingDimensions() {
        assumeTrue(ok)
        val cases = listOf("a.mp4" to "mkv", "a.mp4" to "mov", "a.mp4" to "avi", "a.mp4" to "webm", "b.webm" to "mp4", "b.webm" to "mkv", "c.avi" to "mp4")
        for ((src, target) in cases) {
            val p = probe(f(src))
            val o = out("conv-${src.substringBefore('.')}.$target")
            run(MediaArgs.convertVideo(f(src).path, o.path, target, p))
            val r = probe(o)
            assertEquals("$src→$target width", p.video!!.width, r.video!!.width)
            assertEquals("$src→$target height", p.video!!.height, r.video!!.height)
            assertTrue("$src→$target audio", r.hasAudio)
            assertTrue("$src→$target duration ${r.duration}", abs(r.duration - p.duration) < 0.5)
            assertTrue("$src→$target codec ${r.video!!.codec}", MediaArgs.videoCopyOk(target, r.video!!.codec))
            // الحاوية التي تقبل الترميز الأصلي لا تعيد ترميزه
            if (MediaArgs.videoCopyOk(target, p.video!!.codec)) assertEquals(p.video!!.codec, r.video!!.codec)
        }
    }

    @Test
    fun extractsAndConvertsAudio() {
        assumeTrue(ok)
        val expect = mapOf("mp3" to "mp3", "m4a" to "aac", "aac" to "aac", "wav" to "pcm_s16le", "flac" to "flac", "opus" to "opus")
        for ((fmt, codec) in expect) {
            val o = out("audio.$fmt")
            run(MediaArgs.audio(f("a.mp4").path, o.path, AudioOpts(fmt, kbps = if (fmt == "mp3") 128 else 0, sampleRate = if (fmt == "wav") 22050 else 0), probe(f("a.mp4"))))
            val r = probe(o)
            assertEquals(fmt, codec, r.audio!!.codec)
            assertTrue("$fmt has no video", !r.hasVideo)
            assertTrue("$fmt duration ${r.duration}", abs(r.duration - 4.0) < 0.3)
            if (fmt == "wav") assertEquals(22050, r.audio!!.sampleRate)
            if (fmt == "opus") assertEquals(48000, r.audio!!.sampleRate)
        }
        // صوت من مقطع محدد فقط
        val seg = out("seg.mp3")
        run(MediaArgs.audio(f("a.mp4").path, seg.path, AudioOpts("mp3", start = 1.0, end = 2.5), probe(f("a.mp4"))))
        assertTrue(abs(probe(seg).duration - 1.5) < 0.2)
    }

    @Test
    fun trimsFastAndPrecise() {
        assumeTrue(ok)
        val p = probe(f("a.mp4"))
        val fast = out("trim-fast.mp4")
        run(MediaArgs.trimCopy(f("a.mp4").path, fast.path, 1.0, 3.0, "mp4"))
        val rf = probe(fast)
        assertEquals(640, rf.video!!.width)
        // القص السريع يبدأ من إطار مفتاحي، فالمدة قريبة لا مطابقة
        assertTrue("fast ${rf.duration}", rf.duration in 1.5..3.2)
        val precise = out("trim-precise.mp4")
        run(MediaArgs.trimPrecise(f("a.mp4").path, precise.path, 1.2, 2.7, p))
        val rp = probe(precise)
        assertTrue("precise ${rp.duration}", abs(rp.duration - 1.5) < 0.15)
        assertEquals(360, rp.video!!.height)
    }

    @Test
    fun mergesByCopyOrEncode() {
        assumeTrue(ok)
        val a = probe(f("a.mp4"))
        // نسخ: ملفان متطابقان
        val list = File(dir, "list.txt").apply { writeText(MediaArgs.concatList(listOf(f("a.mp4").path, f("a.mp4").path))) }
        val copy = out("merge-copy.mp4")
        assertTrue(MediaArgs.canConcatCopy(listOf(a, a)))
        run(MediaArgs.concatCopy(list.path, copy.path))
        assertTrue(abs(probe(copy).duration - 8.0) < 0.3)
        // ترميز: أبعاد مختلفة ومقطع بلا صوت؛ الناتج بأبعاد الأول والصوت متزامن
        val d = probe(f("d.mp4"))
        val c = probe(f("c.avi"))
        val enc = out("merge-enc.mp4")
        run(MediaArgs.concatEncode(listOf(f("a.mp4").path, f("d.mp4").path, f("c.avi").path), enc.path, listOf(a, d, c)))
        val r = probe(enc)
        assertEquals(640, r.video!!.width)
        assertEquals(360, r.video!!.height)
        assertTrue(r.hasAudio)
        assertTrue("merged ${r.duration}", abs(r.duration - 9.0) < 0.4)
    }

    @Test
    fun extractsFrame() {
        assumeTrue(ok)
        val o = out("frame.jpg")
        run(MediaArgs.frame(f("big.mp4").path, o.path, 2.0))
        val r = probe(o)
        assertEquals(1920, r.video!!.width)
        assertEquals(1080, r.video!!.height)
    }

    @Test
    fun compressesWithoutChangingDimensions() {
        assumeTrue(ok)
        val p = probe(f("big.mp4"))
        val plan = MediaArgs.compressPlan(p, Level.MEDIUM, hevc = false)
        val o = out("compressed.mp4")
        run(MediaArgs.compressCpu(f("big.mp4").path, o.path, plan, p))
        val r = probe(o)
        assertEquals(1920, r.video!!.width)
        assertEquals(1080, r.video!!.height)
        assertEquals(p.video!!.fps, r.video!!.fps, 0.1)
        assertTrue("smaller ${o.length()} < ${f("big.mp4").length()}", o.length() < f("big.mp4").length())
        assertTrue(abs(r.duration - p.duration) < 0.3)
    }
}
