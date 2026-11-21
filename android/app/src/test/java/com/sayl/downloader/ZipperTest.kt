package com.sayl.downloader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class ZipperTest {
    @Test
    fun uniqueNames() {
        assertEquals(listOf("001.jpg", "001 (1).JPG", "a_b.mp3", "001 (2).jpg", "ملف"), Zipper.uniqueNames(listOf("001.jpg", "001.JPG", "a/b.mp3", "001.jpg", " ")))
    }

    @Test
    fun writesReadableZipAndSkipsMissing() {
        val a = ByteArray(5000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val n = Zipper.write(out, listOf("001.jpg" to { ByteArrayInputStream(a) }, "gone.jpg" to { null }, "صوت المنشور.mp3" to { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }))
        assertEquals(2, n)
        val z = ZipInputStream(ByteArrayInputStream(out.toByteArray()))
        val e1 = z.nextEntry!!
        assertEquals("001.jpg", e1.name)
        assertArrayEquals(a, z.readBytes())
        assertEquals("صوت المنشور.mp3", z.nextEntry!!.name)
        assertArrayEquals(byteArrayOf(1, 2, 3), z.readBytes())
    }
}
