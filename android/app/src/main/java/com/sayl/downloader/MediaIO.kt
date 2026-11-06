package com.sayl.downloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File

/** الوصول إلى ملفات المستخدم: معرفة الاسم والحجم، وتجهيز مسار يقرؤه FFmpeg، ونشر الناتج حيث يختار المستخدم. */
object MediaIO {
    const val OUT_FOLDER = "معالَج"

    data class Meta(val name: String, val size: Long, val mime: String?)

    fun meta(ctx: Context, uri: Uri): Meta {
        if (uri.scheme == "file") return File(uri.path!!).let { Meta(it.name, it.length(), Saver.mime(it.name)) }
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "ملف"
        var size = 0L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return Meta(name, size, runCatching { ctx.contentResolver.getType(uri) }.getOrNull())
    }

    /** مسار حقيقي على القرص إن أمكن قراءته مباشرة (ملفات الوسائط وملفات سَيْل) */
    fun directPath(ctx: Context, uri: Uri): File? {
        if (uri.scheme == "file") return File(uri.path!!)
        val path = runCatching {
            when {
                uri.authority == "media" -> ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                uri.authority == "com.android.externalstorage.documents" -> {
                    val id = DocumentsContract.getDocumentId(uri)
                    if (id.startsWith("primary:")) @Suppress("DEPRECATION") File(Environment.getExternalStorageDirectory(), id.removePrefix("primary:")).path else null
                }
                uri.authority == "${ctx.packageName}.fileprovider" -> {
                    // file_paths.xml يربط «downloads» بمجلد التنزيلات العام (أندرويد 9 فما دون)
                    val seg = uri.pathSegments
                    @Suppress("DEPRECATION")
                    if (seg.firstOrNull() == "downloads") File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), seg.drop(1).joinToString("/")).path else null
                }
                else -> null
            }
        }.getOrNull() ?: return null
        val f = File(path)
        return if (f.isFile && f.canRead()) f else null
    }

    /**
     * ملف يقرؤه FFmpeg: المسار نفسه إن أمكن، وإلا نسخة مؤقتة في مجلد العمل (تُحذف بعد الانتهاء).
     * الأصل لا يُلمس أبداً.
     */
    fun localInput(ctx: Context, ref: MediaRef, work: File, index: Int, canceled: () -> Boolean, onCopy: (Float) -> Unit): File {
        val uri = Uri.parse(ref.uri)
        directPath(ctx, uri)?.let { return it }
        val ext = ref.name.substringAfterLast('.', "bin").take(8)
        val dest = File(work, "in$index.$ext")
        val total = ref.size.takeIf { it > 0 } ?: meta(ctx, uri).size
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("تعذّر فتح الملف؛ ربما حُذف أو نُقل")
        input.use { inp ->
            dest.outputStream().use { out ->
                val buf = ByteArray(512 * 1024)
                var done = 0L
                var last = 0L
                while (true) {
                    if (canceled()) throw FFmpeg.Canceled()
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0 && done - last > 4 * 1024 * 1024) { last = done; onCopy((done * 100f / total).coerceAtMost(100f)) }
                }
            }
        }
        return dest
    }

    // ---------- مكان حفظ الناتج ----------
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("sayl", Context.MODE_PRIVATE)

    /** مجلد اختاره المستخدم (SAF) أو null للافتراضي Download/سيل/معالَج */
    fun outTree(ctx: Context): Uri? {
        val s = prefs(ctx).getString("outTree", null) ?: return null
        val u = Uri.parse(s)
        val held = ctx.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission }
        return if (held) u else null
    }

    fun setOutTree(ctx: Context, uri: Uri?) = prefs(ctx).edit().apply { if (uri == null) remove("outTree") else putString("outTree", uri.toString()) }.apply()

    /** اسم المجلد الظاهر للمستخدم */
    fun outLabel(ctx: Context): String {
        val t = outTree(ctx) ?: return "Download/${Saver.ROOT}/$OUT_FOLDER"
        return runCatching { DocumentsContract.getTreeDocumentId(t).substringAfter(':').ifEmpty { "الذاكرة الداخلية" } }.getOrDefault("مجلد مختار")
    }

    /** ينقل الناتج إلى مكانه النهائي باسم لا يستبدل أي ملف موجود */
    fun publish(ctx: Context, file: File): MediaRef {
        val mime = Saver.mime(file.name)
        val tree = outTree(ctx)
        if (tree != null) {
            val dir = DocumentFile.fromTreeUri(ctx, tree) ?: throw IllegalStateException("المجلد المختار لم يعد متاحاً؛ اختر مجلداً آخر من الإعدادات")
            var name = file.name
            var n = 1
            while (dir.findFile(name) != null) { name = "${file.nameWithoutExtension} ($n).${file.extension}"; n++ }
            // octet-stream حتى لا يضيف المزوّد امتداداً ثانياً للاسم
            val doc = dir.createFile("application/octet-stream", name) ?: throw IllegalStateException("تعذّر الكتابة في المجلد المختار")
            try {
                ctx.contentResolver.openOutputStream(doc.uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
            } catch (e: Exception) {
                doc.delete()
                throw e
            }
            val size = file.length()
            file.delete()
            return MediaRef(doc.uri.toString(), doc.name ?: name, size, "${outLabel(ctx)}/${doc.name ?: name}", mime)
        }
        val size = file.length()
        val saved = Saver.publish(ctx, file, OUT_FOLDER)
        return MediaRef(saved.uri, saved.path.substringAfterLast('/'), size, saved.path, mime)
    }

    /** حذف ناتج لم يكتمل نشره أو لم يعد مطلوباً */
    fun delete(ctx: Context, uri: String) = runCatching {
        val u = Uri.parse(uri)
        if (DocumentsContract.isDocumentUri(ctx, u)) DocumentsContract.deleteDocument(ctx.contentResolver, u) else ctx.contentResolver.delete(u, null, null) > 0
    }.getOrDefault(false)

    /** يُعلم معرض الهاتف بملف جديد (أندرويد 9 فما دون؛ الأحدث يفهرسه MediaStore وحده) */
    fun scan(ctx: Context, path: String) {
        if (Build.VERSION.SDK_INT < 29) android.media.MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null)
    }

    @Suppress("unused")
    private fun values(name: String) = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
}
