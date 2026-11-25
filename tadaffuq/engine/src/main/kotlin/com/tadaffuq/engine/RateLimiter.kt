package com.tadaffuq.engine

import kotlinx.coroutines.delay

/**
 * دلو رموز (token bucket) بنموذج «الدَّين»: كل قراءة تخصم من الرصيد، وإن صار سالباً
 * ينتظر المستدعي حتى يُسدَّد. السعة ثانية واحدة. 0 = بلا حد. قابل للتغيير أثناء التشغيل.
 */
class RateLimiter(bytesPerSecond: Long = 0, private val clock: () -> Long = System::nanoTime) {
    @Volatile var bytesPerSecond: Long = bytesPerSecond.coerceAtLeast(0)
        set(value) { field = value.coerceAtLeast(0) }

    private val lock = Any()
    private var tokens = 0.0
    private var last = clock()

    suspend fun acquire(bytes: Int) {
        val rate = bytesPerSecond
        if (rate <= 0) {
            synchronized(lock) { last = clock(); tokens = 0.0 }
            return
        }
        val waitNs = synchronized(lock) {
            val now = clock()
            tokens = (tokens + (now - last) / 1e9 * rate).coerceAtMost(rate.toDouble())
            last = now
            tokens -= bytes
            if (tokens >= 0) 0L else ((-tokens) / rate * 1e9).toLong()
        }
        if (waitNs > 0) delay((waitNs / 1_000_000).coerceAtLeast(1))
    }
}
