package com.tadaffuq.engine

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class ProxyConfig(
    val type: Proxy.Type,
    val host: String,
    val port: Int,
    val user: String? = null,
    val password: String? = null,
)

/** عميل HTTP واحد مشترك: يعيد استخدام الاتصالات (keep-alive) ولا يعطّل التحقق من TLS أبداً. */
object HttpClients {
    fun create(
        proxy: ProxyConfig? = null,
        connectTimeoutSec: Long = 15,
        readTimeoutSec: Long = 30,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(DownloadConfig.MAX_CONNECTIONS * 2, 5, TimeUnit.MINUTES))
        .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .apply {
            if (proxy != null) {
                proxy(Proxy(proxy.type, InetSocketAddress.createUnresolved(proxy.host, proxy.port)))
                if (proxy.user != null && proxy.type == Proxy.Type.HTTP) {
                    val cred = Credentials.basic(proxy.user, proxy.password.orEmpty())
                    proxyAuthenticator(Authenticator { _, response ->
                        if (response.request.header("Proxy-Authorization") != null) null
                        else response.request.newBuilder().header("Proxy-Authorization", cred).build()
                    })
                }
            }
        }
        .build()
}

internal fun RequestSpec.toRequest(url: String = this.url): Request.Builder = Request.Builder().url(url).apply {
    headers.forEach { (k, v) -> header(k, v) }
    userAgent?.let { header("User-Agent", it) }
    referer?.let { header("Referer", it) }
    cookies?.let { header("Cookie", it) }
    // ضغط النقل يفسد حساب المواضع في الملف.
    header("Accept-Encoding", "identity")
}

/** ينفّذ الطلب ويلغيه فوراً إن أُلغي الـ coroutine (الإيقاف المؤقت). */
internal suspend fun OkHttpClient.executeCancellable(request: Request): Response {
    val call = newCall(request)
    val job = currentCoroutineContext()[Job]
    val handle = job?.invokeOnCompletion { if (it != null) call.cancel() }
    try {
        currentCoroutineContext().ensureActive()
        return call.execute()
    } finally {
        handle?.dispose()
    }
}

/** RFC 9110 §14.4: «bytes a-b/total». */
internal data class ContentRange(val first: Long, val last: Long, val total: Long?)

internal fun parseContentRange(value: String?): ContentRange? {
    if (value == null) return null
    val (unit, spec) = value.trim().split(' ', limit = 2).takeIf { it.size == 2 } ?: return null
    if (!unit.equals("bytes", true)) return null
    val span = spec.substringBefore('/')
    val size = spec.substringAfter('/', "*")
    val first = span.substringBefore('-').toLongOrNull() ?: return null
    val last = span.substringAfter('-').toLongOrNull() ?: return null
    return ContentRange(first, last, size.toLongOrNull())
}
