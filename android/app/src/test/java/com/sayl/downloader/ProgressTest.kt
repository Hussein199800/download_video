package com.sayl.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTest {
    @Test
    fun parsesYtDlpLine() {
        val p = Progress.parse("[download]  42.0% of ~ 10.50MiB at  1.20MiB/s ETA 00:05 (frag 3/20)")
        assertEquals(4624220L, p.downloaded)
        assertEquals(11010048L, p.total)
        assertEquals(1258291L, p.speed)
    }

    @Test
    fun parsesUnknownSpeed() {
        val p = Progress.parse("[download]   0.0% of   59.01MiB at  Unknown B/s ETA Unknown")
        assertEquals(0L, p.downloaded)
        assertEquals(61876469L, p.total)
        assertNull(p.speed)
    }

    @Test
    fun parsesAria2Line() {
        val p = Progress.parse("[#a1b2c3 1.2MiB/10MiB(12%) CN:4 DL:800KiB ETA:10s]")
        assertEquals(1258291L, p.downloaded)
        assertEquals(10485760L, p.total)
        assertEquals(819200L, p.speed)
    }

    @Test
    fun ignoresOtherLines() {
        val p = Progress.parse("[youtube] Extracting URL")
        assertNull(p.downloaded)
        assertNull(p.total)
        assertNull(p.speed)
    }
}
