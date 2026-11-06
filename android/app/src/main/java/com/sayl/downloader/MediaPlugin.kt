package com.sayl.downloader

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import org.json.JSONObject

/** الجسر لأدوات الوسائط (التحويل والضغط والقص والدمج واللقطة) ولمكتبة الوسائط. */
@CapacitorPlugin(
    name = "Media",
    permissions = [
        Permission(alias = "media", strings = [Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES]),
        Permission(alias = "storage", strings = [Manifest.permission.READ_EXTERNAL_STORAGE]),
    ],
)
class MediaPlugin : Plugin() {
    private val tasks get() = Bus.tasks(context)
    private var observer: ContentObserver? = null
    private var senderLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var senderDone: ((Boolean) -> Unit)? = null

    override fun load() {
        Bus.taskListener = { t -> notifyListeners("task", JSObject.fromJSONObject(t.toJson())) }
        // طلبات أندرويد للحذف أو التعديل على ملفات تطبيقات أخرى (نافذة تأكيد من النظام)
        senderLauncher = activity.activityResultRegistry.register("sayl-media-sender", ActivityResultContracts.StartIntentSenderForResult()) { r: ActivityResult ->
            senderDone?.invoke(r.resultCode == Activity.RESULT_OK)
            senderDone = null
        }
        // تحديث المكتبة تلقائياً عند إضافة ملفات أو حذفها
        val handler = Handler(Looper.getMainLooper())
        val fire = Runnable { notifyListeners("libraryChanged", JSObject()) }
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { handler.removeCallbacks(fire); handler.postDelayed(fire, 1500) }
        }.also { context.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, it) }
    }

    override fun handleOnDestroy() {
        Bus.taskListener = null
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        senderLauncher?.unregister()
    }

    private fun bg(call: PluginCall, block: () -> Unit) = Thread {
        try { block() } catch (e: Exception) { call.reject((e.message ?: e.toString()).take(2000)) }
    }.start()

    private fun ref(o: JSONObject) = MediaRef.fromJson(o)

    // ---------- اختيار الملفات ----------
    @PluginMethod
    fun pick(call: PluginCall) {
        val types = call.getArray("types")?.toList<String>()?.takeIf { it.isNotEmpty() } ?: listOf("video/*", "audio/*")
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if (types.size == 1) types[0] else "*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, call.getBoolean("multiple", false) == true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(call, i, "picked")
    }

    @ActivityCallback
    private fun picked(call: PluginCall, result: ActivityResult) {
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) return call.reject("لم يُختر ملف")
        val uris = buildList {
            data.clipData?.let { c -> for (i in 0 until c.itemCount) add(c.getItemAt(i).uri) }
            if (isEmpty()) data.data?.let { add(it) }
        }
        val out = JSArray()
        uris.forEach { u ->
            runCatching { context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val m = MediaIO.meta(context, u)
            val kind = Library.kindOf(m.mime, m.name) ?: "video"
            out.put(JSObject().put("uri", u.toString()).put("name", m.name).put("size", m.size).put("mime", m.mime).put("kind", kind))
        }
        call.resolve(JSObject().put("files", out))
    }

    // ---------- الخصائص والتقدير ----------
    private fun probeOf(uri: Uri, size: Long): MediaArgs.Probe {
        // ffprobe أدق؛ يحتاج مساراً مباشراً، وإلا MediaExtractor يقرأ الرابط نفسه دون نسخ الملف
        return MediaIO.directPath(context, uri)?.let { runCatching { FFmpeg.probe(context, it.path) }.getOrNull() } ?: Library.extract(context, uri, size)
    }

    @PluginMethod
    fun probe(call: PluginCall) {
        val uri = Uri.parse(call.getString("uri") ?: return call.reject("uri مطلوب"))
        bg(call) {
            val size = call.getLong("size")?.takeIf { it > 0 } ?: MediaIO.meta(context, uri).size
            val p = probeOf(uri, size)
            call.resolve(JSObject.fromJSONObject(p.toJson().put("size", if (p.size > 0) p.size else size)))
        }
    }

    /** تقدير الضغط: معدل البت المستهدف والحجم المتوقع ونسبة التوفير */
    @PluginMethod
    fun plan(call: PluginCall) {
        val uri = Uri.parse(call.getString("uri") ?: return call.reject("uri مطلوب"))
        bg(call) {
            val size = call.getLong("size")?.takeIf { it > 0 } ?: MediaIO.meta(context, uri).size
            val p = probeOf(uri, size).let { if (it.size <= 0) it.copy(size = size) else it }
            val level = runCatching { MediaArgs.Level.valueOf((call.getString("level") ?: "smart").uppercase()) }.getOrDefault(MediaArgs.Level.SMART)
            val codec = call.getString("codec") ?: "auto"
            val hevc = when (codec) { "h265" -> true; "h264" -> false; else -> Hw.hasHevc() }
            val plan = MediaArgs.compressPlan(p, level, hevc)
            call.resolve(JSObject().put("videoBps", plan.videoBps).put("audioBps", plan.audioBps).put("estimate", plan.estimate)
                .put("worth", plan.worth).put("hevc", hevc && Hw.hasHevc()).put("hw", Hw.hasAvc()).put("size", size).put("probe", JSObject.fromJSONObject(p.toJson())))
        }
    }

    @PluginMethod
    fun caps(call: PluginCall) {
        bg(call) { call.resolve(JSObject().put("hevc", Hw.hasHevc()).put("hw", Hw.hasAvc())) }
    }

    // ---------- طابور المعالجة ----------
    @PluginMethod
    fun enqueue(call: PluginCall) {
        val arr = call.getArray("tasks") ?: return call.reject("tasks مطلوب")
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ins = o.getJSONArray("inputs")
            MediaTask(
                id = o.getString("id"), op = o.getString("op"), title = o.optString("title"),
                inputs = (0 until ins.length()).map { ref(ins.getJSONObject(it)) },
                params = o.optJSONObject("params") ?: JSONObject(), batch = o.optString("batch").ifEmpty { null },
            )
        }
        if (list.any { it.inputs.isEmpty() }) return call.reject("لم يُختر ملف")
        tasks.add(list)
        list.forEach { Bus.emitTask(it) }
        MediaWorker.kick(context)
        call.resolve(JSObject().put("added", list.size))
    }

    @PluginMethod
    fun tasks(call: PluginCall) {
        val out = JSArray()
        tasks.all().forEach { out.put(it.toJson()) }
        call.resolve(JSObject().put("tasks", out))
    }

    @PluginMethod
    fun cancelTask(call: PluginCall) {
        MediaWorker.cancel(context, call.getString("id") ?: return call.reject("id مطلوب"))
        call.resolve()
    }

    @PluginMethod
    fun retryTask(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id مطلوب")
        tasks.update(id) { if (!it.active) it.copy(state = "queued", pct = 0f, error = null, stage = "", outputs = emptyList(), createdAt = System.currentTimeMillis()) else it }?.let { Bus.emitTask(it) }
        MediaWorker.kick(context)
        call.resolve()
    }

    @PluginMethod
    fun removeTask(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id مطلوب")
        val t = tasks.get(id) ?: return call.resolve()
        if (t.active) MediaWorker.cancel(context, id)
        if (call.getBoolean("deleteOutputs", false) == true) t.outputs.forEach { MediaIO.delete(context, it.uri) }
        tasks.remove(id)
        call.resolve()
    }

    @PluginMethod
    fun clearTasks(call: PluginCall) {
        tasks.clearFinished()
        call.resolve()
    }

    // ---------- الفتح والمشاركة ----------
    private fun mimeOf(uri: Uri, fallbackName: String?) =
        runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: Saver.mime(fallbackName ?: uri.lastPathSegment ?: "")

    @PluginMethod
    fun open(call: PluginCall) {
        val uri = Uri.parse(call.getString("uri") ?: return call.reject("uri مطلوب"))
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, call.getString("mime") ?: mimeOf(uri, call.getString("name")))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { activity.startActivity(i); call.resolve() } catch (e: Exception) { call.reject("لا يوجد تطبيق يفتح هذا الملف") }
    }

    @PluginMethod
    fun share(call: PluginCall) {
        val uris = call.getArray("uris")?.toList<String>()?.map { Uri.parse(it) }.orEmpty()
        if (uris.isEmpty()) return call.reject("لم يُختر ملف")
        val mimes = uris.map { mimeOf(it, null) }.distinct()
        val mime = if (mimes.size == 1) mimes[0] else mimes.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { activity.startActivity(Intent.createChooser(send, "مشاركة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); call.resolve() } catch (e: Exception) { call.reject("تعذّرت المشاركة") }
    }

    // ---------- مكان الحفظ ----------
    @PluginMethod
    fun outDir(call: PluginCall) {
        call.resolve(JSObject().put("label", MediaIO.outLabel(context)).put("custom", MediaIO.outTree(context) != null))
    }

    @PluginMethod
    fun chooseOutDir(call: PluginCall) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(call, i, "outPicked")
    }

    @ActivityCallback
    private fun outPicked(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return call.reject("لم يُختر مجلد")
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            return call.reject("لا يسمح هذا المجلد بالكتابة؛ اختر مجلداً آخر")
        }
        MediaIO.setOutTree(context, uri)
        call.resolve(JSObject().put("label", MediaIO.outLabel(context)).put("custom", true))
    }

    @PluginMethod
    fun resetOutDir(call: PluginCall) {
        MediaIO.outTree(context)?.let { runCatching { context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        MediaIO.setOutTree(context, null)
        call.resolve(JSObject().put("label", MediaIO.outLabel(context)).put("custom", false))
    }

    // ---------- المكتبة ----------
    @PluginMethod
    fun library(call: PluginCall) {
        val sources = call.getArray("sources")?.toList<String>()?.toSet() ?: setOf("sayl")
        bg(call) {
            val allowed = sources.filter { it != "device" || deviceGranted() != "denied" }.toSet()
            val out = JSArray()
            Library.list(context, allowed).forEach { out.put(it.toJson()) }
            call.resolve(JSObject().put("items", out))
        }
    }

    @PluginMethod
    fun thumb(call: PluginCall) {
        val uri = Uri.parse(call.getString("uri") ?: return call.reject("uri مطلوب"))
        bg(call) { call.resolve(JSObject.fromJSONObject(Library.thumb(context, uri, call.getString("kind") ?: "video", call.getLong("stamp") ?: 0))) }
    }

    @PluginMethod
    fun duplicates(call: PluginCall) {
        val arr = call.getArray("items") ?: return call.reject("items مطلوب")
        val items = (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("uri") to o.optLong("size") } }
        bg(call) {
            var last = 0L
            val groups = Library.duplicates(context, items) { done, total ->
                val now = System.currentTimeMillis()
                if (now - last > 300) { last = now; notifyListeners("dupProgress", JSObject().put("done", done).put("total", total)) }
            }
            val out = JSArray()
            groups.forEach { out.put(JSArray(it)) }
            call.resolve(JSObject().put("groups", out))
        }
    }

    @PluginMethod
    fun folders(call: PluginCall) {
        val out = JSArray()
        Library.trees(context).forEach { out.put(JSObject().put("uri", it.toString()).put("label", Library.treeLabel(it))) }
        call.resolve(JSObject().put("folders", out))
    }

    @PluginMethod
    fun addFolder(call: PluginCall) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(call, i, "folderPicked")
    }

    @ActivityCallback
    private fun folderPicked(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return call.reject("لم يُختر مجلد")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            .recoverCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Library.addTree(context, uri)
        call.resolve(JSObject().put("uri", uri.toString()).put("label", Library.treeLabel(uri)))
    }

    @PluginMethod
    fun removeFolder(call: PluginCall) {
        Library.removeTree(context, Uri.parse(call.getString("uri") ?: return call.reject("uri مطلوب")))
        call.resolve()
    }

    // ---------- صلاحية كل وسائط الجهاز ----------
    /** granted | partial (أندرويد 14: صور مختارة فقط) | denied */
    private fun deviceGranted(): String {
        fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && has(Manifest.permission.READ_MEDIA_VIDEO) && has(Manifest.permission.READ_MEDIA_AUDIO) -> "granted"
            Build.VERSION.SDK_INT >= 33 && (has(Manifest.permission.READ_MEDIA_VIDEO) || has(Manifest.permission.READ_MEDIA_AUDIO) || has(Manifest.permission.READ_MEDIA_IMAGES)) -> "partial"
            Build.VERSION.SDK_INT >= 34 && has("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") -> "partial"
            Build.VERSION.SDK_INT < 33 && has(Manifest.permission.READ_EXTERNAL_STORAGE) -> "granted"
            else -> "denied"
        }
    }

    @PluginMethod
    fun deviceAccess(call: PluginCall) {
        call.resolve(JSObject().put("state", deviceGranted()))
    }

    @PluginMethod
    fun requestDeviceAccess(call: PluginCall) {
        if (deviceGranted() == "granted") return call.resolve(JSObject().put("state", "granted"))
        requestPermissionForAlias(if (Build.VERSION.SDK_INT >= 33) "media" else "storage", call, "deviceResult")
    }

    @PermissionCallback
    private fun deviceResult(call: PluginCall) {
        val state = deviceGranted()
        val alias = if (Build.VERSION.SDK_INT >= 33) "media" else "storage"
        // رفضٌ نهائي: أندرويد لا يعرض النافذة مجدداً، فنرشد إلى الإعدادات
        val blocked = state == "denied" && getPermissionState(alias) == PermissionState.DENIED && !activity.shouldShowRequestPermissionRationale(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE)
        call.resolve(JSObject().put("state", state).put("blocked", blocked))
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        call.resolve()
    }

    // ---------- الحذف وإعادة التسمية ----------
    private fun launchSender(sender: IntentSender, done: (Boolean) -> Unit) {
        activity.runOnUiThread {
            senderDone = done
            senderLauncher?.launch(IntentSenderRequest.Builder(sender).build()) ?: done(false)
        }
    }

    private fun isMedia(u: Uri) = u.authority == "media"

    @PluginMethod
    fun deleteItems(call: PluginCall) {
        val uris = call.getArray("uris")?.toList<String>()?.map { Uri.parse(it) }.orEmpty()
        if (uris.isEmpty()) return call.reject("لم يُختر ملف")
        bg(call) {
            var deleted = 0
            val needConsent = ArrayList<Uri>()
            for (u in uris) {
                try {
                    val ok = if (DocumentsContract.isDocumentUri(context, u)) DocumentsContract.deleteDocument(context.contentResolver, u)
                    else context.contentResolver.delete(u, null, null) > 0
                    if (ok) deleted++
                } catch (e: SecurityException) {
                    if (isMedia(u) && Build.VERSION.SDK_INT >= 29) needConsent += u else throw IllegalStateException("لا توجد صلاحية لحذف ${u.lastPathSegment}")
                }
            }
            if (needConsent.isEmpty()) return@bg call.resolve(JSObject().put("deleted", deleted))
            // ملفات تطبيقات أخرى: أندرويد 11+ يطلب موافقة واحدة للجميع
            if (Build.VERSION.SDK_INT >= 30) {
                launchSender(MediaStore.createDeleteRequest(context.contentResolver, needConsent).intentSender) { ok ->
                    call.resolve(JSObject().put("deleted", deleted + if (ok) needConsent.size else 0).put("declined", !ok))
                }
                return@bg
            }
            // أندرويد 10: موافقة لكل ملف
            fun next(i: Int) {
                if (i >= needConsent.size) { call.resolve(JSObject().put("deleted", deleted)); return }
                val u = needConsent[i]
                try {
                    if (context.contentResolver.delete(u, null, null) > 0) deleted++
                    next(i + 1)
                } catch (e: RecoverableSecurityException) {
                    launchSender(e.userAction.actionIntent.intentSender) { ok ->
                        Thread { if (ok) runCatching { if (context.contentResolver.delete(u, null, null) > 0) deleted++ }; next(i + 1) }.start()
                    }
                }
            }
            next(0)
        }
    }

    @PluginMethod
    fun renameItem(call: PluginCall) {
        val u = Uri.parse(call.getString("uri") ?: return call.reject("uri مطلوب"))
        val raw = (call.getString("name") ?: return call.reject("الاسم مطلوب")).trim()
        val name = raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").trim()
        if (name.isEmpty() || name.startsWith(".")) return call.reject("اسم غير صالح")
        bg(call) {
            if (DocumentsContract.isDocumentUri(context, u)) {
                val n = DocumentsContract.renameDocument(context.contentResolver, u, name) ?: throw IllegalStateException("تعذّرت إعادة التسمية")
                return@bg call.resolve(JSObject().put("uri", n.toString()).put("name", name))
            }
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
            val apply = { context.contentResolver.update(u, values, null, null) > 0 }
            try {
                if (!apply()) throw IllegalStateException("تعذّرت إعادة التسمية؛ ربما يوجد ملف بالاسم نفسه")
                call.resolve(JSObject().put("uri", u.toString()).put("name", name))
            } catch (e: SecurityException) {
                val sender = when {
                    Build.VERSION.SDK_INT >= 30 -> MediaStore.createWriteRequest(context.contentResolver, listOf(u)).intentSender
                    Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException -> e.userAction.actionIntent.intentSender
                    else -> throw IllegalStateException("لا توجد صلاحية لإعادة تسمية هذا الملف")
                }
                launchSender(sender) { ok ->
                    if (!ok) return@launchSender call.reject("لم تُمنح الموافقة")
                    Thread {
                        try { if (apply()) call.resolve(JSObject().put("uri", u.toString()).put("name", name)) else call.reject("تعذّرت إعادة التسمية") }
                        catch (e2: Exception) { call.reject(e2.message ?: "تعذّرت إعادة التسمية") }
                    }.start()
                }
            }
        }
    }
}
