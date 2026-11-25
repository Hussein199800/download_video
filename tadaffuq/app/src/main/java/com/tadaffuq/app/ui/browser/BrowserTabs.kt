package com.tadaffuq.app.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.tadaffuq.app.ui.add.AddRequest
import java.io.ByteArrayInputStream

/** تبويب متصفح بحالة قابلة للمراقبة من Compose. */
class BrowserTab(val id: Long, val private: Boolean, val webView: WebView) {
    val url = mutableStateOf("")
    val title = mutableStateOf("")
    val progress = mutableStateOf(0)
    val canBack = mutableStateOf(false)
    val canForward = mutableStateOf(false)
    val blocked = mutableStateOf(0)
}

/**
 * التبويبات تعيش خارج الشاشة (بسياق قابل للتبديل) حتى لا تضيع عند التنقل بين تبويبات التطبيق.
 * الحجب اختياري: النوافذ المنبثقة، وقائمة صغيرة معروفة من نطاقات التتبع.
 */
object BrowserTabs {
    val tabs = mutableStateListOf<BrowserTab>()
    val current = mutableStateOf(0)
    private var nextId = 1L

    @Volatile var blockPopups = true
    @Volatile var blockTrackers = false
    @Volatile var desktopMode = false
    var onDownload: ((AddRequest) -> Unit)? = null
    var onVisit: ((String, String) -> Unit)? = null

    private val trackerHosts = setOf(
        "google-analytics.com", "googletagmanager.com", "doubleclick.net", "googlesyndication.com", "adservice.google.com",
        "facebook.net", "connect.facebook.net", "scorecardresearch.com", "hotjar.com", "mixpanel.com", "segment.io",
        "amplitude.com", "criteo.com", "taboola.com", "outbrain.com", "adnxs.com", "quantserve.com", "moatads.com",
    )

    fun isTracker(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return trackerHosts.any { h == it || h.endsWith(".$it") }
    }

    const val HOME = "https://duckduckgo.com/"

    fun toUrl(input: String): String {
        val t = input.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (!t.contains(' ') && t.contains('.') && !t.endsWith('.')) return "https://$t"
        return "https://duckduckgo.com/?q=" + Uri.encode(t)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun open(context: Context, url: String = HOME, private: Boolean = false): BrowserTab {
        val wv = WebView(MutableContextWrapper(context.applicationContext))
        val tab = BrowserTab(nextId++, private, wv)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = !private
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = !blockPopups
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = if (private) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            setGeolocationEnabled(false)
            saveFormData = false
        }
        applyDesktop(wv)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                return scheme != "http" && scheme != "https" // لا مخططات أخرى (intent:, file:, javascript:) من الصفحات
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (blockTrackers && !request.isForMainFrame && isTracker(request.url.host)) {
                    tab.blocked.value++
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { sync(tab) }
            override fun onPageFinished(view: WebView, url: String) {
                sync(tab)
                if (!private) onVisit?.invoke(url, view.title.orEmpty())
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = sync(tab)
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { tab.progress.value = newProgress }
            override fun onReceivedTitle(view: WebView, title: String?) { tab.title.value = title.orEmpty() }
        }
        wv.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
            onDownload?.invoke(
                AddRequest(
                    text = dlUrl,
                    referer = tab.url.value.ifBlank { null },
                    userAgent = userAgent,
                    cookies = CookieManager.getInstance().getCookie(dlUrl),
                    suggestedName = URLUtil.guessFileName(dlUrl, contentDisposition, mimeType),
                ),
            )
        }
        wv.loadUrl(url)
        tabs.add(tab)
        current.value = tabs.lastIndex
        return tab
    }

    private fun sync(tab: BrowserTab) {
        tab.url.value = tab.webView.url.orEmpty()
        tab.title.value = tab.webView.title.orEmpty()
        tab.canBack.value = tab.webView.canGoBack()
        tab.canForward.value = tab.webView.canGoForward()
    }

    fun applyDesktop(wv: WebView) {
        val base = WebSettings.getDefaultUserAgent(wv.context)
        wv.settings.userAgentString = if (desktopMode)
            base.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "")
        else base
    }

    fun close(tab: BrowserTab) {
        val i = tabs.indexOf(tab)
        tabs.remove(tab)
        if (tab.private) tab.webView.clearCache(true)
        tab.webView.destroy()
        current.value = (if (i <= current.value) current.value - 1 else current.value).coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    }

    fun applySettings() {
        tabs.forEach {
            it.webView.settings.javaScriptCanOpenWindowsAutomatically = !blockPopups
            applyDesktop(it.webView)
        }
    }

    /** امتدادات الملفات العامة المقبولة لالتقاط الروابط؛ بلا مسارات بث وسائط. */
    val fileExtensions = listOf(
        "zip", "rar", "7z", "tar", "gz", "tgz", "xz", "bz2", "zst", "iso", "img", "bin", "apk", "apks", "xapk", "exe", "msi", "dmg", "deb", "rpm",
        "appimage", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "epub", "txt", "csv", "json", "gguf", "safetensors", "onnx", "jar",
    )

    const val COLLECT_JS = "(function(){var o=[];var s={};document.querySelectorAll('a[href]').forEach(function(a){var h=a.href;if(!s[h]){s[h]=1;o.push([h,(a.textContent||'').trim().slice(0,120)]);}});return JSON.stringify(o.slice(0,2000));})()"
}
