package com.sayl.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureRetryTest {
    @Test
    fun classifiesFailures() {
        assertEquals(Failure.Kind.EXTRACT, Failure.classify("ERROR: unable to download video data: HTTP Error 403: Forbidden"))
        assertEquals(Failure.Kind.EXTRACT, Failure.classify("ERROR: [tiktok] Unable to extract webpage video data"))
        assertEquals(Failure.Kind.NETWORK, Failure.classify("ERROR: Read timed out."))
        assertEquals(Failure.Kind.NETWORK, Failure.classify("<urlopen error [Errno 7] No address associated with hostname>"))
        assertEquals(Failure.Kind.TEMPORARY, Failure.classify("ERROR: unable to download webpage: HTTP Error 503: Service Unavailable"))
        assertEquals(Failure.Kind.TEMPORARY, Failure.classify("HTTP Error 429: Too Many Requests"))
        assertEquals(Failure.Kind.PERMANENT, Failure.classify("ERROR: [youtube] x: Sign in to confirm you’re not a bot"))
        assertEquals(Failure.Kind.PERMANENT, Failure.classify("OSError: [Errno 28] No space left on device"))
        assertEquals(Failure.Kind.PERMANENT, Failure.classify("ERROR: Unsupported URL: https://example.com"))
        assertEquals(Failure.Kind.PERMANENT, Failure.classify("ERROR: [youtube] x: Video unavailable. This video has been removed"))
        assertEquals(Failure.Kind.TEMPORARY, Failure.classify("something odd"))
    }

    @Test
    fun retryDelaysGrow() {
        assertEquals(30_000L, Retry.delayMs(1))
        assertEquals(120_000L, Retry.delayMs(2))
        assertEquals(600_000L, Retry.delayMs(3))
        assertEquals(600_000L, Retry.delayMs(9))
    }

    @Test
    fun retryOnlyTemporaryAndExtractWithinLimit() {
        assertTrue(Retry.shouldRetry(Failure.Kind.TEMPORARY, attempts = 0, max = 5))
        assertTrue(Retry.shouldRetry(Failure.Kind.EXTRACT, attempts = 4, max = 5))
        assertFalse(Retry.shouldRetry(Failure.Kind.EXTRACT, attempts = 5, max = 5))
        assertFalse(Retry.shouldRetry(Failure.Kind.PERMANENT, attempts = 0, max = 5))
        assertFalse(Retry.shouldRetry(Failure.Kind.TEMPORARY, attempts = 0, max = 0))
    }
}
