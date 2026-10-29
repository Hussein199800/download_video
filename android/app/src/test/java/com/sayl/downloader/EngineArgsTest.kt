package com.sayl.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineArgsTest {
    @Test
    fun outputArgsKeepProgressVisible() {
        val a = Engine.outputArgs(File("/x/work"))
        // --print يجعل yt-dlp صامتاً؛ بدون --progress لا يصل التقدم للإشعار
        assertTrue(a.contains("--progress"))
        assertTrue(a.contains("--newline"))
        assertEquals("/x/work/" + Engine.OUTPUT_TEMPLATE, a[a.indexOf("-o") + 1])
        assertEquals("after_move:filepath", a[a.indexOf("--print") + 1])
    }
}
