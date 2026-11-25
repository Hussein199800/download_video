package com.tadaffuq.app.ui.files

import com.tadaffuq.app.core.sr
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.core.Search
import com.tadaffuq.app.download.StorageDirs
import com.tadaffuq.app.ui.MainViewModel
import com.tadaffuq.app.ui.components.Chip
import com.tadaffuq.app.ui.components.EmptyState
import com.tadaffuq.app.ui.components.LargeTitle
import com.tadaffuq.app.ui.downloads.TaskCard
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus
import com.tadaffuq.engine.FileCategory
import com.tadaffuq.engine.FileNames
import com.tadaffuq.engine.Hashes
import kotlinx.coroutines.launch
import java.io.File

enum class Sort(val label: Int) { DATE(R.string.sort_date), NAME(R.string.sort_name), SIZE(R.string.sort_size) }

fun categoryLabel(c: FileCategory) = when (c) {
    FileCategory.COMPRESSED -> R.string.cat_compressed
    FileCategory.DOCUMENTS -> R.string.cat_documents
    FileCategory.PROGRAMS -> R.string.cat_programs
    FileCategory.DISK_IMAGES -> R.string.cat_disk_images
    FileCategory.MODELS -> R.string.cat_models
    FileCategory.OTHER -> R.string.cat_other
}

@Composable
fun FilesScreen(vm: MainViewModel, padding: PaddingValues) {
    val ctx = LocalContext.current
    val tasks by vm.tasks.collectAsState()
    val files = tasks.filter { it.status == TaskStatus.COMPLETED && it.filePath != null }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf<FileCategory?>(null) }
    var sort by rememberSaveable { mutableStateOf(Sort.DATE) }
    var sortMenu by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var info by remember { mutableStateOf<TaskEntity?>(null) }
    var cleanup by remember { mutableStateOf(false) }

    val shown = files
        .filter { category == null || FileNames.category(it.fileName) == category }
        .filter { Search.matches(it.fileName, query) }
        .let { l -> when (sort) { Sort.DATE -> l.sortedByDescending { it.completedAt }; Sort.NAME -> l.sortedBy { Search.normalize(it.fileName) }; Sort.SIZE -> l.sortedByDescending { it.totalBytes } } }

    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
        item {
            if (selection.isEmpty()) {
                LargeTitle(stringResource(R.string.tab_files), sr(R.string.files_sub, files.size, Fmt.bytes(files.sumOf { it.totalBytes }))) {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Rounded.Search, stringResource(R.string.search)) }
                    androidx.compose.foundation.layout.Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.sort)) }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            Sort.entries.forEach { s -> DropdownMenuItem(text = { Text(stringResource(s.label)) }, onClick = { sort = s; sortMenu = false }) }
                        }
                    }
                    IconButton(onClick = { cleanup = true }) { Icon(Icons.Rounded.CleaningServices, stringResource(R.string.cleanup)) }
                }
            } else {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel)) }
                    Text(sr(R.string.selected_n, selection.size), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { selection = shown.map { it.id }.toSet() }) { Icon(Icons.Rounded.SelectAll, stringResource(R.string.select_all)) }
                    IconButton(onClick = {
                        val list = files.filter { it.id in selection }.mapNotNull { it.filePath?.let(::File)?.takeIf(File::exists) }
                        if (list.isNotEmpty()) ctx.startActivity(StorageDirs.shareIntent(ctx, list))
                    }) { Icon(Icons.Rounded.Share, stringResource(R.string.action_share)) }
                    IconButton(onClick = { vm.moveToTrash(files.filter { it.id in selection }); selection = emptySet() }) { Icon(Icons.Rounded.Delete, stringResource(R.string.move_to_trash)) }
                }
            }
            if (searching) OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.search_files)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.f_all), category == null, { category = null })
                FileCategory.entries.forEach { c ->
                    val n = files.count { FileNames.category(it.fileName) == c }
                    if (n > 0) Chip(stringResource(categoryLabel(c)), category == c, { category = c }, n)
                }
            }
        }
        if (shown.isEmpty()) item {
            EmptyState(Icons.Rounded.Folder, stringResource(R.string.empty_files), sr(R.string.empty_files_text, StorageDirs.ROOT))
        }
        items(shown, key = { it.id }) { t ->
            TaskCard(t, null, t.id in selection, selection.isNotEmpty(),
                onClick = { if (selection.isNotEmpty()) selection = if (t.id in selection) selection - t.id else selection + t.id else info = t },
                onLongClick = { selection = if (t.id in selection) selection - t.id else selection + t.id },
                onPrimary = {})
        }
    }

    info?.let { t -> FileInfoSheet(tasks.firstOrNull { it.id == t.id } ?: t, vm) { info = null } }
    if (cleanup) CleanupSheet(files, vm) { cleanup = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileInfoSheet(t: TaskEntity, vm: MainViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = t.filePath?.let(::File)
    var renaming by remember { mutableStateOf(false) }
    var hashing by remember { mutableStateOf(false) }
    var reference by remember { mutableStateOf("") }
    var verify by remember { mutableStateOf<Boolean?>(null) }
    var warnExe by remember { mutableStateOf(false) }
    val settings by vm.settings.collectAsState()

    fun open() {
        try { ctx.startActivity(StorageDirs.openIntent(ctx, file!!)) } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text(t.fileName, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Line(R.string.size, Fmt.bytes(t.totalBytes))
            Line(R.string.type, Fmt.iso(t.mimeType ?: StorageDirs.mime(t.fileName)))
            t.completedAt?.let { Line(R.string.completed_at, Fmt.date(it)) }
            Line(R.string.path, Fmt.iso(t.filePath.orEmpty()))
            Line(R.string.source, t.url)
            if (file?.exists() != true) Text(stringResource(R.string.file_missing), color = T.extra.danger, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Text("SHA-256", style = MaterialTheme.typography.titleSmall)
            if (t.actualHash != null) Text(Fmt.iso(t.actualHash.orEmpty()), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
            else TextButton(onClick = { hashing = true; scope.launch { vm.computeHash(t); hashing = false } }, enabled = !hashing && file?.exists() == true) {
                Text(stringResource(if (hashing) R.string.hashing else R.string.compute_hash))
            }
            OutlinedTextField(reference, { reference = it.trim(); verify = null }, label = { Text(stringResource(R.string.verify_against)) },
                supportingText = { Text(stringResource(R.string.verify_desc)) }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            TextButton(onClick = {
                hashing = true
                scope.launch {
                    verify = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { file?.let { Hashes.verify(it, reference)?.first } }
                    hashing = false
                }
            }, enabled = !hashing && Hashes.detect(reference) != null && file?.exists() == true) { Text(stringResource(R.string.verify)) }
            verify?.let { Text(stringResource(if (it) R.string.verify_ok else R.string.verify_bad), color = if (it) T.extra.success else T.extra.danger) }

            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (file?.exists() == true) {
                    AssistChip(onClick = { if (settings.warnExecutables && FileNames.isExecutable(t.fileName)) warnExe = true else open() }, label = { Text(stringResource(R.string.action_open)) })
                    AssistChip(onClick = { ctx.startActivity(StorageDirs.shareIntent(ctx, listOf(file))) }, label = { Text(stringResource(R.string.action_share)) })
                    AssistChip(onClick = { renaming = true }, label = { Text(stringResource(R.string.rename)) })
                }
                AssistChip(onClick = { vm.moveToTrash(listOf(t)); onDismiss() }, label = { Text(stringResource(R.string.move_to_trash)) })
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(t.fileName) }
        var failed by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(name, { name = it; failed = false }, isError = failed, supportingText = { if (failed) Text(stringResource(R.string.rename_failed)) }, maxLines = 3) },
            confirmButton = { TextButton(onClick = { scope.launch { if (vm.rename(t, name)) renaming = false else failed = true } }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (warnExe) AlertDialog(
        onDismissRequest = { warnExe = false },
        title = { Text(stringResource(R.string.exe_title)) },
        text = { Text(stringResource(R.string.exe_text)) },
        confirmButton = { TextButton(onClick = { warnExe = false; open() }) { Text(stringResource(R.string.open_anyway)) } },
        dismissButton = { TextButton(onClick = { warnExe = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Line(label: Int, value: String) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = T.extra.secondaryText)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** أدوات التنظيف: المكرر (بالحجم ثم البصمة)، والكبير، والفارغ، والملفات المفقودة من القرص. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanupSheet(files: List<TaskEntity>, vm: MainViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var duplicates by remember { mutableStateOf<List<List<TaskEntity>>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val large = files.filter { it.totalBytes >= 500L * 1024 * 1024 }.sortedByDescending { it.totalBytes }
    val empty = files.filter { it.totalBytes == 0L }
    val missing = files.filter { f -> f.filePath?.let { !File(it).exists() } ?: true }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.cleanup), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.cleanup_desc), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.dup_files), style = MaterialTheme.typography.titleSmall)
            when {
                scanning -> Text(stringResource(R.string.hashing), color = T.extra.secondaryText)
                duplicates == null -> TextButton(onClick = {
                    scanning = true
                    scope.launch {
                        duplicates = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            files.filter { it.totalBytes > 0 && it.filePath?.let { p -> File(p).exists() } == true }
                                .groupBy { it.totalBytes }.values.filter { it.size > 1 }
                                .flatMap { sameSize -> sameSize.groupBy { it.actualHash ?: vm.computeHash(it) }.values.filter { it.size > 1 } }
                        }
                        scanning = false
                    }
                }) { Text(stringResource(R.string.scan)) }
                duplicates!!.isEmpty() -> Text(stringResource(R.string.none_found), color = T.extra.secondaryText)
                else -> duplicates!!.forEach { group ->
                    Text(group.first().fileName + " · " + Fmt.bytes(group.first().totalBytes), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.moveToTrash(group.drop(1)); duplicates = duplicates!! - listOf(group) }) {
                        Text(sr(R.string.keep_one, group.size - 1))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.large_files), style = MaterialTheme.typography.titleSmall)
            if (large.isEmpty()) Text(stringResource(R.string.none_found), color = T.extra.secondaryText)
            large.take(10).forEach { Text("${it.fileName} · ${Fmt.bytes(it.totalBytes)}", style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.empty_files_title), style = MaterialTheme.typography.titleSmall)
            if (empty.isEmpty()) Text(stringResource(R.string.none_found), color = T.extra.secondaryText)
            else TextButton(onClick = { vm.moveToTrash(empty) }) { Text(sr(R.string.trash_n, empty.size)) }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.missing_files), style = MaterialTheme.typography.titleSmall)
            if (missing.isEmpty()) Text(stringResource(R.string.none_found), color = T.extra.secondaryText)
            else TextButton(onClick = { vm.delete(missing.map { it.id }, false) }) { Text(sr(R.string.remove_records_n, missing.size)) }
            Spacer(Modifier.height(20.dp))
        }
    }
}
