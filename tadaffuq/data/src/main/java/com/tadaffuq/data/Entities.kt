package com.tadaffuq.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TaskStatus {
    QUEUED, RUNNING, PAUSED, WAITING_NETWORK, VERIFYING, COMPLETED, FAILED;

    val isActive get() = this == RUNNING || this == VERIFYING
    val isWaiting get() = this == QUEUED || this == WAITING_NETWORK
}

/** مهمة تنزيل. Room هو مصدر الحقيقة؛ الواجهة تقرأ منه عبر Flow. */
@Entity(
    tableName = "tasks",
    indices = [Index("status"), Index("url"), Index("packageId")],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val finalUrl: String? = null,
    val fileName: String,
    /** المجلد النهائي (مسار ملف). */
    val dirPath: String,
    val status: TaskStatus = TaskStatus.QUEUED,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val etag: String? = null,
    val lastModified: String? = null,
    val acceptRanges: Boolean = false,
    val mimeType: String? = null,
    /** 0 = تلقائي. */
    val connections: Int = 0,
    /** 0 = بلا حد. */
    val speedLimitBps: Long = 0,
    /** أعلى = أسبق. */
    val priority: Int = 0,
    val expectedHash: String? = null,
    val actualHash: String? = null,
    val hashOk: Boolean? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val cookies: String? = null,
    val errorCode: String? = null,
    val errorDetail: String? = null,
    val retries: Int = 0,
    val networkRetries: Int = 0,
    val serverRetries: Int = 0,
    val averageBps: Long = 0,
    val maxBps: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val packageId: Long? = null,
    val wifiOnly: Boolean = false,
    /** مسار الملف النهائي بعد الاكتمال. */
    val filePath: String? = null,
    /** في سلة المحذوفات (للملفات التي يديرها التطبيق). */
    val trashedAt: Long? = null,
)

@Entity(
    tableName = "segments",
    primaryKeys = ["taskId", "idx"],
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
)
data class SegmentEntity(
    val taskId: Long,
    val idx: Int,
    val start: Long,
    val endInclusive: Long,
    val downloaded: Long,
)

enum class NetKind { WIFI, CELLULAR, OTHER }

/** استهلاك البيانات اليومي حسب نوع الشبكة، من البايتات المستلمة فعلاً. */
@Entity(tableName = "usage", primaryKeys = ["day", "net"])
data class UsageEntity(
    /** yyyy-MM-dd بالتوقيت المحلي. */
    val day: String,
    val net: NetKind,
    val bytes: Long,
)

/** حزمة: مجموعة مهام تُدار معاً. */
@Entity(tableName = "packages")
data class PackageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** مركز الروابط: روابط محفوظة لتنزيلها لاحقاً. */
@Entity(tableName = "saved_links", indices = [Index(value = ["url"], unique = true)])
data class SavedLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "bookmarks", indices = [Index(value = ["url"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "history", indices = [Index("visitedAt")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long = System.currentTimeMillis(),
)
