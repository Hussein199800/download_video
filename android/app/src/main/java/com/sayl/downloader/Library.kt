package com.sayl.downloader

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * مكتبة الوسائط: ثلاثة مصادر يختارها المستخدم.
 *  - sayl: ما في Download/سيل (دائماً، بلا صلاحيات).
 *  - folder: مجلدات اختارها المستخدم بنفسه (إذن المجلد فقط).
 *  - device: كل فيديو وصوت وصور الهاتف (بصلاحية الوسائط، باختيار المستخدم وبعد تنبيهه).
 */
object Library {
    data class Item(
        val uri: String, val name: String, val size: Long, val date: Long, val duration: Long,
        val width: Int, val height: Int, val mime: String, val kind: String, val source: String,
        val folder: String, val owned: Boolean, val key: String,
    ) {
        fun toJson(): JSONObject = JSONObject().put("uri", uri).put("name", name).put("size", size).put("date", date)
            .put("duration", duration).put("width", width).put("height", height).put("mime", mime).put("kind", kind)
            .put("source", source).put("folder", folder).put("owned", owned).put("id", key)
    }

    private val VIDEO = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "flv", "wmv")
    private val AUDIO = setOf("mp3", "m4a", "aac", "wav", "flac", "opus", "ogg", "amr", "wma")
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp")

    fun kindOf(mime: String?, name: String): String? {
        val m = mime ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            m.startsWith("video/") || ext in VIDEO -> "video"
            m.startsWith("audio/") || ext in AUDIO -> "audio"
            m.startsWith("image/") || ext in IMAGE -> "image"
            else -> null
        }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("sayl", Context.MODE_PRIVATE)

    // ---------- المجلدات المختارة ----------
    fun trees(ctx: Context): List<Uri> {
        val held = ctx.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        return runCatching { JSONArray(prefs(ctx).getString("libTrees", "[]")) }.getOrDefault(JSONArray()).let { a ->
            (0 until a.length()).map { Uri.parse(a.getString(it)) }.filter { it in held }
        }
    }

    fun addTree(ctx: Context, uri: Uri) {
        val list = (trees(ctx) + uri).distinct()
        prefs(ctx).edit().putString("libTrees", JSONArray(list.map { it.toString() }).toString()).apply()
    }

    fun removeTree(ctx: Context, uri: Uri) {
        prefs(ctx).edit().putString("libTrees", JSONArray(trees(ctx).filter { it != uri }.map { it.toString() }).toString()).apply()
        runCatching { ctx.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }

    fun treeLabel(uri: Uri): String = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifEmpty { "الذاكرة الداخلية" } }.getOrDefault("مجلد")

    // ---------- القراءة ----------
    fun list(ctx: Context, sources: Set<String>): List<Item> {
        val out = LinkedHashMap<String, Item>()
        if ("sayl" in sources) sayl(ctx).forEach { out.putIfAbsent(it.key, it) }
        if ("device" in sources) device(ctx).forEach { out.putIfAbsent(it.key, it) }
        if ("folder" in sources) trees(ctx).forEach { t -> runCatching { folder(ctx, t) }.getOrDefault(emptyList()).forEach { out.putIfAbsent(it.key, it) } }
        return out.values.sortedByDescending { it.date }
    }

    private val COLS = buildList {
        add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DISPLAY_NAME); add(MediaStore.MediaColumns.SIZE)
        add(MediaStore.MediaColumns.DATE_MODIFIED); add(MediaStore.MediaColumns.MIME_TYPE)
        add(MediaStore.MediaColumns.WIDTH); add(MediaStore.MediaColumns.HEIGHT)
        if (Build.VERSION.SDK_INT >= 29) { add(MediaStore.MediaColumns.RELATIVE_PATH); add(MediaStore.MediaColumns.DURATION); add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME) }
        else add(MediaStore.MediaColumns.DATA)
    }.toTypedArray()

    @Suppress("DEPRECATION")
    private val STORAGE_ROOT get() = Environment.getExternalStorageDirectory().path.trimEnd('/') + "/"

    private fun query(ctx: Context, collection: Uri, source: String, selection: String?, args: Array<String>?): List<Item> {
        val list = ArrayList<Item>()
        ctx.contentResolver.query(collection, COLS, selection, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
            val idx = COLS.associateWith { c.getColumnIndex(it) }
            fun str(k: String) = idx[k]?.takeIf { it >= 0 }?.let { c.getString(it) }
            fun long(k: String) = idx[k]?.takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) } ?: 0L
            while (c.moveToNext()) {
                val name = str(MediaStore.MediaColumns.DISPLAY_NAME) ?: continue
                val mime = str(MediaStore.MediaColumns.MIME_TYPE)
                val kind = kindOf(mime, name) ?: continue
                val rel = if (Build.VERSION.SDK_INT >= 29) str(MediaStore.MediaColumns.RELATIVE_PATH) ?: ""
                else (str(MediaStore.MediaColumns.DATA) ?: "").removePrefix(STORAGE_ROOT).substringBeforeLast('/', "") + "/"
                val owner = if (Build.VERSION.SDK_INT >= 29) str(MediaStore.MediaColumns.OWNER_PACKAGE_NAME) else null
                list += Item(
                    uri = ContentUris.withAppendedId(collection, long(MediaStore.MediaColumns._ID)).toString(), name = name,
                    size = long(MediaStore.MediaColumns.SIZE), date = long(MediaStore.MediaColumns.DATE_MODIFIED) * 1000,
                    duration = if (Build.VERSION.SDK_INT >= 29) long(MediaStore.MediaColumns.DURATION) else 0,
                    width = long(MediaStore.MediaColumns.WIDTH).toInt(), height = long(MediaStore.MediaColumns.HEIGHT).toInt(),
                    mime = mime ?: Saver.mime(name), kind = kind, source = source, folder = rel.trimEnd('/'),
                    owned = owner == ctx.packageName || source == "sayl", key = "p:" + (rel + name).lowercase(),
                )
            }
        }
        return list
    }

    /** ما نزّله سَيْل أو عالجه: Download/سيل وكل ما تحته */
    fun sayl(ctx: Context): List<Item> {
        if (Build.VERSION.SDK_INT >= 29) {
            return query(ctx, MediaStore.Downloads.EXTERNAL_CONTENT_URI, "sayl", "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("${Environment.DIRECTORY_DOWNLOADS}/${Saver.ROOT}/%"))
        }
        @Suppress("DEPRECATION")
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Saver.ROOT)
        return root.walkTopDown().maxDepth(4).filter { it.isFile }.mapNotNull { f ->
            val kind = kindOf(null, f.name) ?: return@mapNotNull null
            val rel = f.parentFile!!.path.removePrefix(STORAGE_ROOT)
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            Item(uri.toString(), f.name, f.length(), f.lastModified(), 0, 0, 0, Saver.mime(f.name), kind, "sayl", rel, true, "p:" + "$rel/${f.name}".lowercase())
        }.toList().sortedByDescending { it.date }
    }

    /** كل وسائط الهاتف (يتطلب صلاحية الوسائط) */
    fun device(ctx: Context): List<Item> {
        val vol = if (Build.VERSION.SDK_INT >= 29) MediaStore.VOLUME_EXTERNAL else "external"
        return listOf(MediaStore.Video.Media.getContentUri(vol), MediaStore.Audio.Media.getContentUri(vol), MediaStore.Images.Media.getContentUri(vol))
            .flatMap { runCatching { query(ctx, it, "device", null, null) }.getOrDefault(emptyList()) }
    }

    /** مجلد اختاره المستخدم: بحث في المجلدات الفرعية حتى عمق 6 وبحد 3000 ملف */
    fun folder(ctx: Context, tree: Uri): List<Item> {
        val out = ArrayList<Item>()
        val label = treeLabel(tree)
        val rootRel = DocumentsContract.getTreeDocumentId(tree).let { if (it.startsWith("primary:")) it.removePrefix("primary:") else null }
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS)
        fun walk(docId: String, path: String, depth: Int) {
            if (depth > 6 || out.size >= 3000) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < 3000) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (name.startsWith(".")) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { walk(id, "$path/$name", depth + 1); continue }
                    val kind = kindOf(mime, name) ?: continue
                    val flags = c.getInt(5)
                    // المفتاح نفسه لملفات الذاكرة الداخلية كما في MediaStore حتى لا يظهر الملف مرتين
                    val key = if (id.startsWith("primary:")) "p:" + (id.removePrefix("primary:")).lowercase() else "d:$id"
                    out += Item(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, c.getLong(3), c.getLong(4), 0, 0, 0,
                        mime ?: Saver.mime(name), kind, "folder", path.trimStart('/'), flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0, key)
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), rootRel ?: label, 0)
        return out
    }

    // ---------- الصور المصغرة والمدة ----------
    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** صورة مصغرة في ذاكرة التطبيق المؤقتة، ومعها المدة والأبعاد إن لم تكن معروفة */
    fun thumb(ctx: Context, uri: Uri, kind: String, stamp: Long): JSONObject {
        val dir = File(ctx.cacheDir, "lib-thumbs").apply { mkdirs() }
        val out = File(dir, sha1("$uri|$stamp") + ".jpg")
        val r = JSONObject()
        val needMeta = kind != "image"
        if (out.exists() && !needMeta) return r.put("thumb", out.path)
        var bmp: Bitmap? = null
        if (!out.exists() && Build.VERSION.SDK_INT >= 29) bmp = runCatching { ctx.contentResolver.loadThumbnail(uri, Size(360, 360), null) }.getOrNull()
        if (kind == "image" && bmp == null && !out.exists()) {
            bmp = runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 360) s *= 2
                r.put("width", o.outWidth).put("height", o.outHeight)
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
            }.getOrNull()
        }
        if (needMeta) runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(ctx, uri)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { r.put("duration", it) }
                    if (kind == "video") {
                        val w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        val rot = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        if (rot % 180 != 0) r.put("width", h).put("height", w) else r.put("width", w).put("height", h)
                    }
                    if (bmp == null && !out.exists()) bmp = if (kind == "video") getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        else embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = 2 }) }
                } finally { release() }
            }
        }
        bmp?.let { b ->
            val scale = 360f / maxOf(b.width, b.height)
            val small = if (scale < 1) Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true) else b
            runCatching { out.outputStream().use { o -> small.compress(Bitmap.CompressFormat.JPEG, 80, o) } }
        }
        if (out.exists()) r.put("thumb", out.path)
        return r
    }

    // ---------- خصائص تقنية دون نسخ الملف (MediaExtractor يقرأ رابط المحتوى مباشرة) ----------
    private val CODECS = mapOf(
        "video/avc" to "h264", "video/hevc" to "hevc", "video/x-vnd.on2.vp9" to "vp9", "video/x-vnd.on2.vp8" to "vp8", "video/av01" to "av1",
        "video/mp4v-es" to "mpeg4", "video/3gpp" to "h263", "audio/mp4a-latm" to "aac", "audio/mpeg" to "mp3", "audio/opus" to "opus",
        "audio/vorbis" to "vorbis", "audio/flac" to "flac", "audio/raw" to "pcm_s16le", "audio/3gpp" to "amr_nb", "audio/ac3" to "ac3", "audio/eac3" to "eac3",
    )

    fun extract(ctx: Context, uri: Uri, size: Long): MediaArgs.Probe {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            var v: MediaArgs.VStream? = null
            var a: MediaArgs.AStream? = null
            var audios = 0
            var dur = 0L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                fun int(k: String) = if (f.containsKey(k)) runCatching { f.getInteger(k) }.getOrNull() else null
                if (f.containsKey(MediaFormat.KEY_DURATION)) dur = maxOf(dur, f.getLong(MediaFormat.KEY_DURATION))
                if (mime.startsWith("video/") && v == null) {
                    val rot = int("rotation-degrees") ?: 0
                    val fps = int(MediaFormat.KEY_FRAME_RATE)?.toDouble() ?: runCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrDefault(0.0)
                    v = MediaArgs.VStream(CODECS[mime] ?: mime.substringAfter('/'), int(MediaFormat.KEY_WIDTH) ?: 0, int(MediaFormat.KEY_HEIGHT) ?: 0, fps, (int(MediaFormat.KEY_BIT_RATE) ?: 0).toLong(), rot)
                } else if (mime.startsWith("audio/")) {
                    audios++
                    if (a == null) a = MediaArgs.AStream(CODECS[mime] ?: mime.substringAfter('/'), int(MediaFormat.KEY_SAMPLE_RATE) ?: 0, int(MediaFormat.KEY_CHANNEL_COUNT) ?: 0, (int(MediaFormat.KEY_BIT_RATE) ?: 0).toLong())
                }
            }
            val seconds = dur / 1_000_000.0
            val total = if (seconds > 0 && size > 0) (size * 8 / seconds).toLong() else 0
            if (v != null && v.bitrate <= 0 && total > 0) v = v.copy(bitrate = maxOf(0, total - (a?.bitrate?.takeIf { it > 0 } ?: 128_000)))
            if (v == null && a == null) throw IllegalStateException("لا يحتوي الملف صوتاً أو صورة يمكن قراءتها")
            return MediaArgs.Probe(seconds, size, total, "", v, a, audios, 0)
        } finally {
            ex.release()
        }
    }

    // ---------- المكررات ----------
    /** بصمة من ثلاث عينات (البداية والوسط والنهاية) مع الحجم: سريعة على الملفات الكبيرة ودقيقة عملياً للوسائط */
    fun fingerprint(ctx: Context, uri: Uri, size: Long): String? = runCatching {
        ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val md = MessageDigest.getInstance("SHA-1")
                val chunk = 64 * 1024L
                val buf = java.nio.ByteBuffer.allocate(chunk.toInt())
                for (pos in listOf(0L, maxOf(0, size / 2 - chunk / 2), maxOf(0, size - chunk))) {
                    buf.clear(); ch.position(pos)
                    while (buf.hasRemaining() && ch.read(buf) > 0) {}
                    buf.flip(); md.update(buf)
                }
                md.update(size.toString().toByteArray())
                md.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }.getOrNull()

    /** مجموعات الملفات المتطابقة: نفس الحجم أولاً، ثم نفس البصمة */
    fun duplicates(ctx: Context, items: List<Pair<String, Long>>, onProgress: (Int, Int) -> Unit): List<List<String>> {
        val bySize = items.filter { it.second > 0 }.groupBy { it.second }.values.filter { it.size > 1 }
        val todo = bySize.sumOf { it.size }
        var done = 0
        val groups = ArrayList<List<String>>()
        for (g in bySize) {
            g.groupBy { (uri, size) -> fingerprint(ctx, Uri.parse(uri), size).also { done++; onProgress(done, todo) } }
                .filterKeys { it != null }.values.filter { it.size > 1 }.forEach { groups += it.map { p -> p.first } }
        }
        return groups
    }
}
