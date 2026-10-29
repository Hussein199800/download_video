package com.sayl.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTest {
    @Test
    fun parsesYtDlpLine() {
        val (done, speed) = Progress.parse("[download]  42.0% of ~ 10.50MiB at  1.20MiB/s ETA 00:05")
        assertEquals(4624220L, done)
        assertEquals(1258291L, speed)
    }

    @Test
    fun parsesAria2Line() {
        val (done, speed) = Progress.parse("[#a1b2c3 1.2MiB/10MiB(12%) CN:4 DL:800KiB ETA:10s]")
        assertEquals(1258291L, done)
        assertEquals(819200L, speed)
    }

    @Test
    fun ignoresOtherLines() {
        val (done, speed) = Progress.parse("[youtube] Extracting URL")
        assertNull(done)
        assertNull(speed)
    }
}
