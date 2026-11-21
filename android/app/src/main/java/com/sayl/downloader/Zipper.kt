package com.sayl.downloader

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** ملف ZIP واحد لمشاركة ألبوم: الصور والفيديو مضغوطة أصلاً فتُخزَّن دون ضغط (أسرع ولا تكبر). */
object Zipper {
    /** أسماء بلا تكرار داخل الملف: «001.jpg» ثم «001 (1).jpg» */
    fun uniqueNames(names: List<String>): List<String> {
        val used = HashSet<String>()
        return names.map { raw ->
            val n = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "ملف" }
            var name = n
            var i = 1
            while (!used.add(name.lowercase())) {
                val dot = n.lastIndexOf('.')
                name = if (dot > 0) "${n.substring(0, dot)} ($i)${n.substring(dot)}" else "$n ($i)"
                i++
            }
            name
        }
    }

    fun write(out: OutputStream, entries: List<Pair<String, () -> InputStream?>>, canceled: () -> Boolean = { false }): Int {
        var n = 0
        ZipOutputStream(out.buffered()).use { z ->
            z.setLevel(Deflater.NO_COMPRESSION)
            val names = uniqueNames(entries.map { it.first })
            entries.forEachIndexed { i, (_, open) ->
                if (canceled()) throw IllegalStateException("أُلغي")
                val input = open() ?: return@forEachIndexed
                z.putNextEntry(ZipEntry(names[i]))
                input.use { it.copyTo(z, 256 * 1024) }
                z.closeEntry()
                n++
            }
        }
        return n
    }
}
