package com.tadaffuq.app.ui.downloads

import com.tadaffuq.app.core.sr
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.download.ErrorCodes
import com.tadaffuq.app.download.StorageDirs
import com.tadaffuq.app.ui.MainViewModel
import com.tadaffuq.app.ui.components.SegmentMap
import com.tadaffuq.app.ui.components.StatTile
import com.tadaffuq.app.ui.doctorText
import com.tadaffuq.app.ui.statusText
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus
import com.tadaffuq.engine.Doctor
import com.tadaffuq.engine.ProbeResult
import com.tadaffuq.engine.Snapshot
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailSheet(t: TaskEntity, live: Snapshot?, vm: MainViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text(t.fileName, style = MaterialTheme.typography.titleLarge)
            Text(statusText(t.status), style = MaterialTheme.typography.labelLarge, color = statusColor(t.status))
            Spacer(Modifier.height(12.dp))

            val total = live?.total ?: t.totalBytes
            val segments = live?.segments
            if (segments != null && total > 0) {
                Text(sr(R.string.segments_title, segments.size), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                SegmentMap(segments, total, T.extra.chartLine, sr(R.string.segments_a11y, segments.size))
                Spacer(Modifier.height(12.dp))
            }

            Row(Modifier.fillMaxWidth()) {
                StatTile(stringResource(R.string.downloaded), "${Fmt.bytes(live?.downloaded ?: t.downloadedBytes)} / ${Fmt.bytes(total)}", Modifier.weight(1f))
                StatTile(stringResource(R.string.speed_now), live?.let { Fmt.speed(it.speedBps) } ?: "—", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                StatTile(stringResource(R.string.speed_avg), Fmt.speed(live?.averageBps ?: t.averageBps), Modifier.weight(1f))
                StatTile(stringResource(R.string.speed_max), Fmt.speed(maxOf(live?.maxBps ?: 0, t.maxBps)), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                StatTile(stringResource(R.string.time_left), live?.etaSeconds?.let { Fmt.duration(it) } ?: "—", Modifier.weight(1f))
                StatTile(stringResource(R.string.connections), live?.connections?.let { Fmt.iso(it.toString()) } ?: if (t.connections == 0) stringResource(R.string.auto) else Fmt.iso(t.connections.toString()), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                StatTile(stringResource(R.string.retries), Fmt.iso(t.retries.toString()), Modifier.weight(1f))
                StatTile(stringResource(R.string.priority), Fmt.iso(t.priority.toString()), Modifier.weight(1f))
            }
            if (t.speedLimitBps > 0) StatTile(stringResource(R.string.speed_limit), Fmt.speed(t.speedLimitBps))

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.link), style = MaterialTheme.typography.titleSmall)
            Text(t.url, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
            if (t.finalUrl != null && t.finalUrl != t.url) {
                Text(stringResource(R.string.final_link), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                Text(t.finalUrl.orEmpty(), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
            }
            Text(sr(R.string.saved_in, t.filePath ?: t.dirPath), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText, modifier = Modifier.padding(top = 4.dp))
            t.actualHash?.let { Text("SHA/MD5: ${Fmt.iso(it)}", style = MaterialTheme.typography.bodySmall, color = if (t.hashOk == false) T.extra.danger else T.extra.secondaryText) }

            // طبيب التحميل: قواعد ثابتة على قراءات المهمة الحقيقية
            val probe = if (t.totalBytes != 0L || t.finalUrl != null) ProbeResult(t.finalUrl ?: t.url, t.totalBytes, t.acceptRanges, t.etag, t.lastModified, t.fileName, t.mimeType) else null
            val findings = Doctor.diagnose(
                Doctor.Input(
                    probe = probe.takeIf { t.finalUrl != null },
                    error = ErrorCodes.toError(t.errorCode),
                    retries = t.retries, networkRetries = t.networkRetries, serverRetries = t.serverRetries,
                    averageBps = live?.averageBps ?: t.averageBps, connections = live?.connections ?: 0,
                    freeBytes = runCatching { File(t.dirPath).let { if (it.exists()) it else it.parentFile }?.usableSpace ?: -1 }.getOrDefault(-1),
                    remainingBytes = if (total > 0) total - (live?.downloaded ?: t.downloadedBytes) else -1,
                ),
            ).filter { t.status != TaskStatus.COMPLETED || it == Doctor.Finding.CHECKSUM_MISMATCH }
            if (findings.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.doctor_title), style = MaterialTheme.typography.titleSmall)
                findings.forEach { f ->
                    val (what, fix) = doctorText(f)
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(what, style = MaterialTheme.typography.bodyMedium)
                        Text(fix, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (t.status) {
                    TaskStatus.RUNNING, TaskStatus.QUEUED, TaskStatus.WAITING_NETWORK ->
                        Action(Icons.Rounded.Pause, stringResource(R.string.action_pause)) { vm.pause(listOf(t.id)) }
                    TaskStatus.PAUSED, TaskStatus.FAILED ->
                        Action(Icons.Rounded.PlayArrow, stringResource(if (t.status == TaskStatus.FAILED) R.string.action_retry else R.string.action_resume)) { vm.resume(listOf(t.id)) }
                    else -> Unit
                }
                if (t.status == TaskStatus.COMPLETED) {
                    val file = t.filePath?.let(::File)
                    if (file?.exists() == true) {
                        Action(Icons.Rounded.OpenInNew, stringResource(R.string.action_open)) {
                            try { ctx.startActivity(StorageDirs.openIntent(ctx, file)) } catch (e: ActivityNotFoundException) {
                                Toast.makeText(ctx, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
                            }
                        }
                        Action(Icons.Rounded.Share, stringResource(R.string.action_share)) { ctx.startActivity(StorageDirs.shareIntent(ctx, listOf(file))) }
                    }
                } else {
                    Action(Icons.Rounded.ArrowUpward, stringResource(R.string.priority_up)) { vm.setPriority(t.id, t.priority + 1) }
                    Action(Icons.Rounded.ArrowDownward, stringResource(R.string.priority_down)) { vm.setPriority(t.id, t.priority - 1) }
                    Action(Icons.Rounded.Speed, stringResource(R.string.speed_limit)) { dialog = "limit" }
                    Action(Icons.Rounded.Link, stringResource(R.string.refresh_link)) { dialog = "url" }
                    Action(Icons.Rounded.RestartAlt, stringResource(R.string.action_restart)) { vm.restart(t.id) }
                }
                Action(Icons.Rounded.ContentCopy, stringResource(R.string.copy_link)) {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("url", t.url))
                }
                Action(Icons.Rounded.Delete, stringResource(R.string.action_delete)) { dialog = "delete" }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    when (dialog) {
        "limit" -> SpeedLimitDialog(t.speedLimitBps, { dialog = null }) { vm.setSpeedLimit(t.id, it); dialog = null }
        "url" -> UrlDialog(t.url, { dialog = null }) { vm.updateUrl(t.id, it); dialog = null }
        "delete" -> DeleteDialog(1, t.status == TaskStatus.COMPLETED, { dialog = null }) { withFiles -> vm.delete(listOf(t.id), withFiles); dialog = null; onDismiss() }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) }, leadingIcon = { Icon(icon, null) })
}

@Composable
fun SpeedLimitDialog(currentBps: Long, onDismiss: () -> Unit, onSet: (Long) -> Unit) {
    var text by remember { mutableStateOf(if (currentBps > 0) (currentBps / 1024).toString() else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speed_limit)) },
        text = {
            Column {
                Text(stringResource(R.string.speed_limit_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.kbps)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSet(((Fmt.parseNumber(text) ?: 0.0) * 1024).toLong().coerceAtLeast(0)) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun UrlDialog(current: String, onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.refresh_link)) },
        text = {
            Column {
                Text(stringResource(R.string.refresh_link_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it.trim() }, maxLines = 5)
            }
        },
        confirmButton = { TextButton(onClick = { onSet(text) }, enabled = com.tadaffuq.engine.LinkTools.isSupported(text)) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
