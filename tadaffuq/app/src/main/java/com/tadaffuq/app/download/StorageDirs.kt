package com.tadaffuq.app.download

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.tadaffuq.engine.FileCategory
import java.io.File

/**
 * الملفات في «التنزيلات/تدفق/<الفئة>». الكتابة بمسار الملف مباشرة مدعومة لملفات التطبيق
 * في مجلد التنزيلات العام من Android 11، وعلى 10 بـ requestLegacyExternalStorage، وعلى 9 بصلاحية الكتابة.
 * أثناء التنزيل يحمل الملف اللاحقة ‎.tdq ثم يُعاد تسميته عند الاكتمال.
 */
object StorageDirs {
    const val ROOT = "تدفق"
    const val PART_SUFFIX = ".tdq"

    @Suppress("DEPRECATION")
    fun root(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ROOT)

    fun trash(): File = File(root(), ".سلة")

    fun folderName(c: FileCategory) = when (c) {
        FileCategory.COMPRESSED -> "مضغوطة"
        FileCategory.DOCUMENTS -> "مستندات"
        FileCategory.PROGRAMS -> "برامج"
        FileCategory.DISK_IMAGES -> "صور أقراص"
        FileCategory.MODELS -> "نماذج"
        FileCategory.OTHER -> "أخرى"
    }

    fun dirFor(category: FileCategory, byCategory: Boolean): File =
        if (byCategory) File(root(), folderName(category)) else root()

    fun partFile(dir: String, name: String) = File(dir, name + PART_SUFFIX)

    fun mime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

    fun scan(context: Context, file: File) {
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime(file.name)), null)
    }

    fun openIntent(context: Context, file: File): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri(context, file), mime(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shareIntent(context: Context, files: List<File>): Intent {
        val uris = ArrayList(files.map { uri(context, it) })
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mime(files[0].name))
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
        return Intent.createChooser(send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
