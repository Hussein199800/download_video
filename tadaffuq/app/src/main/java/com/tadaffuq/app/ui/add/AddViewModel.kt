package com.tadaffuq.app.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tadaffuq.app.download.DownloadManager
import com.tadaffuq.app.download.ErrorCodes
import com.tadaffuq.app.download.NewTask
import com.tadaffuq.app.download.StorageDirs
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.PackageEntity
import com.tadaffuq.data.SettingsRepository
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.engine.DownloadException
import com.tadaffuq.engine.FileNames
import com.tadaffuq.engine.Hashes
import com.tadaffuq.engine.LinkTools
import com.tadaffuq.engine.ProbeResult
import com.tadaffuq.engine.RequestSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

/** ما يصل من المشاركة أو المتصفح أو الحافظة. */
data class AddRequest(
    val text: String,
    val referer: String? = null,
    val userAgent: String? = null,
    val cookies: String? = null,
    val suggestedName: String? = null,
)

enum class DuplicatePolicy { RENAME, REPLACE, SKIP }

data class Item(
    val url: String,
    val probing: Boolean = true,
    val probe: ProbeResult? = null,
    val errorCode: String? = null,
    val name: String = "",
    val selected: Boolean = true,
    val duplicates: List<TaskEntity> = emptyList(),
)

data class AddState(
    val input: String = "",
    val items: List<Item> = emptyList(),
    val connections: Int = 0,
    val expectedHash: String = "",
    val wifiOnly: Boolean = false,
    val startPaused: Boolean = false,
    val referer: String = "",
    val userAgent: String = "",
    val cookies: String = "",
    val packageName: String = "",
    val duplicatePolicy: DuplicatePolicy = DuplicatePolicy.RENAME,
    val submitting: Boolean = false,
    val done: Boolean = false,
) {
    val single get() = items.size == 1
    val hashValid get() = expectedHash.isBlank() || Hashes.detect(expectedHash) != null
    val anyDuplicate get() = items.any { it.selected && it.duplicates.isNotEmpty() }
    val canSubmit get() = !submitting && hashValid && items.any { it.selected && !it.probing && it.probe != null }
}

@HiltViewModel
class AddViewModel @Inject constructor(
    private val manager: DownloadManager,
    private val db: AppDatabase,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AddState())
    val state: StateFlow<AddState> = _state
    private var parseJob: Job? = null
    private var request: AddRequest? = null

    fun open(req: AddRequest) {
        request = req
        _state.value = AddState(
            input = req.text,
            referer = req.referer.orEmpty(),
            userAgent = req.userAgent.orEmpty(),
            cookies = req.cookies.orEmpty(),
        )
        parse(immediate = true)
    }

    fun setInput(text: String) { _state.update { it.copy(input = text) }; parse(immediate = false) }

    private fun parse(immediate: Boolean) {
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            if (!immediate) delay(500)
            val text = _state.value.input
            val urls = LinkTools.extract(text).flatMap { if (LinkTools.hasSequence(it)) LinkTools.expandSequence(it) else listOf(it) }
                .filter(LinkTools::isSupported).distinct().take(LinkTools.MAX_GENERATED)
            _state.update { s -> s.copy(items = urls.map { u -> s.items.firstOrNull { it.url == u } ?: Item(u) }) }
            probeAll()
        }
    }

    private suspend fun probeAll() {
        val sem = Semaphore(4)
        val s = _state.value
        val settings = settingsRepo.current()
        kotlinx.coroutines.coroutineScope {
            s.items.filter { it.probing }.map { item ->
                async {
                    sem.withPermit {
                        val result = runCatching { manager.probe(spec(item.url)) }
                        val probe = result.getOrNull()
                        val code = (result.exceptionOrNull() as? DownloadException)?.error?.let(ErrorCodes::of)
                            ?: result.exceptionOrNull()?.let { "NETWORK" }
                        val name = when {
                            s.single && request?.suggestedName != null -> FileNames.sanitize(request!!.suggestedName!!)
                            probe != null -> probe.fileName
                            else -> FileNames.resolve(null, item.url, null)
                        }
                        val dir = StorageDirs.dirFor(FileNames.category(name), settings.sortByCategory).path
                        val dups = manager.duplicates(item.url, dir, name)
                        _state.update { st ->
                            st.copy(items = st.items.map {
                                if (it.url == item.url) it.copy(probing = false, probe = probe, errorCode = code, name = name, duplicates = dups) else it
                            })
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun spec(url: String): RequestSpec {
        val s = _state.value
        return RequestSpec(url, referer = s.referer.ifBlank { null }, userAgent = s.userAgent.ifBlank { null }, cookies = s.cookies.ifBlank { null })
    }

    fun reprobe() {
        _state.update { s -> s.copy(items = s.items.map { it.copy(probing = true, errorCode = null) }) }
        viewModelScope.launch { probeAll() }
    }

    fun rename(url: String, name: String) = _state.update { s -> s.copy(items = s.items.map { if (it.url == url) it.copy(name = name) else it }) }
    fun toggle(url: String) = _state.update { s -> s.copy(items = s.items.map { if (it.url == url) it.copy(selected = !it.selected) else it }) }
    fun selectAll(v: Boolean) = _state.update { s -> s.copy(items = s.items.map { it.copy(selected = v) }) }
    fun update(f: (AddState) -> AddState) = _state.update(f)

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            val settings = settingsRepo.current()
            val packageId = if (s.items.count { it.selected } > 1)
                db.links().insertPackage(PackageEntity(name = s.packageName.ifBlank { "حزمة ${com.tadaffuq.app.core.Fmt.date(System.currentTimeMillis())}" }))
            else null
            val toAdd = s.items.filter { it.selected && !it.probing && it.probe != null }.mapNotNull { item ->
                if (item.duplicates.isNotEmpty()) when (s.duplicatePolicy) {
                    DuplicatePolicy.SKIP -> return@mapNotNull null
                    // الاستبدال يتم بموافقة صريحة فقط: يحذف المهمة القديمة وملفها
                    DuplicatePolicy.REPLACE -> manager.delete(item.duplicates.map { it.id }, withFiles = true)
                    DuplicatePolicy.RENAME -> Unit // الاسم الفريد يُختار عند الاكتمال
                }
                val name = FileNames.sanitize(item.name.ifBlank { item.probe?.fileName ?: "download" })
                NewTask(
                    url = item.url,
                    fileName = name,
                    dirPath = StorageDirs.dirFor(FileNames.category(name), settings.sortByCategory).path,
                    probe = item.probe,
                    connections = s.connections,
                    expectedHash = if (s.single) s.expectedHash.ifBlank { null } else null,
                    userAgent = s.userAgent.ifBlank { null },
                    referer = s.referer.ifBlank { null },
                    cookies = s.cookies.ifBlank { null },
                    packageId = packageId,
                    wifiOnly = s.wifiOnly,
                    startPaused = s.startPaused,
                )
            }
            manager.enqueue(toAdd)
            _state.update { it.copy(submitting = false, done = true) }
        }
    }

    fun saveForLater() = viewModelScope.launch {
        db.links().insert(_state.value.items.filter { it.selected }.map { com.tadaffuq.data.SavedLinkEntity(url = it.url, title = it.name.ifBlank { null }) })
        _state.update { it.copy(done = true) }
    }
}
