package com.sayl.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ArgGuardTest {
    @Test
    fun rejectsOutputAndExecFlags() {
        for (f in listOf("-o", "--output", "-P", "--paths", "--exec", "--exec-before-download", "--config-location", "--batch-file", "-a")) {
            assertNotNull(f, ArgGuard.check(listOf("--no-playlist", f, "x")))
        }
        assertNotNull(ArgGuard.check(listOf("--exec=rm -rf /")))
        assertNotNull(ArgGuard.check(listOf("-o/sdcard/x")))
    }

    @Test
    fun acceptsAppArgs() {
        val mp3 = listOf("--no-playlist", "--windows-filenames", "--no-mtime", "--retries", "3", "-f", "ba/b", "-x",
            "--audio-format", "mp3", "--audio-quality", "192K", "--embed-thumbnail", "--embed-metadata", "--downloader", "libaria2c.so")
        assertNull(ArgGuard.check(mp3))
        // قيمة خيار تبدأ بـ -o ليست خياراً (مثل صيغة -f)
        assertNull(ArgGuard.check(listOf("-f", "bv*[height<=720]+ba/b[height<=720]/b")))
    }
}
