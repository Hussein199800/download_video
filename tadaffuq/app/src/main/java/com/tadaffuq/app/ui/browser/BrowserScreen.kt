package com.tadaffuq.app.ui.browser

import com.tadaffuq.app.core.sr
import android.content.MutableContextWrapper
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.ui.add.AddRequest
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.BookmarkEntity
import com.tadaffuq.data.HistoryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(private val db: AppDatabase) : ViewModel() {
    val bookmarks = db.browser().bookmarks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = db.browser().history().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun visit(url: String, title: String) = viewModelScope.launch { db.browser().visit(HistoryEntity(url = url, title = title)) }
    fun toggleBookmark(url: String, title: String, exists: Boolean) = viewModelScope.launch {
        if (exists) db.browser().deleteBookmark(url) else db.browser().upsertBookmark(BookmarkEntity(url = url, title = title.ifBlank { url }))
    }
    fun clearHistory() = viewModelScope.launch { db.browser().clearHistory() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(padding: PaddingValues, onDownload: (AddRequest) -> Unit, blockPopups: Boolean, blockTrackers: Boolean, desktop: Boolean, vm: BrowserViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    LaunchedEffect(blockPopups, blockTrackers, desktop) {
        BrowserTabs.blockPopups = blockPopups; BrowserTabs.blockTrackers = blockTrackers; BrowserTabs.desktopMode = desktop
        BrowserTabs.applySettings()
    }
    DisposableEffect(Unit) {
        BrowserTabs.onDownload = onDownload
        BrowserTabs.onVisit = { u, t -> vm.visit(u, t) }
        if (BrowserTabs.tabs.isEmpty()) BrowserTabs.open(ctx)
        onDispose { BrowserTabs.onDownload = null; BrowserTabs.onVisit = null }
    }
    val tab = BrowserTabs.tabs.getOrNull(BrowserTabs.current.value) ?: return
    val bookmarks by vm.bookmarks.collectAsState()
    var address by remember(tab.id, tab.url.value) { mutableStateOf(tab.url.value) }
    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var findText by remember { mutableStateOf<String?>(null) }
    var captured by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    BackHandler(enabled = tab.canBack.value) { tab.webView.goBack() }

    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (tab.private) Icon(Icons.Rounded.VisibilityOff, stringResource(R.string.private_tab), tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 6.dp))
                    OutlinedTextField(
                        value = address, onValueChange = { address = it }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.address_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { tab.webView.loadUrl(BrowserTabs.toUrl(address)) }),
                        shape = MaterialTheme.shapes.extraLarge,
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(onClick = { sheet = "tabs" }) {
                        Box(contentAlignment = Alignment.Center) { Text(Fmt.iso(BrowserTabs.tabs.size.toString()), style = MaterialTheme.typography.labelLarge) }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(menu, { menu = false }) {
                            val isBookmarked = bookmarks.any { it.url == tab.url.value }
                            DropdownMenuItem(text = { Text(stringResource(if (isBookmarked) R.string.bookmark_remove else R.string.bookmark_add)) }, leadingIcon = { Icon(if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null) },
                                onClick = { vm.toggleBookmark(tab.url.value, tab.title.value, isBookmarked); menu = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks)) }, onClick = { sheet = "bookmarks"; menu = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.history)) }, onClick = { sheet = "history"; menu = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.find_in_page)) }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, onClick = { findText = ""; menu = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.capture_links)) }, leadingIcon = { Icon(Icons.Rounded.Download, null) }, onClick = {
                                menu = false
                                tab.webView.evaluateJavascript(BrowserTabs.COLLECT_JS) { json ->
                                    captured = parseLinks(json)
                                }
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.new_tab)) }, leadingIcon = { Icon(Icons.Rounded.Add, null) }, onClick = { BrowserTabs.open(ctx); menu = false })
                            DropdownMenuItem(text = { Text(stringResource(R.string.new_private_tab)) }, leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) }, onClick = { BrowserTabs.open(ctx, private = true); menu = false })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { tab.webView.goBack() }, enabled = tab.canBack.value) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                    IconButton(onClick = { tab.webView.goForward() }, enabled = tab.canForward.value) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.forward)) }
                    IconButton(onClick = { tab.webView.reload() }) { Icon(Icons.Rounded.Refresh, stringResource(R.string.reload)) }
                    Text(
                        if (tab.blocked.value > 0) sr(R.string.trackers_blocked, tab.blocked.value) else tab.title.value,
                        style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                }
                if (tab.progress.value in 1..99) LinearProgressIndicator(progress = { tab.progress.value / 100f }, modifier = Modifier.fillMaxWidth())
                findText?.let { q ->
                    Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(q, { findText = it; tab.webView.findAllAsync(it) }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text(stringResource(R.string.find_in_page)) })
                        IconButton(onClick = { tab.webView.findNext(false) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.previous)) }
                        IconButton(onClick = { tab.webView.findNext(true) }) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.next)) }
                        IconButton(onClick = { tab.webView.clearMatches(); findText = null }) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) }
                    }
                }
            }
        }
        key(tab.id) {
            AndroidView(
                factory = { c ->
                    (tab.webView.context as MutableContextWrapper).baseContext = c
                    (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                    tab.webView
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { (it.parent as? ViewGroup)?.removeView(it) },
            )
        }
    }

    when (sheet) {
        "tabs" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            LazyColumn(Modifier.navigationBarsPadding()) {
                items(BrowserTabs.tabs.toList(), key = { it.id }) { t ->
                    Row(Modifier.fillMaxWidth().clickable { BrowserTabs.current.value = BrowserTabs.tabs.indexOf(t); sheet = null }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (t.private) Icon(Icons.Rounded.VisibilityOff, null, tint = MaterialTheme.colorScheme.secondary)
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(t.title.value.ifBlank { stringResource(R.string.new_tab) }, style = MaterialTheme.typography.bodyLarge)
                            Text(t.url.value, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                        }
                        IconButton(onClick = { BrowserTabs.close(t); if (BrowserTabs.tabs.isEmpty()) BrowserTabs.open(ctx) }) { Icon(Icons.Rounded.Close, stringResource(R.string.close_tab)) }
                    }
                }
                item {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { BrowserTabs.open(ctx); sheet = null }) { Text(stringResource(R.string.new_tab)) }
                        TextButton(onClick = { BrowserTabs.open(ctx, private = true); sheet = null }) { Text(stringResource(R.string.new_private_tab)) }
                    }
                    Text(stringResource(R.string.private_note), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
        "bookmarks" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            LinkList(bookmarks.map { it.title to it.url }, stringResource(R.string.no_bookmarks)) { tab.webView.loadUrl(it); sheet = null }
        }
        "history" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            val history by vm.history.collectAsState()
            TextButton(onClick = vm::clearHistory, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.clear_history)) }
            LinkList(history.map { it.title to it.url }, stringResource(R.string.no_history)) { tab.webView.loadUrl(it); sheet = null }
        }
    }

    captured?.let { list ->
        CapturedLinksDialog(list, onDismiss = { captured = null }) { urls ->
            captured = null
            onDownload(AddRequest(urls.joinToString("\n"), referer = tab.url.value, userAgent = tab.webView.settings.userAgentString))
        }
    }
}

private fun parseLinks(json: String?): List<Pair<String, String>> = runCatching {
    // evaluateJavascript يعيد سلسلة JSON مغلّفة بعلامات اقتباس
    val inner = org.json.JSONTokener(json).nextValue() as String
    val arr = JSONArray(inner)
    (0 until arr.length()).map { arr.getJSONArray(it).let { a -> a.getString(0) to a.getString(1) } }
}.getOrDefault(emptyList())

@Composable
private fun LinkList(items: List<Pair<String, String>>, empty: String, onOpen: (String) -> Unit) {
    if (items.isEmpty()) Text(empty, color = T.extra.secondaryText, modifier = Modifier.padding(24.dp))
    LazyColumn(Modifier.navigationBarsPadding()) {
        items(items) { (title, url) ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(url) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(title.ifBlank { url }, style = MaterialTheme.typography.bodyLarge)
                Text(url, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
            }
        }
    }
}

@Composable
private fun CapturedLinksDialog(all: List<Pair<String, String>>, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    val files = all.filter { (u, _) -> ext(u) in BrowserTabs.fileExtensions }
    val exts = files.map { ext(it.first) }.distinct().sorted()
    var filter by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf(files.map { it.first }.toSet()) }
    val shown = files.filter { filter == null || ext(it.first) == filter }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(sr(R.string.captured_title, files.size)) },
        text = {
            Column {
                if (files.isEmpty()) Text(stringResource(R.string.captured_none))
                else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(filter == null, { filter = null }, label = { Text(stringResource(R.string.f_all)) })
                        exts.forEach { e -> FilterChip(filter == e, { filter = e }, label = { Text(e) }) }
                    }
                    LazyColumn(Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(max = 360.dp)) {
                        items(shown, key = { it.first }) { (u, text) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(u in chosen, { chosen = if (it) chosen + u else chosen - u })
                                Column {
                                    Text(text.ifBlank { u.substringAfterLast('/') }, style = MaterialTheme.typography.bodyMedium)
                                    Text(u, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(shown.map { it.first }.filter { it in chosen }) }, enabled = shown.any { it.first in chosen }) { Text(stringResource(R.string.add_title)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}


private fun ext(url: String) = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()
