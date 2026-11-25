package com.tadaffuq.app.ui.home

import com.tadaffuq.app.core.sr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.ui.MainViewModel
import com.tadaffuq.app.ui.components.Card
import com.tadaffuq.app.ui.components.EmptyState
import com.tadaffuq.app.ui.components.LargeTitle
import com.tadaffuq.app.ui.components.LiveScene
import com.tadaffuq.app.ui.components.SectionHeader
import com.tadaffuq.app.ui.components.SpeedChart
import com.tadaffuq.app.ui.components.StatTile
import com.tadaffuq.app.ui.downloads.TaskCard
import com.tadaffuq.app.ui.downloads.TaskDetailSheet
import com.tadaffuq.app.ui.downloads.primary
import com.tadaffuq.app.ui.holdText
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.data.NetKind
import com.tadaffuq.data.TaskStatus

@Composable
fun HomeScreen(vm: MainViewModel, clipboardLink: String?, onAdd: (String?) -> Unit, onOpenDownloads: () -> Unit, padding: PaddingValues) {
    val tasks by vm.tasks.collectAsState()
    val live by vm.live.collectAsState()
    val history by vm.speedHistory.collectAsState()
    val hold by vm.hold.collectAsState()
    val usage by vm.usage.collectAsState()
    val settings by vm.settings.collectAsState()
    var detail by remember { mutableStateOf<Long?>(null) }

    val running = tasks.filter { it.status.isActive }
    val waiting = tasks.count { it.status.isWaiting }
    val speed = live.values.sumOf { it.speedBps }
    val connections = live.values.sumOf { it.connections }
    val today = Fmt.day(System.currentTimeMillis())
    val todayRows = usage.filter { it.day == today }

    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 88.dp)) {
        item {
            LargeTitle(stringResource(R.string.app_name), stringResource(if (running.isEmpty()) R.string.home_idle else R.string.home_active))
        }
        if (clipboardLink != null) item {
            Card(onClick = { onAdd(clipboardLink) }) {
                Row {
                    Icon(Icons.Rounded.ContentPaste, null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.padding(4.dp))
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.clip_found), style = MaterialTheme.typography.titleSmall)
                        Text(clipboardLink, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                    }
                }
            }
        }
        item {
            Card {
                Row(Modifier.fillMaxWidth()) {
                    StatTile(stringResource(R.string.speed_now), Fmt.speed(speed), Modifier.weight(1f), accent = MaterialTheme.colorScheme.secondary)
                    StatTile(stringResource(R.string.active_n), Fmt.iso(running.size.toString()), Modifier.weight(0.7f))
                    StatTile(stringResource(R.string.waiting_n), Fmt.iso(waiting.toString()), Modifier.weight(0.7f))
                }
                hold?.let { Text(holdText(it), style = MaterialTheme.typography.bodySmall, color = T.extra.warning, modifier = Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(10.dp))
                SpeedChart(history, description = sr(R.string.chart_a11y, Fmt.speed(speed)))
                Text(stringResource(R.string.chart_caption), style = MaterialTheme.typography.labelSmall, color = T.extra.secondaryText)
                if (settings.showLiveScene) {
                    Spacer(Modifier.height(8.dp))
                    LiveScene(speed, connections, sr(R.string.scene_a11y, connections))
                }
            }
        }
        item {
            Card {
                Text(stringResource(R.string.today_usage), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    StatTile(stringResource(R.string.total), Fmt.bytes(todayRows.sumOf { it.bytes }), Modifier.weight(1f))
                    StatTile("Wi-Fi", Fmt.bytes(todayRows.filter { it.net == NetKind.WIFI }.sumOf { it.bytes }), Modifier.weight(1f))
                    StatTile(stringResource(R.string.mobile_data), Fmt.bytes(todayRows.filter { it.net == NetKind.CELLULAR }.sumOf { it.bytes }), Modifier.weight(1f))
                }
            }
        }
        if (tasks.isEmpty()) item {
            EmptyState(Icons.Rounded.Download, stringResource(R.string.empty_home), stringResource(R.string.empty_home_text), stringResource(R.string.add_title)) { onAdd(null) }
        } else {
            val focus = (running + tasks.filter { it.status.isWaiting || it.status == TaskStatus.PAUSED }).take(4)
            if (focus.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.in_progress), stringResource(R.string.see_all), onOpenDownloads) }
                items(focus, key = { "f" + it.id }) { t ->
                    TaskCard(t, live[t.id], false, false, { detail = t.id }, { detail = t.id }, { primary(vm, t) })
                }
            }
            val recent = tasks.filter { it.status == TaskStatus.COMPLETED }.sortedByDescending { it.completedAt }.take(3)
            if (recent.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.recent_done)) }
                items(recent, key = { "r" + it.id }) { t -> TaskCard(t, null, false, false, { detail = t.id }, { detail = t.id }, {}) }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    FilledTonalButton(onClick = { onAdd(null) }) { Text(stringResource(R.string.add_title)) }
                }
            }
        }
    }
    detail?.let { id -> tasks.firstOrNull { it.id == id }?.let { TaskDetailSheet(it, live[id], vm) { detail = null } } }
}
