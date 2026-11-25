package com.tadaffuq.engine

import java.io.File
import java.security.MessageDigest

enum class HashAlgo(val jca: String, val hexLength: Int) { SHA256("SHA-256", 64), MD5("MD5", 32), SHA1("SHA-1", 40) }

object Hashes {
    fun of(file: File, algo: HashAlgo, onProgress: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance(algo.jca)
        val buf = ByteArray(256 * 1024)
        var done = 0L
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                onProgress(done)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** يستنتج الخوارزمية من طول البصمة المرجعية التي ألصقها المستخدم. */
    fun detect(reference: String): HashAlgo? {
        val hex = reference.trim().lowercase()
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return HashAlgo.entries.firstOrNull { it.hexLength == hex.length }
    }

    fun verify(file: File, reference: String): Pair<Boolean, String>? {
        val algo = detect(reference) ?: return null
        val actual = of(file, algo)
        return (actual == reference.trim().lowercase()) to actual
    }
}
