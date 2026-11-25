package com.tadaffuq.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE trashedAt IS NULL ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE trashedAt IS NOT NULL ORDER BY trashedAt DESC")
    fun observeTrash(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: Long): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks ORDER BY createdAt")
    suspend fun all(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status IN (:statuses) AND trashedAt IS NULL ORDER BY priority DESC, createdAt ASC")
    suspend fun withStatus(statuses: List<TaskStatus>): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE trashedAt IS NULL AND (url = :url OR finalUrl = :url)")
    suspend fun byUrl(url: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE trashedAt IS NULL AND fileName = :name AND dirPath = :dir")
    suspend fun byName(dir: String, name: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status = 'COMPLETED' AND trashedAt IS NULL AND actualHash = :hash")
    suspend fun byHash(hash: String): List<TaskEntity>

    @Insert suspend fun insert(task: TaskEntity): Long
    @Insert suspend fun insertAll(tasks: List<TaskEntity>): List<Long>
    @Update suspend fun update(task: TaskEntity)

    @Query("UPDATE tasks SET status = :status, errorCode = :errorCode, errorDetail = :errorDetail WHERE id = :id")
    suspend fun setStatus(id: Long, status: TaskStatus, errorCode: String? = null, errorDetail: String? = null)

    @Query("UPDATE tasks SET status = :to WHERE status IN (:from)")
    suspend fun moveStatus(from: List<TaskStatus>, to: TaskStatus)

    @Query("UPDATE tasks SET downloadedBytes = :downloaded, totalBytes = :total, averageBps = :avg, maxBps = MAX(maxBps, :max) WHERE id = :id")
    suspend fun progress(id: Long, downloaded: Long, total: Long, avg: Long, max: Long)

    @Query("UPDATE tasks SET priority = :priority WHERE id = :id") suspend fun setPriority(id: Long, priority: Int)
    @Query("UPDATE tasks SET speedLimitBps = :bps WHERE id = :id") suspend fun setSpeedLimit(id: Long, bps: Long)
    @Query("UPDATE tasks SET trashedAt = :at WHERE id IN (:ids)") suspend fun trash(ids: List<Long>, at: Long?)
    @Query("DELETE FROM tasks WHERE id IN (:ids)") suspend fun delete(ids: List<Long>)
    @Query("DELETE FROM tasks WHERE trashedAt IS NOT NULL AND trashedAt < :before") suspend fun purgeTrash(before: Long)

    @Query("SELECT * FROM segments WHERE taskId = :taskId ORDER BY start")
    suspend fun segments(taskId: Long): List<SegmentEntity>

    @Query("DELETE FROM segments WHERE taskId = :taskId") suspend fun clearSegments(taskId: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSegments(list: List<SegmentEntity>)

    /** حفظ التقدم ذرياً: الأجزاء وعدّاد المهمة معاً. */
    @Transaction
    suspend fun saveProgress(taskId: Long, segments: List<SegmentEntity>, downloaded: Long, total: Long, etag: String?, lastModified: String?, acceptRanges: Boolean, finalUrl: String) {
        clearSegments(taskId)
        insertSegments(segments)
        saveProbe(taskId, downloaded, total, etag, lastModified, acceptRanges, finalUrl)
    }

    @Query("UPDATE tasks SET downloadedBytes = :downloaded, totalBytes = :total, etag = :etag, lastModified = :lastModified, acceptRanges = :acceptRanges, finalUrl = :finalUrl WHERE id = :id")
    suspend fun saveProbe(id: Long, downloaded: Long, total: Long, etag: String?, lastModified: String?, acceptRanges: Boolean, finalUrl: String)
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage WHERE day >= :from AND day <= :to ORDER BY day")
    fun observeRange(from: String, to: String): Flow<List<UsageEntity>>

    @Query("SELECT * FROM usage WHERE day >= :from AND day <= :to ORDER BY day")
    suspend fun range(from: String, to: String): List<UsageEntity>

    @Query("INSERT OR IGNORE INTO usage(day, net, bytes) VALUES (:day, :net, 0)")
    suspend fun ensure(day: String, net: NetKind)

    @Query("UPDATE usage SET bytes = bytes + :bytes WHERE day = :day AND net = :net")
    suspend fun addTo(day: String, net: NetKind, bytes: Long)

    @Transaction
    suspend fun add(day: String, net: NetKind, bytes: Long) { ensure(day, net); addTo(day, net, bytes) }
}

@Dao
interface LinkDao {
    @Query("SELECT * FROM saved_links ORDER BY createdAt DESC") fun observe(): Flow<List<SavedLinkEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(links: List<SavedLinkEntity>)
    @Query("DELETE FROM saved_links WHERE id IN (:ids)") suspend fun delete(ids: List<Long>)

    @Query("SELECT * FROM packages ORDER BY createdAt DESC") fun observePackages(): Flow<List<PackageEntity>>
    @Insert suspend fun insertPackage(p: PackageEntity): Long
}

@Dao
interface BrowserDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC") fun bookmarks(): Flow<List<BookmarkEntity>>
    @Upsert suspend fun upsertBookmark(b: BookmarkEntity)
    @Query("DELETE FROM bookmarks WHERE url = :url") suspend fun deleteBookmark(url: String)
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT :limit") fun history(limit: Int = 300): Flow<List<HistoryEntity>>
    @Insert suspend fun visit(h: HistoryEntity)
    @Query("DELETE FROM history") suspend fun clearHistory()
}
