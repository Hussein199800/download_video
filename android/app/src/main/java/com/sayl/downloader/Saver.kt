package com.sayl.downloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** ينقل الملف المنزّل إلى Download/سيل/[القائمة]/ ويعيد رابط محتواه ومساره الظاهر للمستخدم. */
object Saver {
    const val ROOT = "سيل"

    /** اسم مجلد آمن من عنوان قائمة التشغيل */
    fun safeFolder(name: String?): String? {
        val s = name?.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(80)?.trim('.', ' ')
        return if (s.isNullOrEmpty()) null else s
    }

    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    data class Saved(val uri: String, val path: String)

    fun publish(ctx: Context, file: File, folder: String?): Saved {
        val sub = listOfNotNull(ROOT, safeFolder(folder)).joinToString("/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime(file.name))
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$sub")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("تعذّر الحفظ في التنزيلات")
            try {
                cr.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
                cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                cr.delete(uri, null, null)
                throw e
            }
            file.delete()
            return Saved(uri.toString(), "${Environment.DIRECTORY_DOWNLOADS}/$sub/${file.name}")
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub).apply { mkdirs() }
        var dest = File(dir, file.name)
        var n = 1
        while (dest.exists()) dest = File(dir, "${file.nameWithoutExtension} ($n).${file.extension}").also { n++ }
        if (!file.renameTo(dest)) {
            file.copyTo(dest)
            file.delete()
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", dest)
        return Saved(uri.toString(), "${Environment.DIRECTORY_DOWNLOADS}/$sub/${dest.name}")
    }

    fun delete(ctx: Context, uri: String?): Boolean = runCatching {
        if (uri == null) return false
        ctx.contentResolver.delete(Uri.parse(uri), null, null) > 0
    }.getOrDefault(false)
}
