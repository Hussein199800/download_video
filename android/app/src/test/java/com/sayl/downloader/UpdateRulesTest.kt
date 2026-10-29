package com.sayl.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {
    private val pkg = "com.sayl.downloader"

    @Test
    fun onlyOwnReleaseUrls() {
        assertTrue(UpdateRules.isAllowedUrl(UpdateRules.PREFIX + "v1.2.0/sayl-1.2.0-arm64-v8a.apk"))
        assertFalse(UpdateRules.isAllowedUrl("https://evil.com/sayl.apk"))
        assertFalse(UpdateRules.isAllowedUrl(UpdateRules.PREFIX + "../x.apk"))
        assertFalse(UpdateRules.isAllowedUrl(null))
    }

    @Test
    fun rejectsBadPackages() {
        val s = setOf("aa")
        assertEquals("الملف المحمّل ليس تطبيق سَيْل", UpdateRules.reject("other.app", pkg, 22, 12, s, s))
        assertEquals("النسخة المحمّلة ليست أحدث من المثبّتة", UpdateRules.reject(pkg, pkg, 12, 12, s, s))
        assertEquals("توقيع التحديث لا يطابق التطبيق المثبّت", UpdateRules.reject(pkg, pkg, 22, 12, setOf("bb"), s))
        assertNull(UpdateRules.reject(pkg, pkg, 22, 12, s, s))
    }
}
