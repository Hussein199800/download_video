package com.tadaffuq.engine

/** منطق تقسيم الملف الخالص، بلا شبكة ولا ملفات، ليسهل اختباره. */
object SegmentPlanner {

    /** عدد الاتصالات الابتدائي: ملف صغير = اتصال واحد، ويزيد مع الحجم حتى الحد. */
    fun autoConnections(total: Long, max: Int, minSplit: Long): Int {
        if (total <= 0) return 1
        val bySize = (total / (minSplit * 4)).coerceAtLeast(1)
        return minOf(bySize, max.toLong(), 8L).toInt().coerceAtLeast(1)
    }

    fun initial(total: Long, count: Int): List<Segment> {
        if (total <= 0) return listOf(Segment(0, 0, -1))
        val n = count.toLong().coerceIn(1, total)
        val base = total / n
        val rem = total % n
        var start = 0L
        return (0 until n.toInt()).map { i ->
            val len = base + if (i < rem) 1 else 0
            Segment(i, start, start + len - 1).also { start += len }
        }
    }

    /**
     * تقسيم ديناميكي: يأخذ الجزء ذا أكبر متبقٍّ ويقسمه نصفين، فيكمل الاتصال القديم
     * النصف الأول ويأخذ اتصال جديد النصف الثاني. يعيد null إن لم يبقَ ما يستحق التقسيم.
     */
    fun splitLargest(segments: MutableList<Segment>, minSplit: Long): Segment? {
        val target = segments.filter { !it.done && it.end >= 0 }.maxByOrNull { it.remaining } ?: return null
        synchronized(target) {
            val remaining = target.remaining
            if (remaining < minSplit * 2) return null
            val mid = target.position + remaining / 2
            val newSeg = Segment(nextIndex(segments), mid, target.end)
            target.end = mid - 1
            segments.add(newSeg)
            return newSeg
        }
    }

    private fun nextIndex(segments: List<Segment>) = (segments.maxOfOrNull { it.index } ?: -1) + 1

    /** يتحقق أن الأجزاء تغطي الملف كاملاً بلا تداخل ولا فجوات. */
    fun isConsistent(states: List<SegmentState>, total: Long): Boolean {
        if (total <= 0) return states.size == 1
        val sorted = states.sortedBy { it.start }
        var expected = 0L
        for (s in sorted) {
            if (s.start != expected || s.end < s.start - 1 || s.downloaded < 0 || s.position > s.end + 1) return false
            expected = s.end + 1
        }
        return expected == total
    }
}
