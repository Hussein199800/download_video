package com.sayl.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPathsTest {
    @Test
    fun modernFolders() {
        assertEquals("Android/media/com.whatsapp/WhatsApp/Media/.Statuses", StatusPaths.relative("wa", sdk = 30))
        assertEquals("Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses", StatusPaths.relative("w4b", sdk = 34))
    }

    @Test
    fun legacyFolders() {
        assertEquals("WhatsApp/Media/.Statuses", StatusPaths.relative("wa", sdk = 29))
        assertEquals("WhatsApp Business/Media/.Statuses", StatusPaths.relative("w4b", sdk = 26))
    }

    @Test
    fun initialDocumentId() {
        assertEquals("primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses", StatusPaths.documentId("wa", 33))
    }

    @Test
    fun acceptsOnlyTheStatusesTree() {
        assertTrue(StatusPaths.isStatusesTree("primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses", "wa"))
        assertFalse(StatusPaths.isStatusesTree("primary:Download", "wa"))
        assertFalse(StatusPaths.isStatusesTree("primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses", "w4b"))
    }

    @Test
    fun mediaFiles() {
        assertTrue(StatusPaths.isMedia("abc.jpg"))
        assertTrue(StatusPaths.isMedia("abc.MP4"))
        assertFalse(StatusPaths.isMedia(".nomedia"))
        assertFalse(StatusPaths.isMedia("abc.opus"))
        assertTrue(StatusPaths.isVideo("x.mp4"))
    }
}
