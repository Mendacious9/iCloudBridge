package cn.yunqiao.icloud.http

import cn.yunqiao.icloud.ICloudConstants
import cn.yunqiao.icloud.ICloudNetworkException
import cn.yunqiao.icloud.ICloudProtocolException
import cn.yunqiao.icloud.ICloudRegion
import cn.yunqiao.icloud.model.SessionState
import cn.yunqiao.icloud.store.SessionStore
import cn.yunqiao.icloud.store.StoredCookie
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.ConnectionPool
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 一次 HTTP 往返的结果（只关心状态码 / 响应头 / 文本体，body 不重复读取） */
class HttpResponse(
    val status: Int,
    val body: String,
    val headers: Headers,
) {
    val isSuccessful: Boolean get() = status in 200..299

    fun header(name: String): String? = headers.get(name)

    /** 有些接口（如 drivews）顶层是数组，不能强行当对象解析 */
    fun jsonElement(): JsonElement = try {
        json.parseToJsonElement(body.ifBlank { "null" })
    } catch (e: Exception) {
        throw ICloudProtocolException("响应不是合法 JSON：${body.take(200)}", e)
    }

    fun jsonObject(): JsonObject {
        val element = jsonElement()
        return element as? JsonObject
            ?: throw ICloudProtocolException("响应不是 JSON 对象：${body.take(200)}")
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/**
 * iCloud Web API 的 HTTP 底座。
 *
 * 三件事必须做对，否则后面全盘皆输：
 * 1. **Cookie 跨进程持久化** —— 苹果的 `X-APPLE-WEBAUTH-*` 系列 Cookie 才是真正的登录态，
 *    令牌（sessionToken/trustToken）只是拿到它的凭证。
 * 2. **响应头捕获** —— scnt / sessionId / sessionToken / trustToken 都是在登录过程中
 *    通过响应头一步步下发的，必须每收到一个响应就抓一次并落盘。
 * 3. **错误统一转译** —— 网络异常与非 2xx 分开抛，让上层 UI 能区分「没网」和「被拒」。
 */
class ICloudHttp(
    val appleId: String,
    val region: ICloudRegion,
    private val store: SessionStore,
    baseClient: OkHttpClient = SHARED_CLIENT,
    clientId: String? = null,
) {

    private val lock = Any()
    private val cookieCache = mutableListOf<Cookie>()

    /** 当前会话状态；每次写入存储前都会更新 */
    @Volatile
    var session: SessionState
        private set

    private val client: OkHttpClient

    /**
     * 上传专用客户端。
     *
     * 常规客户端为了「不转圈」把总超时压到 35 秒，这对手机视频是致命的：
     * 一个 500 MB 的 4K 视频在任何家宽上都不可能 35 秒传完。上传必须单独一套宽松超时，
     * 否则用户看到的是「传到一半永远失败」。
     */
    private val uploadClient: OkHttpClient

    /**
     * 下载专用客户端。
     *
     * 常规客户端有 `callTimeout = 35s`，那是为「接口请求」定的：一个接口卡 35 秒还没回来，
     * 基本就是网络有问题。但**下载不能套这个口径** —— 云盘里 30~60 MB 的文件在国内网络下
     * 跑一两分钟很正常，35 秒一到 OkHttp 直接取消请求，用户看到的就是「下载失败」，
     * 而且重试多少次都一样。这里改成不限总时长、只保留「单次读超时」兜底：
     * 真正断流时 read 会先超时，不会无限挂住。
     */
    private val downloadClient: OkHttpClient

    init {
        val loaded = store.readSession(appleId)
        session = loaded ?: SessionState(
            appleId = appleId,
            clientId = clientId ?: ("auth-" + UUID.randomUUID().toString().lowercase()),
        )
        if (loaded == null || loaded.clientId.isBlank()) {
            store.writeSession(session)
        }

        val now = System.currentTimeMillis()
        cookieCache += store.readCookies(appleId)
            .filter { it.expiresAt > now }
            .mapNotNull { it.toOkHttp() }

        client = baseClient.newBuilder().cookieJar(StoreCookieJar()).build()
        uploadClient = baseClient.newBuilder()
            .cookieJar(StoreCookieJar())
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            // 0 = 不限总时长，进度由 UI 层自己看着办
            .callTimeout(0, TimeUnit.SECONDS)
            .build()

        downloadClient = baseClient.newBuilder()
            .cookieJar(StoreCookieJar())
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // 单次读 60 秒没有新字节才判失败；总时长不设上限
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    // ---------------------------------------------------------------- 会话读写

    /** 供认证层更新会话字段：只写非空值，避免把已捕获的字段清空 */
    fun updateSession(
        scnt: String? = null,
        sessionId: String? = null,
        sessionToken: String? = null,
        trustToken: String? = null,
        accountCountry: String? = null,
        dsid: String? = null,
    ) {
        synchronized(lock) {
            session = session.copy(
                scnt = scnt ?: session.scnt,
                sessionId = sessionId ?: session.sessionId,
                sessionToken = sessionToken ?: session.sessionToken,
                trustToken = trustToken ?: session.trustToken,
                accountCountry = accountCountry ?: session.accountCountry,
                dsid = dsid ?: session.dsid,
            )
            store.writeSession(session)
        }
    }

    fun resetSession() {
        synchronized(lock) {
            cookieCache.clear()
            store.clear(appleId)
            session = SessionState(
                appleId = appleId,
                clientId = "auth-" + UUID.randomUUID().toString().lowercase(),
            )
            store.writeSession(session)
        }
    }

    val hasSessionToken: Boolean get() = !session.sessionToken.isNullOrBlank()

    /** 诊断用：列出会随该 URL 发出的 Cookie 名（排查 "Missing validate token" 这类问题） */
    fun cookieNamesFor(url: String): List<String> = try {
        val httpUrl = url.toHttpUrl()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            cookieCache.filter { it.expiresAt > now && it.matches(httpUrl) }
                .map { "${it.name}@${it.domain}" }
        }
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    // ---------------------------------------------------------------- 通用请求

    /** 所有 CloudKit / drivews 接口都要带的公共 query 参数 */
    fun apiParams(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val base = linkedMapOf(
            "clientBuildNumber" to ICloudConstants.CLIENT_BUILD_NUMBER,
            "clientMasteringNumber" to ICloudConstants.CLIENT_MASTERING_NUMBER,
            "clientId" to session.clientId,
        )
        session.dsid?.let { base["dsid"] = it }
        base.putAll(extra)
        return base
    }

    /** 登录链路专用请求头（OAuth + Widget Key + scnt/sessionId） */
    fun authHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val headers = linkedMapOf(
            "Accept" to "application/json, text/javascript",
            "Content-Type" to "application/json",
            "X-Apple-OAuth-Client-Id" to ICloudConstants.WIDGET_KEY,
            "X-Apple-OAuth-Client-Type" to "firstPartyAuth",
            "X-Apple-OAuth-Redirect-URI" to region.home,
            "X-Apple-OAuth-Require-Grant-Code" to "true",
            "X-Apple-OAuth-Response-Mode" to "web_message",
            "X-Apple-OAuth-Response-Type" to "code",
            "X-Apple-OAuth-State" to session.clientId,
            "X-Apple-Widget-Key" to ICloudConstants.WIDGET_KEY,
        )
        session.scnt?.takeIf { it.isNotBlank() }?.let { headers["scnt"] = it }
        session.sessionId?.takeIf { it.isNotBlank() }
            ?.let { headers["X-Apple-ID-Session-Id"] = it }
        headers.putAll(extra)
        return headers
    }

    /** 访问 iCloud 业务接口时的通用头 */
    fun apiHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val headers = linkedMapOf(
            "Origin" to region.home,
            "Referer" to "${region.home}/",
            "Accept" to "application/json",
        )
        headers.putAll(extra)
        return headers
    }

    fun postJson(
        url: String,
        body: String? = "null",
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
    ): HttpResponse = execute(
        Request.Builder()
            .url(buildUrl(url, query))
            .post((body ?: "null").toRequestBody(JSON_MEDIA))
            .headers(toHeaders(headers))
            .build(),
    )

    fun putJson(
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val builder = Request.Builder().url(buildUrl(url, query))
        val requestBody = body?.toRequestBody(JSON_MEDIA)
        builder.put(requestBody ?: ByteArray(0).toRequestBody(null))
        return execute(builder.headers(toHeaders(headers)).build())
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
    ): HttpResponse = execute(
        Request.Builder()
            .url(buildUrl(url, query))
            .get()
            .headers(toHeaders(headers))
            .build(),
    )

    /**
     * 原样下载（照片原图可达数百 MB，不能整块读进内存）。
     * 返回可关闭的响应体流，由调用方写入磁盘。
     */
    fun stream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        onResponse: (okhttp3.Response) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .get()
            .headers(toHeaders(headers + mapOf("Accept" to "*/*")))
            .build()
        try {
            // 用下载专用 client：普通 client 的 35 秒总超时会把大文件下载整体掐断
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ICloudNetworkException(
                        "下载失败：HTTP ${response.code} ${response.message}",
                    )
                }
                onResponse(response)
            }
        } catch (e: IOException) {
            throw ICloudNetworkException("下载中断：${e.message}", e)
        }
    }

    /**
     * 边下边写盘，并把进度回报给界面。
     *
     * 不整块读进内存：云盘里 60 MB 的文件读成 ByteArray 在低端机上就是 OOM。
     * 返回实际写入字节数，调用方可以拿它跟 `Content-Length` 对账 ——
     * 少写了说明连接中途被掐断，不能当成「下载成功」。
     */
    fun downloadTo(
        url: String,
        out: OutputStream,
        headers: Map<String, String> = emptyMap(),
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): Long {
        val request = Request.Builder()
            .url(url)
            .get()
            .headers(toHeaders(headers + mapOf("Accept" to "*/*")))
            .build()
        try {
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ICloudNetworkException(
                        "下载失败：HTTP ${response.code} ${response.message}",
                    )
                }
                val body = response.body
                    ?: throw ICloudNetworkException("下载失败：响应没有内容")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        received += read
                        // 每 256 KB 回报一次：再密就是拿主线程消息队列开玩笑
                        if (received - lastReport >= 256 * 1024) {
                            lastReport = received
                            onProgress(received, total)
                        }
                    }
                    out.flush()
                    onProgress(received, total)
                    if (total > 0 && received < total) {
                        throw ICloudNetworkException(
                            "下载不完整：只收到 $received/$total 字节，请重试",
                        )
                    }
                    return received
                }
            }
        } catch (e: IOException) {
            throw ICloudNetworkException("下载中断：${e.message}", e)
        }
    }

    /**
     * 上传文件（流式）。
     *
     * 三个约束：
     * - **不能整块读进内存**：手机视频动辄几百 MB，读成 ByteArray 会直接 OOM；
     * - **不能带 JSON Content-Type**：照片/云盘的签名上传地址对 Content-Type 敏感，
     *   带错会被内容主机拒成 400；
     * - **要能报进度**：否则界面只能干转圈，用户不知道是在传还是卡死了。
     *
     * @param contentType 内容类型；null 表示不发送该头（照片上传的预留地址允许省略）
     * @param onProgress  (已发送字节, 总字节)
     */
    fun postFile(
        url: String,
        file: File,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): HttpResponse = execute(
        Request.Builder()
            .url(url)
            .post(FileRequestBody(file, contentType?.toMediaType(), onProgress))
            .headers(toHeaders(headers + mapOf("Accept" to "*/*")))
            .build(),
        // 上传失败重试的成本是「白传一遍几百 MB」，交给调用方决定是否重试
        client = uploadClient,
    )

    /**
     * 上传内存中的小数据（云盘的 0 字节文件、文本等）。
     * 小于 1 MB 的内容用这个就够了，避免为几字节落一次盘。
     */
    fun postBytes(
        url: String,
        bytes: ByteArray,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse = execute(
        Request.Builder()
            .url(url)
            .post(bytes.toRequestBody(contentType?.toMediaType()))
            .headers(toHeaders(headers + mapOf("Accept" to "*/*")))
            .build(),
        client = uploadClient,
    )

    // ---------------------------------------------------------------- 内部实现

    private fun execute(
        request: Request,
        client: OkHttpClient = this.client,
    ): HttpResponse {
        val response = try {
            client.newCall(request).execute()
        } catch (e: InterruptedIOException) {
            throw ICloudNetworkException(
                "网络超时（${request.url.host}${request.url.encodedPath}）：DNS 或代理可能有问题",
                e,
            )
        } catch (e: IOException) {
            throw ICloudNetworkException("网络不可达：${e.message}", e)
        }
        response.use { resp ->
            captureSessionHeaders(resp.headers)
            val text = try {
                resp.body?.string().orEmpty()
            } catch (e: IOException) {
                throw ICloudNetworkException("读取响应失败：${e.message}", e)
            }
            return HttpResponse(resp.code, text, resp.headers)
        }
    }

    /** 苹果在登录过程中通过响应头下发会话数据，收到就必须立刻落到状态里 */
    private fun captureSessionHeaders(headers: Headers) {
        var changed = false
        var next = session
        for ((headerName, field) in ICloudConstants.HEADER_TO_SESSION_KEY) {
            val value = headers.get(headerName)?.takeIf { it.isNotBlank() } ?: continue
            next = when (field) {
                "accountCountry" -> if (next.accountCountry == value) next
                else next.copy(accountCountry = value).also { changed = true }

                "sessionId" -> if (next.sessionId == value) next
                else next.copy(sessionId = value).also { changed = true }

                "sessionToken" -> if (next.sessionToken == value) next
                else next.copy(sessionToken = value).also { changed = true }

                "trustToken" -> if (next.trustToken == value) next
                else next.copy(trustToken = value).also { changed = true }

                "scnt" -> if (next.scnt == value) next
                else next.copy(scnt = value).also { changed = true }

                else -> next
            }
        }
        if (changed) {
            synchronized(lock) {
                session = next
                store.writeSession(next)
            }
        }
    }

    private fun buildUrl(url: String, query: Map<String, String>): HttpUrl {
        val base = try {
            url.toHttpUrl()
        } catch (e: IllegalArgumentException) {
            throw ICloudProtocolException("非法 URL：$url", e)
        }
        if (query.isEmpty()) return base
        return base.newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
    }

    private fun toHeaders(map: Map<String, String>): Headers {
        val builder = Headers.Builder()
        map.forEach { (k, v) -> builder.add(k, v) }
        builder.add("User-Agent", ICloudConstants.USER_AGENT)
        return builder.build()
    }

    /** OkHttp 的 CookieJar 实现：内存缓存 + 每次变更写存储 */
    private inner class StoreCookieJar : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            synchronized(lock) {
                val now = System.currentTimeMillis()
                cookieCache.removeAll { it.expiresAt < now }
                for (cookie in cookies) {
                    cookieCache.removeAll {
                        it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                    }
                    if (cookie.expiresAt > now) cookieCache.add(cookie)
                }
                store.writeCookies(appleId, cookieCache.map { it.toStored() })
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
            val now = System.currentTimeMillis()
            cookieCache.filter { it.expiresAt > now && it.matches(url) }
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * 全局唯一的 OkHttp 实例。
         *
         * 曾经是「每次 ICloudClient.create 都 new 一个」，后果很实在：
         * DNS 缓存与连接池每次都从零开始，国内访问 `*.icloud.com.cn` 首次
         * TLS 握手很容易吃满超时，用户看到的就是「第一次点登录报错，第二次才成功」。
         * 共享实例后，第二次请求复用第一次已经建立的连接与解析结果。
         * Cookie 仍按会话隔离（每个 ICloudHttp 用 newBuilder().cookieJar(...) 各自覆盖）。
         */
        val SHARED_CLIENT: OkHttpClient by lazy { buildClient() }

        fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            // 超时要「短而可重试」：卡 45 秒才报错，用户只会以为 App 死了
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
    }
}

// -------------------------------------------------------------------- 转换工具

/** 会话级 Cookie（discard）落盘时给 30 天，够长但不会永久污染 */
private const val SESSION_COOKIE_TTL_MS = 30L * 24 * 60 * 60 * 1000

internal fun Cookie.toStored(): StoredCookie = StoredCookie(
    name = name,
    value = value,
    domain = domain,
    path = path,
    expiresAt = if (expiresAt > System.currentTimeMillis() + SESSION_COOKIE_TTL_MS) {
        expiresAt
    } else if (persistent) {
        expiresAt
    } else {
        System.currentTimeMillis() + SESSION_COOKIE_TTL_MS
    },
    secure = secure,
    httpOnly = httpOnly,
    hostOnly = hostOnly,
)

internal fun StoredCookie.toOkHttp(): Cookie? = try {
    var builder = Cookie.Builder()
        .name(name)
        .value(value)
        .expiresAt(expiresAt)
        .path(path.ifBlank { "/" })
    val host = domain.trimStart('.')
    builder = if (hostOnly) builder.hostOnlyDomain(host) else builder.domain(host)
    if (secure) builder = builder.secure()
    if (httpOnly) builder = builder.httpOnly()
    builder.build()
} catch (_: IllegalArgumentException) {
    null
}

/**
 * 把磁盘文件流式写进请求体。
 *
 * 关键是 `writeTo` 里按块搬运：手机视频常有几百 MB，任何 `readBytes()` 的写法
 * 都会在上传开始前就 OOM。进度回调顺带把「还剩多久」这件事还给 UI。
 */
private class FileRequestBody(
    private val file: File,
    private val mediaType: MediaType?,
    private val onProgress: (Long, Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var sent = 0L
        file.source().use { source ->
            val buffer = Buffer()
            while (true) {
                val read = source.read(buffer, CHUNK)
                if (read == -1L) break
                sink.write(buffer, read)
                sent += read
                onProgress(sent, total)
            }
        }
    }

    private companion object {
        const val CHUNK = 64L * 1024
    }
}
