package com.sayl.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaverTest {
    @Test
    fun folderNameIsSafe() {
        assertEquals("أغاني 2024 الجزء 1", Saver.safeFolder("أغاني: 2024 / الجزء 1"))
        assertEquals("a b c", Saver.safeFolder("a*b?c"))
        assertNull(Saver.safeFolder("  ///  "))
        assertNull(Saver.safeFolder(null))
        assertEquals(80, Saver.safeFolder("x".repeat(200))!!.length)
    }

    @Test
    fun mimeByExtension() {
        assertEquals("video/mp4", Saver.mime("a [x].mp4"))
        assertEquals("audio/mpeg", Saver.mime("a.MP3"))
        assertEquals("audio/mp4", Saver.mime("a.m4a"))
    }
}
