package com.tadaffuq.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tadaffuq.app.download.DownloadManager
import com.tadaffuq.app.ui.MainViewModel
import com.tadaffuq.app.ui.add.AddRequest
import com.tadaffuq.app.ui.add.AddSheet
import com.tadaffuq.app.ui.browser.BrowserScreen
import com.tadaffuq.app.ui.downloads.DownloadsScreen
import com.tadaffuq.app.ui.files.FilesScreen
import com.tadaffuq.app.ui.home.HomeScreen
import com.tadaffuq.app.ui.more.AboutScreen
import com.tadaffuq.app.ui.more.LinksScreen
import com.tadaffuq.app.ui.more.MoreScreen
import com.tadaffuq.app.ui.more.ReportsScreen
import com.tadaffuq.app.ui.more.SettingsScreen
import com.tadaffuq.app.ui.more.TrashScreen
import com.tadaffuq.app.ui.theme.TadaffuqTheme
import com.tadaffuq.engine.LinkTools
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var manager: DownloadManager
    private val vm: MainViewModel by viewModels()
    private var pending by mutableStateOf<AddRequest?>(null)
    private var jumpTo by mutableStateOf<String?>(null)
    private var clipboardLink by mutableStateOf<String?>(null)
    private var lastClip: String? = null

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val settings by vm.settings.collectAsState()
            TadaffuqTheme(settings.theme) { App() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // اقتراح الرابط المنسوخ فقط إن فعّله المستخدم؛ Android 10+ يسمح بقراءة الحافظة والتطبيق في المقدمة.
        if (vm.settings.value.suggestClipboard) window.decorView.post {
            val text = runCatching { getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
            val link = text?.let { LinkTools.extract(it).firstOrNull(LinkTools::isSupported) }
            if (link != null && link != lastClip) { clipboardLink = link; lastClip = link }
        }
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { add(AddRequest(it)) }
            Intent.ACTION_VIEW -> intent.dataString?.let { add(AddRequest(it)) }
            ACTION_ADD -> add(AddRequest(""))
            ACTION_DOWNLOADS -> jumpTo = "downloads"
            ACTION_RESUME_ALL -> { jumpTo = "downloads"; lifecycleScope.launch { manager.resumeAll() } }
        }
    }

    private fun add(req: AddRequest) {
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        pending = req
    }

    private data class Tab(val route: String, val label: Int, val icon: ImageVector)

    @Composable
    private fun App() {
        val nav = rememberNavController()
        val settings by vm.settings.collectAsState()
        val tabs = listOf(
            Tab("home", R.string.tab_home, Icons.Rounded.Home),
            Tab("browser", R.string.tab_browser, Icons.Rounded.Language),
            Tab("downloads", R.string.tab_downloads, Icons.Rounded.Download),
            Tab("files", R.string.tab_files, Icons.Rounded.Folder),
            Tab("more", R.string.tab_more, Icons.Rounded.MoreHoriz),
        )
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        val tabRoute = when (route) { "settings", "reports", "links", "trash", "about" -> "more"; else -> route }
        fun go(r: String) = nav.navigate(r) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        LaunchedEffect(jumpTo) { jumpTo?.let { go(it); jumpTo = null } }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = tabRoute == t.route,
                            onClick = { go(t.route) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(stringResource(t.label), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            },
            floatingActionButton = {
                if (tabRoute == "home" || tabRoute == "downloads") ExtendedFloatingActionButton(
                    onClick = { add(AddRequest("")) },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text(stringResource(R.string.add_short)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            },
        ) { inner ->
            val padding = PaddingValues(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), bottom = inner.calculateBottomPadding())
            NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize()) {
                composable("home") {
                    HomeScreen(vm, clipboardLink, onAdd = { link -> clipboardLink = null; add(AddRequest(link.orEmpty())) }, onOpenDownloads = { go("downloads") }, padding = padding)
                }
                composable("browser") {
                    BrowserScreen(PaddingValues(top = padding.calculateTopPadding(), bottom = inner.calculateBottomPadding()), onDownload = { add(it) },
                        blockPopups = settings.blockPopups, blockTrackers = settings.blockTrackers, desktop = settings.desktopMode)
                }
                composable("downloads") { DownloadsScreen(vm, { add(AddRequest("")) }, padding) }
                composable("files") { FilesScreen(vm, padding) }
                composable("more") { MoreScreen(padding) { nav.navigate(it) } }
                composable("settings") { SettingsScreen(vm, padding) { nav.popBackStack() } }
                composable("reports") { ReportsScreen(vm, padding) { nav.popBackStack() } }
                composable("links") { LinksScreen(vm, padding, { nav.popBackStack() }) { add(it) } }
                composable("trash") { TrashScreen(vm, padding) { nav.popBackStack() } }
                composable("about") { AboutScreen(padding) { nav.popBackStack() } }
            }
        }
        pending?.let { req -> AddSheet(req, onDismiss = { pending = null }) }
    }

    companion object {
        const val ACTION_ADD = "com.tadaffuq.app.ADD"
        const val ACTION_DOWNLOADS = "com.tadaffuq.app.DOWNLOADS"
        const val ACTION_RESUME_ALL = "com.tadaffuq.app.RESUME_ALL_UI"
    }
}
