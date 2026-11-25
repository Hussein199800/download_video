package com.tadaffuq.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PureLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun initialSegmentsCoverFile() {
        val segs = SegmentPlanner.initial(1003, 4).map { it.state() }
        assertEquals(4, segs.size)
        assertTrue(SegmentPlanner.isConsistent(segs, 1003))
        assertEquals(listOf(0L, 251L, 502L, 753L), segs.map { it.start })
    }

    @Test fun tinyFileGetsOneConnection() {
        assertEquals(1, SegmentPlanner.autoConnections(100_000, 8, 512 * 1024))
        assertEquals(8, SegmentPlanner.autoConnections(1L shl 30, 32, 512 * 1024))
    }

    @Test fun splitLargestHalvesRemainingPart() {
        val segs = SegmentPlanner.initial(10_000_000, 2).toMutableList()
        segs[0].downloaded = 4_000_000
        val s = SegmentPlanner.splitLargest(segs, 512 * 1024)!!
        assertTrue(SegmentPlanner.isConsistent(segs.map { it.state() }, 10_000_000))
        assertEquals(2, s.index)
        assertTrue(s.start > 5_000_000) // الجزء الثاني (غير المبدوء) هو الأكبر
    }

    @Test fun splitRefusesSmallRemainders() {
        val segs = SegmentPlanner.initial(600_000, 1).toMutableList()
        assertNull(SegmentPlanner.splitLargest(segs, 512 * 1024))
    }

    @Test fun inconsistentSegmentsAreRejected() {
        val bad = listOf(SegmentState(0, 0, 99, 0), SegmentState(1, 101, 199, 0))
        assertFalse(SegmentPlanner.isConsistent(bad, 200))
        val over = listOf(SegmentState(0, 0, 99, 500))
        assertFalse(SegmentPlanner.isConsistent(over, 100))
    }

    @Test fun contentDispositionVariants() {
        assertEquals("تقرير.pdf", FileNames.resolve("attachment; filename*=UTF-8''%D8%AA%D9%82%D8%B1%D9%8A%D8%B1.pdf", "https://x/y", null))
        assertEquals("a b.zip", FileNames.resolve("attachment; filename=\"a b.zip\"", "https://x/y", null))
        assertEquals("file.iso", FileNames.resolve(null, "https://x/dir/file.iso?token=1", null))
        assertEquals("download.zip", FileNames.resolve(null, "https://x/", "application/zip"))
        assertEquals("_etc_passwd", FileNames.sanitize("/etc/passwd"))
        assertFalse(FileNames.sanitize("../../x").contains("/"))
    }

    @Test fun uniqueNameNeverOverwrites() {
        val dir = tmp.newFolder()
        java.io.File(dir, "a.zip").writeText("1")
        java.io.File(dir, "a (1).zip").writeText("2")
        assertEquals("a (2).zip", FileNames.uniqueIn(dir, "a.zip"))
        assertEquals("b.zip", FileNames.uniqueIn(dir, "b.zip"))
    }

    @Test fun linkExtractionFromArabicText() {
        val t = "حمّل من https://ex.com/a.zip، والثاني (https://ex.com/b.iso) ثم https://ex.com/a.zip."
        assertEquals(listOf("https://ex.com/a.zip", "https://ex.com/b.iso"), LinkTools.extract(t))
    }

    @Test fun sequenceExpansion() {
        val l = LinkTools.expandSequence("https://x/part[001-003].bin")
        assertEquals(listOf("https://x/part001.bin", "https://x/part002.bin", "https://x/part003.bin"), l)
        assertEquals(listOf("u0", "u5", "u10"), LinkTools.expandSequence("u[0-10:5]"))
        assertEquals(LinkTools.MAX_GENERATED, LinkTools.expandSequence("u[1-999999]").size)
    }

    @Test fun csvImport() {
        val csv = "name,url\nA,https://x/a.zip\n\"B\",\"https://x/b.zip\"\n"
        assertEquals(listOf("https://x/a.zip", "https://x/b.zip"), LinkTools.fromCsv(csv))
    }

    @Test fun hashDetectAndVerify() {
        val f = tmp.newFile().apply { writeText("abc") }
        assertEquals(HashAlgo.SHA256, Hashes.detect("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertEquals(true, Hashes.verify(f, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD")!!.first)
        assertEquals(true, Hashes.verify(f, "900150983cd24fb0d6963f7d28e17f72")!!.first)
        assertNull(Hashes.detect("xyz"))
    }

    @Test fun contentRangeParsing() {
        assertEquals(ContentRange(0, 0, 1000), parseContentRange("bytes 0-0/1000"))
        assertEquals(ContentRange(5, 9, null), parseContentRange("bytes 5-9/*"))
        assertNull(parseContentRange("items 0-1/2"))
    }

    @Test fun sameContentRules() {
        val p = ProbeResult("u", 10, true, "\"a\"", "d1", "f", null)
        assertTrue(Prober.sameContent(p, p))
        assertFalse(Prober.sameContent(p, p.copy(totalBytes = 11)))
        assertFalse(Prober.sameContent(p, p.copy(etag = "\"b\"")))
        assertTrue(Prober.sameContent(p, p.copy(etag = null)))
        assertEquals("d1", p.copy(etag = "W/\"x\"").ifRangeValidator)
    }

    @Test fun autoTuneBacksOffWhenNoGain() {
        val t = HttpDownloader.AutoTune(DownloadConfig(maxConnections = 16), true)
        var target = 4
        var now = 0L
        repeat(5) { target = t.next(target, 1000, now, target); now += 1_000_000_000 }
        assertEquals(6, target)
        repeat(6) { target = t.next(target, 1000, now, target); now += 1_000_000_000 } // لا زيادة في السرعة
        assertEquals(4, target)
        assertTrue(t.finished)
    }

    @Test fun doctorFindings() {
        val p = ProbeResult("u", -1, false, null, null, "f", null)
        val f = Doctor.diagnose(Doctor.Input(p, null))
        assertTrue(Doctor.Finding.NO_RESUME in f && Doctor.Finding.UNKNOWN_SIZE in f)
        assertEquals(listOf(Doctor.Finding.LINK_EXPIRED), Doctor.diagnose(Doctor.Input(null, DownloadError.LinkExpired(403))))
    }
}
