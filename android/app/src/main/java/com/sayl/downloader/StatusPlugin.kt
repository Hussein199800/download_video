package com.sayl.downloader

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.result.ActivityResult
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import java.io.File

/** مجلدات حالات واتساب (wa) وواتساب أعمال (w4b). */
object StatusPaths {
    private val MEDIA = Regex("""\.(jpe?g|png|webp|gif|mp4|3gp|mkv)$""", RegexOption.IGNORE_CASE)

    fun relative(app: String, sdk: Int): String {
        val biz = app == "w4b"
        val inner = if (biz) "WhatsApp Business/Media/.Statuses" else "WhatsApp/Media/.Statuses"
        // من أندرويد 11 نقل واتساب ملفاته إلى Android/media
        return if (sdk >= 30) "Android/media/${if (biz) "com.whatsapp.w4b" else "com.whatsapp"}/$inner" else inner
    }

    fun documentId(app: String, sdk: Int) = "primary:" + relative(app, sdk)

    fun isStatusesTree(treeDocId: String, app: String) = treeDocId == documentId(app, 30)

    fun isMedia(name: String) = !name.startsWith(".") && MEDIA.containsMatchIn(name)

    fun isVideo(name: String) = name.substringAfterLast('.').lowercase() in setOf("mp4", "3gp", "mkv")
}

/** قراءة حالات واتساب وحفظها: إذن المجلد على أندرويد 11+، وصلاحية القراءة على 8–10. */
@CapacitorPlugin(name = "Statuses", permissions = [Permission(alias = "storage", strings = [Manifest.permission.READ_EXTERNAL_STORAGE])])
class StatusPlugin : Plugin() {
    private val modern get() = Build.VERSION.SDK_INT >= 30
    private fun prefs() = context.getSharedPreferences("sayl.statuses", Context.MODE_PRIVATE)

    private fun treeUri(app: String): Uri? = context.contentResolver.persistedUriPermissions
        .firstOrNull { it.isReadPermission && runCatching { StatusPaths.isStatusesTree(DocumentsContract.getTreeDocumentId(it.uri), app) }.getOrDefault(false) }?.uri

    private fun legacyDir(app: String) =
        @Suppress("DEPRECATION") File(Environment.getExternalStorageDirectory(), StatusPaths.relative(app, Build.VERSION.SDK_INT))

    private fun installed(app: String) = runCatching {
        context.packageManager.getPackageInfo(if (app == "w4b") "com.whatsapp.w4b" else "com.whatsapp", 0); true
    }.getOrDefault(false)

    private fun granted(app: String) =
        if (modern) treeUri(app) != null else getPermissionState("storage") == PermissionState.GRANTED

    @PluginMethod
    fun access(call: PluginCall) {
        val app = call.getString("app", "wa")!!
        call.resolve(JSObject().put("granted", granted(app)).put("installed", installed(app)))
    }

    @PluginMethod
    fun request(call: PluginCall) {
        val app = call.getString("app", "wa")!!
        if (!modern) return requestPermissionForAlias("storage", call, "storageResult")
        val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", StatusPaths.documentId(app, Build.VERSION.SDK_INT))
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(call, i, "treePicked")
    }

    @PermissionCallback
    private fun storageResult(call: PluginCall) {
        call.resolve(JSObject().put("granted", getPermissionState("storage") == PermissionState.GRANTED))
    }

    @ActivityCallback
    private fun treePicked(call: PluginCall, result: ActivityResult) {
        val app = call.getString("app", "wa")!!
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return call.reject("لم يُمنح الإذن")
        if (!StatusPaths.isStatusesTree(DocumentsContract.getTreeDocumentId(uri), app)) return call.reject("اخترت مجلداً آخر؛ اضغط «استخدام هذا المجلد» في مجلد .Statuses نفسه")
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        call.resolve(JSObject().put("granted", true))
    }

    /** عنصر واحد: الاسم، والنوع، والحجم، والوقت، وصورة مصغرة مولّدة في ذاكرة التطبيق المؤقتة */
    private data class Item(val name: String, val size: Long, val time: Long, val uri: Uri?, val file: File?)

    private fun items(app: String): List<Item> = if (modern) {
        val tree = treeUri(app) ?: return emptyList()
        DocumentFile.fromTreeUri(context, tree)?.listFiles().orEmpty()
            .filter { it.isFile && StatusPaths.isMedia(it.name ?: "") }
            .map { Item(it.name!!, it.length(), it.lastModified(), it.uri, null) }
    } else {
        legacyDir(app).listFiles().orEmpty().filter { it.isFile && StatusPaths.isMedia(it.name) }
            .map { Item(it.name, it.length(), it.lastModified(), null, it) }
    }.sortedByDescending { it.time }

    private fun thumb(it: Item): Pair<File?, Long> {
        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        val out = File(dir, it.name.substringBeforeLast('.') + ".jpg")
        var duration = 0L
        val video = StatusPaths.isVideo(it.name)
        if (video || !out.exists()) runCatching {
            val bmp: Bitmap? = if (video) {
                MediaMetadataRetriever().run {
                    try {
                        if (it.uri != null) setDataSource(context, it.uri) else setDataSource(it.file!!.path)
                        duration = (extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) / 1000
                        if (out.exists()) null else getFrameAtTime(0)
                    } finally { release() }
                }
            } else {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                (it.uri?.let { u -> context.contentResolver.openInputStream(u) } ?: it.file!!.inputStream()).use { s -> BitmapFactory.decodeStream(s, null, opts) }
            }
            bmp?.let { b ->
                val scale = 360f / maxOf(b.width, b.height)
                val small = if (scale < 1) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
                out.outputStream().use { o -> small.compress(Bitmap.CompressFormat.JPEG, 80, o) }
            }
        }
        return (if (out.exists()) out else null) to duration
    }

    @PluginMethod
    fun list(call: PluginCall) {
        val app = call.getString("app", "wa")!!
        Thread {
            try {
                val saved = prefs().getStringSet("saved", emptySet())!!
                val arr = JSArray()
                items(app).forEach {
                    val (t, dur) = thumb(it)
                    arr.put(JSObject().put("name", it.name).put("video", StatusPaths.isVideo(it.name)).put("size", it.size)
                        .put("time", it.time).put("duration", dur).put("thumb", t?.path).put("saved", it.name in saved))
                }
                call.resolve(JSObject().put("items", arr))
            } catch (e: Exception) {
                call.reject(e.message ?: "تعذّر قراءة الحالات")
            }
        }.start()
    }

    /** نسخة مؤقتة من الحالة (للحفظ أو الفتح) */
    private fun copyToCache(app: String, name: String, sub: String): File {
        val it = items(app).firstOrNull { i -> i.name == name } ?: throw IllegalStateException("الحالة لم تعد موجودة")
        val dest = File(File(context.cacheDir, sub).apply { mkdirs() }, name)
        (it.uri?.let { u -> context.contentResolver.openInputStream(u) } ?: it.file!!.inputStream()).use { input -> dest.outputStream().use { o -> input.copyTo(o) } }
        return dest
    }

    @PluginMethod
    fun save(call: PluginCall) {
        val app = call.getString("app", "wa")!!
        val names = call.getArray("names")?.toList<String>() ?: return call.reject("names مطلوب")
        Thread {
            try {
                val saved = prefs().getStringSet("saved", emptySet())!!.toMutableSet()
                names.forEach { n ->
                    Saver.publish(context, copyToCache(app, n, "status-save"), "حالات واتساب")
                    saved += n
                }
                // الحالات تختفي بعد 24 ساعة؛ لا نُبقي إلا أسماء الموجود منها
                val present = (items("wa") + items("w4b")).map { it.name }.toSet()
                prefs().edit().putStringSet("saved", saved.filter { it in present || it in names }.toSet()).apply()
                call.resolve(JSObject().put("saved", names.size))
            } catch (e: Exception) {
                call.reject(e.message ?: "تعذّر الحفظ")
            }
        }.start()
    }

    @PluginMethod
    fun open(call: PluginCall) {
        val app = call.getString("app", "wa")!!
        val name = call.getString("name") ?: return call.reject("name مطلوب")
        try {
            val f = copyToCache(app, name, "updates/status")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (StatusPaths.isVideo(name)) "video/*" else "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(i)
            call.resolve()
        } catch (e: Exception) {
            call.reject(e.message ?: "تعذّر فتح الحالة")
        }
    }
}
