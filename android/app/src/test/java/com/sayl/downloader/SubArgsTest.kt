package com.sayl.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubArgsTest {
    @Test
    fun stripsOnlySubtitleOptions() {
        val a = listOf("--no-playlist", "-f", "bv*+ba/b", "--write-auto-subs", "--sub-langs", "en-orig", "--convert-subs", "srt", "--embed-subs", "-N", "4")
        assertTrue(SubArgs.has(a))
        val s = SubArgs.strip(a)
        assertEquals(listOf("--no-playlist", "-f", "bv*+ba/b", "-N", "4"), s)
        assertFalse(SubArgs.has(s))
        assertTrue(SubArgs.failed("ERROR: Unable to download video subtitles for 'ar': HTTP Error 429: Too Many Requests"))
        assertFalse(SubArgs.failed("ERROR: Unsupported URL"))
    }
}
