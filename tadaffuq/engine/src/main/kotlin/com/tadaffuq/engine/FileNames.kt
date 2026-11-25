package com.tadaffuq.engine

import java.io.File
import java.net.URLDecoder

enum class FileCategory { COMPRESSED, DOCUMENTS, PROGRAMS, DISK_IMAGES, MODELS, OTHER }

object FileNames {
    private val illegal = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    /** اسم الملف من Content-Disposition (RFC 6266 وfilename* من RFC 5987) وإلا من المسار. */
    fun resolve(contentDisposition: String?, url: String, mime: String?): String {
        val fromHeader = contentDisposition?.let(::fromDisposition)
        val fromUrl = runCatching {
            val path = java.net.URI(url).rawPath.orEmpty()
            URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
        }.getOrNull()
        val raw = fromHeader?.takeIf { it.isNotBlank() } ?: fromUrl?.takeIf { it.isNotBlank() } ?: "download"
        var name = sanitize(raw)
        if (!name.contains('.') && mime != null) extensionFor(mime)?.let { name = "$name.$it" }
        return name
    }

    internal fun fromDisposition(header: String): String? {
        val parts = header.split(';').map { it.trim() }
        parts.firstOrNull { it.startsWith("filename*", ignoreCase = true) }?.let { p ->
            val v = p.substringAfter('=').trim().trim('"')
            val charset = v.substringBefore("''", "UTF-8").ifBlank { "UTF-8" }
            val enc = v.substringAfter("''", v)
            runCatching { return URLDecoder.decode(enc.replace("+", "%2B"), charset) }
        }
        parts.firstOrNull { it.startsWith("filename", ignoreCase = true) }?.let { p ->
            return p.substringAfter('=').trim().removeSurrounding("\"")
        }
        return null
    }

    fun sanitize(name: String): String {
        val cleaned = name.replace(illegal, "_").trim().trimStart('.').take(200)
        return when {
            cleaned.isBlank() || cleaned == "." || cleaned == ".." -> "download"
            else -> cleaned
        }
    }

    /** «اسم (1).امتداد» ثم (2)… حتى يُوجد اسم غير مستخدم. لا كتابة فوق ملف موجود دون موافقة. */
    fun uniqueIn(dir: File, name: String, taken: (File) -> Boolean = { it.exists() }): String {
        if (!taken(File(dir, name))) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        val base = dot?.let { name.substring(0, it) } ?: name
        val ext = dot?.let { name.substring(it) } ?: ""
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (!taken(File(dir, candidate))) return candidate
            i++
        }
    }

    fun category(name: String): FileCategory = when (name.substringAfterLast('.', "").lowercase()) {
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz", "lzma" -> FileCategory.COMPRESSED
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "txt", "csv", "epub", "rtf", "md", "json", "xml" -> FileCategory.DOCUMENTS
        "apk", "apks", "xapk", "aab", "exe", "msi", "dmg", "pkg", "deb", "rpm", "appimage", "sh", "bat", "jar" -> FileCategory.PROGRAMS
        "iso", "img", "bin", "vhd", "vmdk", "qcow2" -> FileCategory.DISK_IMAGES
        "gguf", "safetensors", "onnx", "tflite", "pt", "pth", "ckpt" -> FileCategory.MODELS
        else -> FileCategory.OTHER
    }

    /** امتدادات قد تنفّذ شيفرة؛ يُنبَّه المستخدم قبل فتحها. */
    fun isExecutable(name: String) = name.substringAfterLast('.', "").lowercase() in
        setOf("apk", "apks", "xapk", "exe", "msi", "bat", "cmd", "sh", "jar", "dmg", "pkg", "deb", "rpm", "appimage", "ps1", "vbs")

    private fun extensionFor(mime: String): String? = when (mime.substringBefore(';').trim().lowercase()) {
        "application/zip" -> "zip"
        "application/pdf" -> "pdf"
        "application/vnd.android.package-archive" -> "apk"
        "application/x-7z-compressed" -> "7z"
        "application/vnd.rar", "application/x-rar-compressed" -> "rar"
        "application/gzip" -> "gz"
        "text/plain" -> "txt"
        "application/json" -> "json"
        else -> null
    }
}
