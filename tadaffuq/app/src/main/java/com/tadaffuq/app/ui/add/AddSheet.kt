package com.tadaffuq.app.ui.add

import com.tadaffuq.app.core.sr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.ui.components.Hairline
import com.tadaffuq.app.ui.components.SliderRow
import com.tadaffuq.app.ui.errorText
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.engine.FileNames

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(request: AddRequest, onDismiss: () -> Unit, vm: AddViewModel = hiltViewModel()) {
    LaunchedEffect(request) { vm.open(request) }
    val s by vm.state.collectAsState()
    LaunchedEffect(s.done) { if (s.done) onDismiss() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var advanced by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                Text(stringResource(R.string.add_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp))
                Text(stringResource(R.string.add_hint), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                OutlinedTextField(
                    value = s.input, onValueChange = vm::setInput,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    label = { Text(stringResource(R.string.add_links_label)) },
                    minLines = 2, maxLines = 6,
                )
            }
            if (s.items.isEmpty() && s.input.isNotBlank()) item {
                Text(stringResource(R.string.add_no_links), color = T.extra.secondaryText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
            }
            if (s.items.size > 1) item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sr(R.string.add_found, s.items.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    TextButton(onClick = { vm.selectAll(s.items.any { !it.selected }) }) {
                        Text(stringResource(if (s.items.any { !it.selected }) R.string.select_all else R.string.select_none))
                    }
                }
                OutlinedTextField(
                    value = s.packageName, onValueChange = { v -> vm.update { it.copy(packageName = v) } },
                    label = { Text(stringResource(R.string.add_package_name)) },
                    supportingText = { Text(stringResource(R.string.add_package_desc)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), maxLines = 2,
                )
            }
            items(s.items.take(500), key = { it.url }) { item -> ItemRow(item, s.single, vm) }
            if (s.items.size > 500) item {
                Text(sr(R.string.add_more_hidden, s.items.size - 500), color = T.extra.secondaryText, modifier = Modifier.padding(20.dp))
            }

            if (s.anyDuplicate) item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ContentCopy, null, tint = T.extra.warning, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.dup_title), style = MaterialTheme.typography.titleSmall, color = T.extra.warning)
                    }
                    Text(stringResource(R.string.dup_desc), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        listOf(DuplicatePolicy.RENAME to R.string.dup_rename, DuplicatePolicy.SKIP to R.string.dup_skip, DuplicatePolicy.REPLACE to R.string.dup_replace).forEach { (p, label) ->
                            FilterChip(selected = s.duplicatePolicy == p, onClick = { vm.update { it.copy(duplicatePolicy = p) } }, label = { Text(stringResource(label)) })
                        }
                    }
                    if (s.duplicatePolicy == DuplicatePolicy.REPLACE) Text(stringResource(R.string.dup_replace_warn), color = T.extra.danger, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (s.items.isNotEmpty()) item {
                Hairline()
                SliderRow(
                    title = stringResource(R.string.add_connections),
                    description = stringResource(R.string.add_connections_desc),
                    value = s.connections.toFloat(), range = 0f..32f, steps = 31,
                    label = if (s.connections == 0) stringResource(R.string.auto) else Fmt.iso(s.connections.toString()),
                    onChange = { v -> vm.update { it.copy(connections = v.toInt()) } },
                )
                SwitchLine(stringResource(R.string.add_wifi_only), stringResource(R.string.add_wifi_only_desc), s.wifiOnly) { v -> vm.update { it.copy(wifiOnly = v) } }
                SwitchLine(stringResource(R.string.add_start_paused), stringResource(R.string.add_start_paused_desc), s.startPaused) { v -> vm.update { it.copy(startPaused = v) } }
                TextButton(onClick = { advanced = !advanced }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(if (advanced) R.string.hide_advanced else R.string.show_advanced))
                }
            }
            if (advanced && s.items.isNotEmpty()) item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (s.single) OutlinedTextField(
                        value = s.expectedHash, onValueChange = { v -> vm.update { it.copy(expectedHash = v.trim()) } },
                        label = { Text(stringResource(R.string.add_hash)) },
                        supportingText = { Text(stringResource(if (s.hashValid) R.string.add_hash_desc else R.string.add_hash_invalid)) },
                        isError = !s.hashValid, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    )
                    OutlinedTextField(s.referer, { v -> vm.update { it.copy(referer = v.trim()) } }, label = { Text("Referer") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                    OutlinedTextField(s.userAgent, { v -> vm.update { it.copy(userAgent = v) } }, label = { Text("User-Agent") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                    OutlinedTextField(
                        s.cookies, { v -> vm.update { it.copy(cookies = v) } }, label = { Text("Cookies") },
                        supportingText = { Text(stringResource(R.string.add_cookies_desc)) }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    )
                    OutlinedButton(onClick = vm::reprobe) { Text(stringResource(R.string.add_reprobe)) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = vm::saveForLater, enabled = s.items.any { it.selected }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.add_save_later))
                    }
                    Button(onClick = vm::submit, enabled = s.canSubmit, modifier = Modifier.weight(1.4f).heightIn(min = 48.dp)) {
                        Text(stringResource(if (s.startPaused) R.string.add_to_queue else R.string.add_start))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SwitchLine(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun ItemRow(item: Item, single: Boolean, vm: AddViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!single) Checkbox(item.selected, { vm.toggle(item.url) })
            Column(Modifier.weight(1f)) {
                if (single && item.probe != null) {
                    OutlinedTextField(
                        value = item.name, onValueChange = { vm.rename(item.url, it) },
                        label = { Text(stringResource(R.string.add_file_name)) }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    )
                } else {
                    Text(item.name.ifBlank { item.url }, style = MaterialTheme.typography.bodyLarge)
                }
                Text(item.url, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            when {
                item.probing -> {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_probing), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                }
                item.probe != null -> {
                    val p = item.probe
                    Icon(if (p.resumable) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = if (p.resumable) T.extra.success else T.extra.warning, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    val parts = buildList {
                        add(Fmt.bytes(p.totalBytes))
                        p.mimeType?.let { add(Fmt.iso(it)) }
                        add(stringResource(if (p.resumable) R.string.resumable else R.string.not_resumable))
                        java.net.URI(p.finalUrl).host?.let { add(Fmt.iso(it)) }
                        if (p.finalUrl.startsWith("http://")) add(stringResource(R.string.insecure_http))
                    }
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                }
                else -> {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = T.extra.danger, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(errorText(item.errorCode), style = MaterialTheme.typography.bodySmall, color = T.extra.danger)
                }
            }
        }
        if (item.probe != null && FileNames.isExecutable(item.name)) {
            Text(stringResource(R.string.exe_warning), style = MaterialTheme.typography.bodySmall, color = T.extra.warning, modifier = Modifier.padding(top = 2.dp))
        }
        if (item.duplicates.isNotEmpty()) {
            Text(stringResource(R.string.dup_found_inline), style = MaterialTheme.typography.bodySmall, color = T.extra.warning)
        }
    }
}
