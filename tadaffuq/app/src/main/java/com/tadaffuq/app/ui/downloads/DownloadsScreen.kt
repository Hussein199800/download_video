package com.tadaffuq.app.ui.downloads

import com.tadaffuq.app.core.sr
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.ui.MainViewModel
import com.tadaffuq.app.ui.components.Chip
import com.tadaffuq.app.ui.components.EmptyState
import com.tadaffuq.app.ui.components.LargeTitle
import com.tadaffuq.app.ui.holdText
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus

enum class Filter(val label: Int, val match: (TaskStatus) -> Boolean) {
    ALL(R.string.f_all, { true }),
    ACTIVE(R.string.f_active, { it.isActive }),
    WAITING(R.string.f_waiting, { it.isWaiting }),
    COMPLETED(R.string.f_completed, { it == TaskStatus.COMPLETED }),
    PAUSED(R.string.f_paused, { it == TaskStatus.PAUSED }),
    FAILED(R.string.f_failed, { it == TaskStatus.FAILED }),
}

@Composable
fun DownloadsScreen(vm: MainViewModel, onAdd: () -> Unit, padding: PaddingValues) {
    val tasks by vm.tasks.collectAsState()
    val live by vm.live.collectAsState()
    val hold by vm.hold.collectAsState()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var detail by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val shown = tasks.filter { filter.match(it.status) }

    LazyColumn(contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 88.dp, top = padding.calculateTopPadding())) {
        item {
            if (selection.isEmpty()) {
                LargeTitle(stringResource(R.string.tab_downloads), sr(R.string.downloads_sub, tasks.count { it.status.isActive }, tasks.count { it.status.isWaiting })) {
                    if (tasks.any { it.status.isActive || it.status.isWaiting }) IconButton(onClick = vm::pauseAll) { Icon(Icons.Rounded.Pause, stringResource(R.string.action_pause_all)) }
                    if (tasks.any { it.status == TaskStatus.PAUSED }) IconButton(onClick = vm::resumeAll) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.action_resume_all)) }
                }
            } else {
                SelectionBar(
                    count = selection.size,
                    onClose = { selection = emptySet() },
                    onAll = { selection = shown.map { it.id }.toSet() },
                    onPause = { vm.pause(selection); selection = emptySet() },
                    onResume = { vm.resume(selection); selection = emptySet() },
                    onDelete = { confirmDelete = true },
                )
            }
            hold?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(holdText(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.entries.forEach { f ->
                    Chip(stringResource(f.label), filter == f, { filter = f }, count = tasks.count { f.match(it.status) }.takeIf { f != Filter.ALL })
                }
            }
        }
        if (shown.isEmpty()) item {
            EmptyState(
                Icons.Rounded.Download,
                stringResource(if (tasks.isEmpty()) R.string.empty_downloads else R.string.empty_filter),
                stringResource(if (tasks.isEmpty()) R.string.empty_downloads_text else R.string.empty_filter_text),
                if (tasks.isEmpty()) stringResource(R.string.add_title) else null, onAdd,
            )
        }
        items(shown, key = { it.id }) { t ->
            TaskCard(
                task = t, live = live[t.id], selected = t.id in selection, selectionMode = selection.isNotEmpty(),
                onClick = { if (selection.isNotEmpty()) selection = selection.toggle(t.id) else detail = t.id },
                onLongClick = { selection = selection.toggle(t.id) },
                onPrimary = { primary(vm, t) },
            )
        }
    }

    detail?.let { id -> tasks.firstOrNull { it.id == id }?.let { TaskDetailSheet(it, live[id], vm) { detail = null } } ?: run { detail = null } }

    if (confirmDelete) DeleteDialog(
        count = selection.size,
        hasFiles = tasks.any { it.id in selection && it.status == TaskStatus.COMPLETED },
        onDismiss = { confirmDelete = false },
        onConfirm = { withFiles -> vm.delete(selection, withFiles); selection = emptySet(); confirmDelete = false },
    )
}

private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id

fun primary(vm: MainViewModel, t: TaskEntity) {
    when (t.status) {
        TaskStatus.RUNNING, TaskStatus.QUEUED, TaskStatus.WAITING_NETWORK -> vm.pause(listOf(t.id))
        TaskStatus.PAUSED, TaskStatus.FAILED -> vm.resume(listOf(t.id))
        else -> Unit
    }
}

@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onAll: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel)) }
        Text(sr(R.string.selected_n, count), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onAll) { Icon(Icons.Rounded.SelectAll, stringResource(R.string.select_all)) }
        IconButton(onClick = onPause) { Icon(Icons.Rounded.Pause, stringResource(R.string.action_pause)) }
        IconButton(onClick = onResume) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.action_resume)) }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete)) }
    }
}

@Composable
fun DeleteDialog(count: Int, hasFiles: Boolean, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var withFiles by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(sr(R.string.delete_title, count)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(R.string.delete_desc))
                if (hasFiles) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(withFiles, { withFiles = it })
                    Text(stringResource(R.string.delete_files_too))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(withFiles) }) { Text(stringResource(R.string.action_delete), color = T.extra.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
