package com.sayl.downloader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin

/** الجسر بين الواجهة والطبقة الأصلية: المعلومات، والطابور، والمحرّك، والمشاركة. */
@CapacitorPlugin(name = "Sayl")
class SaylPlugin : Plugin() {
    private val store get() = Bus.store(context)

    override fun load() {
        Bus.listener = { event, job -> notifyListeners(event, js(job)) }
    }

    override fun handleOnDestroy() {
        Bus.listener = null
    }

    private fun js(j: Job) = JSObject.fromJSONObject(j.toJson())

    private fun bg(call: PluginCall, block: () -> Unit) = Thread {
        try {
            block()
        } catch (e: Exception) {
            call.reject((e.message ?: e.toString()).take(4000))
        }
    }.start()

    @PluginMethod
    fun info(call: PluginCall) {
        val url = call.getString("url") ?: return call.reject("url مطلوب")
        bg(call) {
            val json = Engine.info(context, url, Bus.cookies(context).takeIf { it.exists() })
            call.resolve(JSObject().put("json", json))
        }
    }

    @PluginMethod
    fun enqueue(call: PluginCall) {
        val arr = call.getArray("jobs") ?: return call.reject("jobs مطلوب")
        val jobs = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val a = o.getJSONArray("args")
            val args = List(a.length()) { a.getString(it) }
            ArgGuard.check(args)?.let { return call.reject("خيار غير مسموح: $it") }
            Job(
                id = o.getString("id"), url = o.getString("url"), title = o.optString("title"), kind = o.optString("kind", "video"),
                args = args, thumb = o.optString("thumb").ifEmpty { null }, site = o.optString("site", "other"),
                folder = o.optString("folder").ifEmpty { null },
            )
        }
        val added = store.add(jobs)
        if (added.isNotEmpty()) DownloadWorker.kick(context)
        call.resolve(JSObject().put("added", added.size).put("skipped", jobs.size - added.size))
    }

    @PluginMethod
    fun list(call: PluginCall) {
        val out = JSArray()
        store.all().forEach { out.put(it.toJson()) }
        call.resolve(JSObject().put("jobs", out))
    }

    private fun act(call: PluginCall, action: String) {
        val id = call.getString("id") ?: return call.reject("id مطلوب")
        Actions.apply(context, action, id)
        call.resolve()
    }

    @PluginMethod
    fun cancel(call: PluginCall) = act(call, ActionReceiver.CANCEL)

    @PluginMethod
    fun pause(call: PluginCall) = act(call, ActionReceiver.PAUSE)

    @PluginMethod
    fun resume(call: PluginCall) = act(call, ActionReceiver.RESUME)

    @PluginMethod
    fun move(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id مطلوب")
        store.move(id, call.getInt("delta", 0) ?: 0)
        call.resolve()
    }

    @PluginMethod
    fun toTop(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id مطلوب")
        store.toTop(id)
        call.resolve()
    }

    @PluginMethod
    fun retry(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id مطلوب")
        val j = store.update(id) { if (!it.active) it.copy(state = "queued", attempts = 0, error = null, retryAt = 0, pct = 0f, downloaded = 0) else it }
        j?.let { Bus.emit("state", it) }
        DownloadWorker.kick(context)
        call.resolve()
    }

    @PluginMethod
    fun remove(call: PluginCall) {
        val id = call.getString("id") ?: return call.reject("id مطلوب")
        val j = store.get(id) ?: return call.resolve()
        if (j.active) Engine.cancel(id)
        if (call.getBoolean("deleteFile", false) == true) Saver.delete(context, j.uri)
        store.remove(id)
        DownloadWorker.workDir(context, id).deleteRecursively()
        call.resolve()
    }

    @PluginMethod
    fun clearFinished(call: PluginCall) {
        store.clearFinished()
        call.resolve()
    }

    private fun view(call: PluginCall, share: Boolean) {
        val j = call.getString("id")?.let { store.get(it) } ?: return call.reject("الملف غير موجود")
        val uri = Uri.parse(j.uri ?: return call.reject("الملف غير موجود"))
        val mime = Saver.mime(j.path ?: "")
        val i = if (share) Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri), j.title)
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(i)
            call.resolve()
        } catch (e: Exception) {
            call.reject("لا يوجد تطبيق يفتح هذا الملف")
        }
    }

    @PluginMethod
    fun open(call: PluginCall) = view(call, false)

    @PluginMethod
    fun share(call: PluginCall) = view(call, true)

    @PluginMethod
    fun engineVersion(call: PluginCall) {
        bg(call) { call.resolve(JSObject().put("version", Engine.version(context))) }
    }

    @PluginMethod
    fun engineUpdate(call: PluginCall) {
        bg(call) {
            val status = Engine.update(context)
            call.resolve(JSObject().put("status", status).put("version", Engine.version(context)))
        }
    }

    @PluginMethod
    fun setConfig(call: PluginCall) {
        call.getInt("parallel")?.let { Bus.setParallel(context, it) }
        call.resolve()
    }

    @PluginMethod
    fun abi(call: PluginCall) {
        call.resolve(JSObject().put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"))
    }

    @PluginMethod
    fun hasCookies(call: PluginCall) {
        call.resolve(JSObject().put("value", Bus.cookies(context).exists()))
    }

    @PluginMethod
    fun importCookies(call: PluginCall) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(call, i, "cookiesPicked")
    }

    @ActivityCallback
    private fun cookiesPicked(call: PluginCall, result: ActivityResult) {
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return call.reject("لم يُختر ملف")
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        // ملف Netscape: سطر العنوان أو سطور مفصولة بسبعة حقول
        val valid = text.contains("# Netscape HTTP Cookie File") || text.lines().any { it.split('\t').size == 7 }
        if (!valid || text.length > 2_000_000) return call.reject("هذا ليس ملف cookies بصيغة Netscape")
        Bus.cookies(context).writeText(text)
        call.resolve()
    }

    @PluginMethod
    fun clearCookies(call: PluginCall) {
        Bus.cookies(context).delete()
        call.resolve()
    }

    /** النص الذي شاركه المستخدم من تطبيق آخر عند فتح التطبيق، مرة واحدة */
    @PluginMethod
    fun takeSharedText(call: PluginCall) {
        val i = activity?.intent
        val text = if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT) else null
        if (text != null) activity.intent = Intent(Intent.ACTION_MAIN)
        call.resolve(JSObject().put("text", text))
    }

    override fun handleOnNewIntent(intent: Intent) {
        super.handleOnNewIntent(intent)
        if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { notifyListeners("shared", JSObject().put("text", it)) }
            activity.intent = Intent(Intent.ACTION_MAIN)
        }
    }

}
