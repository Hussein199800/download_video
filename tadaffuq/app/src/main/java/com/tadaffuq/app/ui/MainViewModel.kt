package com.tadaffuq.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.download.DownloadManager
import com.tadaffuq.app.download.StorageDirs
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.NetKind
import com.tadaffuq.data.SavedLinkEntity
import com.tadaffuq.data.Settings
import com.tadaffuq.data.SettingsRepository
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus
import com.tadaffuq.data.UsageEntity
import com.tadaffuq.engine.Hashes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import javax.inject.Inject

/** ما تحتاجه الرئيسية والتنزيلات والملفات والتقارير، من مصدر الحقيقة نفسه. */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val db: AppDatabase,
    private val settingsRepo: SettingsRepository,
    val manager: DownloadManager,
) : ViewModel() {
    private fun <T> StateFlowOf(flow: kotlinx.coroutines.flow.Flow<T>, initial: T): StateFlow<T> =
        flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val tasks: StateFlow<List<TaskEntity>> = StateFlowOf(db.tasks().observeAll(), emptyList())
    val trash: StateFlow<List<TaskEntity>> = StateFlowOf(db.tasks().observeTrash(), emptyList())
    val settings: StateFlow<Settings> = StateFlowOf(settingsRepo.settings, Settings())
    val links: StateFlow<List<SavedLinkEntity>> = StateFlowOf(db.links().observe(), emptyList())
    val live = manager.live
    val speedHistory = manager.speedHistory
    val hold = manager.hold
    val device = manager.conditions.state

    private val yearStart = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -365) }.timeInMillis
    val usage: StateFlow<List<UsageEntity>> = StateFlowOf(db.usage().observeRange(Fmt.day(yearStart), Fmt.day(System.currentTimeMillis() + 86_400_000)), emptyList())

    fun updateSettings(f: (Settings) -> Settings) = viewModelScope.launch { settingsRepo.update(f) }

    fun pause(ids: Collection<Long>) = viewModelScope.launch { manager.pause(ids) }
    fun resume(ids: Collection<Long>) = viewModelScope.launch { manager.resume(ids) }
    fun restart(id: Long) = viewModelScope.launch { manager.restart(id) }
    fun delete(ids: Collection<Long>, withFiles: Boolean) = viewModelScope.launch { manager.delete(ids, withFiles) }
    fun pauseAll() = viewModelScope.launch { manager.pauseAll() }
    fun resumeAll() = viewModelScope.launch { manager.resumeAll() }
    fun setPriority(id: Long, p: Int) = viewModelScope.launch { manager.setPriority(id, p) }
    fun setSpeedLimit(id: Long, bps: Long) = viewModelScope.launch { manager.setSpeedLimit(id, bps) }
    fun updateUrl(id: Long, url: String) = viewModelScope.launch { manager.updateUrl(id, url) }

    // ===== الملفات =====

    /** سلة المحذوفات: الملف يُنقل إلى مجلد مخفي ويُستعاد منه؛ يُحذف نهائياً بعد ٣٠ يوماً. */
    fun moveToTrash(tasks: List<TaskEntity>) = viewModelScope.launch(Dispatchers.IO) {
        val bin = StorageDirs.trash().apply { mkdirs() }
        tasks.forEach { t ->
            val f = t.filePath?.let(::File) ?: return@forEach
            if (f.exists()) {
                val dest = File(bin, "${t.id}_${f.name}")
                if (f.renameTo(dest)) db.tasks().update(t.copy(filePath = dest.path, trashedAt = System.currentTimeMillis()))
            } else db.tasks().update(t.copy(trashedAt = System.currentTimeMillis()))
        }
    }

    fun restore(tasks: List<TaskEntity>) = viewModelScope.launch(Dispatchers.IO) {
        tasks.forEach { t ->
            val f = t.filePath?.let(::File)
            val dir = File(t.dirPath).apply { mkdirs() }
            if (f != null && f.exists()) {
                val name = com.tadaffuq.engine.FileNames.uniqueIn(dir, t.fileName)
                val dest = File(dir, name)
                if (f.renameTo(dest)) db.tasks().update(t.copy(filePath = dest.path, fileName = name, trashedAt = null))
            } else db.tasks().update(t.copy(trashedAt = null))
        }
    }

    fun purge(tasks: List<TaskEntity>) = viewModelScope.launch(Dispatchers.IO) {
        tasks.forEach { it.filePath?.let(::File)?.delete() }
        db.tasks().delete(tasks.map { it.id })
    }

    fun purgeOldTrash() = viewModelScope.launch(Dispatchers.IO) {
        val before = System.currentTimeMillis() - 30L * 86_400_000
        db.tasks().all().filter { (it.trashedAt ?: Long.MAX_VALUE) < before }.forEach { it.filePath?.let(::File)?.delete() }
        db.tasks().purgeTrash(before)
    }

    /** إعادة تسمية دون الكتابة فوق ملف موجود. */
    suspend fun rename(t: TaskEntity, newName: String): Boolean = withContext(Dispatchers.IO) {
        val f = t.filePath?.let(::File) ?: return@withContext false
        val clean = com.tadaffuq.engine.FileNames.sanitize(newName)
        val dest = File(f.parentFile, clean)
        if (dest.exists() || !f.renameTo(dest)) return@withContext false
        db.tasks().update(t.copy(fileName = clean, filePath = dest.path))
        StorageDirs.scan(ctx, dest)
        true
    }

    suspend fun computeHash(t: TaskEntity): String? = withContext(Dispatchers.IO) {
        val f = t.filePath?.let(::File)?.takeIf { it.exists() } ?: return@withContext null
        val h = Hashes.of(f, com.tadaffuq.engine.HashAlgo.SHA256)
        db.tasks().update(t.copy(actualHash = h))
        h
    }

    fun saveLinks(urls: List<String>) = viewModelScope.launch { db.links().insert(urls.map { SavedLinkEntity(url = it) }) }
    fun deleteLinks(ids: List<Long>) = viewModelScope.launch { db.links().delete(ids) }

    // ===== التقارير =====

    data class Period(val bytes: Long, val wifi: Long, val cellular: Long)

    fun period(rows: List<UsageEntity>, fromDay: String): Period {
        val r = rows.filter { it.day >= fromDay }
        return Period(r.sumOf { it.bytes }, r.filter { it.net == NetKind.WIFI }.sumOf { it.bytes }, r.filter { it.net == NetKind.CELLULAR }.sumOf { it.bytes })
    }

    /** ملف CSV في مجلد المشاركة المؤقت؛ لا يُكتب خارج التطبيق إلا بمشاركة المستخدم. */
    suspend fun exportCsv(rows: List<UsageEntity>, tasks: List<TaskEntity>): Uri = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "tadaffuq-report-${Fmt.day(System.currentTimeMillis())}.csv")
        f.bufferedWriter().use { w ->
            w.write("﻿") // ليفتحه Excel بترميز UTF-8
            w.write("day,network,bytes\n")
            rows.forEach { w.write("${it.day},${it.net},${it.bytes}\n") }
            w.write("\nfile,status,bytes,created,completed,average_bps\n")
            tasks.forEach {
                val name = "\"" + it.fileName.replace("\"", "\"\"") + "\""
                w.write("$name,${it.status},${it.totalBytes},${Fmt.day(it.createdAt)},${it.completedAt?.let(Fmt::day).orEmpty()},${it.averageBps}\n")
            }
        }
        StorageDirs.uri(ctx, f)
    }

    fun activeCount(list: List<TaskEntity>) = list.count { it.status == TaskStatus.RUNNING }
}
