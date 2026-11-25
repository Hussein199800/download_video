package com.tadaffuq.app.download

import com.tadaffuq.app.core.Fmt
import com.tadaffuq.data.NetKind
import com.tadaffuq.data.UsageDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** يعدّ البايتات المستلمة فعلاً لكل نوع شبكة ويكتبها في قاعدة البيانات كل بضع ثوانٍ. */
class UsageMeter(private val dao: UsageDao, private val conditions: Conditions) {
    private val pending = ConcurrentHashMap<NetKind, AtomicLong>()
    @Volatile var cellularToday = 0L
        private set
    @Volatile var cellularMonth = 0L
        private set

    fun add(bytes: Long) {
        pending.getOrPut(conditions.state.value.net) { AtomicLong() }.addAndGet(bytes)
    }

    fun start(scope: CoroutineScope) = scope.launch {
        refresh()
        while (isActive) {
            delay(5_000)
            flush()
        }
    }

    suspend fun flush() {
        val day = Fmt.day(System.currentTimeMillis())
        for ((net, counter) in pending) {
            val n = counter.getAndSet(0)
            if (n > 0) dao.add(day, net, n)
        }
        refresh()
    }

    private suspend fun refresh() {
        val now = System.currentTimeMillis()
        val day = Fmt.day(now)
        val month = day.substring(0, 7) + "-01"
        val rows = dao.range(month, day)
        cellularToday = rows.filter { it.day == day && it.net == NetKind.CELLULAR }.sumOf { it.bytes }
        cellularMonth = rows.filter { it.net == NetKind.CELLULAR }.sumOf { it.bytes }
    }
}
