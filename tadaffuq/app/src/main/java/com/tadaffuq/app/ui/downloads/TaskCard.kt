package com.tadaffuq.app.ui.downloads

import com.tadaffuq.app.core.sr
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.ui.components.ProgressLine
import com.tadaffuq.app.ui.errorText
import com.tadaffuq.app.ui.statusText
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus
import com.tadaffuq.engine.FileCategory
import com.tadaffuq.engine.FileNames
import com.tadaffuq.engine.Snapshot

fun categoryIcon(name: String): ImageVector = when (FileNames.category(name)) {
    FileCategory.COMPRESSED -> Icons.Rounded.Archive
    FileCategory.DOCUMENTS -> Icons.Rounded.Description
    FileCategory.PROGRAMS -> Icons.Rounded.Apps
    FileCategory.DISK_IMAGES -> Icons.Rounded.Album
    FileCategory.MODELS -> Icons.Rounded.Memory
    FileCategory.OTHER -> Icons.Rounded.InsertDriveFile
}

@Composable
fun statusColor(s: TaskStatus): Color = when (s) {
    TaskStatus.RUNNING, TaskStatus.VERIFYING -> T.extra.chartLine
    TaskStatus.COMPLETED -> T.extra.success
    TaskStatus.FAILED -> T.extra.danger
    TaskStatus.WAITING_NETWORK -> T.extra.warning
    else -> T.extra.paused
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskCard(
    task: TaskEntity,
    live: Snapshot?,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPrimary: () -> Unit,
) {
    val downloaded = live?.downloaded ?: task.downloadedBytes
    val total = live?.total ?: task.totalBytes
    val color = statusColor(task.status)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else T.extra.card,
    ) {
        Column(
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(if (task.status == TaskStatus.COMPLETED) Icons.Rounded.CheckCircle else categoryIcon(task.fileName), null, tint = if (task.status == TaskStatus.COMPLETED) T.extra.success else MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.fileName, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle(task, live, downloaded, total), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                }
                if (!selectionMode) PrimaryAction(task.status, onPrimary)
            }
            if (task.status != TaskStatus.COMPLETED) {
                Spacer(Modifier.height(10.dp))
                val fraction = if (total > 0) downloaded.toFloat() / total else if (task.status == TaskStatus.RUNNING) null else 0f
                ProgressLine(fraction, color)
                Spacer(Modifier.height(6.dp))
                Row {
                    Text(statusText(task.status), style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.weight(1f))
                    Text(Fmt.percent(downloaded, total), style = MaterialTheme.typography.labelMedium, color = T.extra.secondaryText)
                }
                if (task.status == TaskStatus.FAILED) Text(errorText(task.errorCode), style = MaterialTheme.typography.bodySmall, color = T.extra.danger)
            }
            if (task.hashOk == false) Text(stringResource(R.string.err_checksum), style = MaterialTheme.typography.bodySmall, color = T.extra.danger)
        }
    }
}

@Composable
private fun subtitle(task: TaskEntity, live: Snapshot?, downloaded: Long, total: Long): String = buildString {
    when (task.status) {
        TaskStatus.COMPLETED -> {
            append(Fmt.bytes(task.totalBytes))
            task.completedAt?.let { append(" · ").append(Fmt.date(it)) }
            if (task.hashOk == true) append(" · ").append(stringResource(R.string.hash_verified))
        }
        TaskStatus.RUNNING -> {
            append(Fmt.bytes(downloaded)).append(" / ").append(Fmt.bytes(total))
            if (live != null) {
                append(" · ").append(Fmt.speed(live.speedBps))
                if (live.etaSeconds >= 0) append(" · ").append(sr(R.string.eta, Fmt.duration(live.etaSeconds)))
                append(" · ").append(sr(R.string.connections_n, live.connections))
            }
        }
        else -> {
            append(Fmt.bytes(downloaded)).append(" / ").append(Fmt.bytes(total))
        }
    }
}

@Composable
private fun PrimaryAction(status: TaskStatus, onClick: () -> Unit) {
    val (icon, label) = when (status) {
        TaskStatus.RUNNING, TaskStatus.QUEUED, TaskStatus.WAITING_NETWORK -> Icons.Rounded.Pause to R.string.action_pause
        TaskStatus.PAUSED -> Icons.Rounded.PlayArrow to R.string.action_resume
        TaskStatus.FAILED -> Icons.Rounded.Refresh to R.string.action_retry
        else -> return
    }
    IconButton(onClick = onClick) { Icon(icon, contentDescription = stringResource(label)) }
}
