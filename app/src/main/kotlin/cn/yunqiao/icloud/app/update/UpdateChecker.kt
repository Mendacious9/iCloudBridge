package cn.yunqiao.icloud.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 更新检查失败。message 是可以直接给用户看的中文原因，不含堆栈。 */
class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 拉取线上版本清单。
 *
 * 两个源按顺序尝试，任一成功即返回：
 * 1. **GitHub Releases**：`/repos/{owner}/{repo}/releases/latest`，从 assets 里找
 *    `update.json` 再下载它。不用 `tag_name` 当版本号 —— 标签是人随手起的
 *    （`v1.0.5`、`1.0.5`、`release-1.0.5`），拿它比较版本迟早出错，
 *    所以强制要求 release 里附一个带 `versionCode` 的 json。
 * 2. **静态地址**：直接 GET 一个 update.json。
 *
 * 为什么不用 GitHub 的 `browser_download_url` 直接下 APK：那只能拿到文件，
 * 拿不到 `sha256` 与强制更新阈值。清单里把这些一起给出，校验才有依据。
 */
class UpdateChecker(
    private val client: OkHttpClient = defaultUpdateClient(),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        // 清单是手写/脚本生成的，偶尔多个尾逗号，不该因此判失败
        isLenient = true
    }

    suspend fun check(): UpdateManifest = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null

        if (UpdateConfig.GITHUB_REPO.isNotBlank()) {
            try {
                return@withContext fromGithub(UpdateConfig.GITHUB_REPO)
            } catch (e: Throwable) {
                lastError = e
            }
        }

        if (UpdateConfig.MANIFEST_URL.isNotBlank()) {
            try {
                return@withContext fromUrl(UpdateConfig.MANIFEST_URL)
            } catch (e: Throwable) {
                lastError = e
            }
        }

        throw when {
            lastError is UpdateException -> lastError
            lastError != null -> UpdateException("检查更新失败：${lastError.message ?: "网络异常"}", lastError)
            else -> UpdateException("没有配置更新源")
        }
    }

    // ------------------------------------------------------------------ GitHub

    private fun fromGithub(repo: String): UpdateManifest {
        val api = "https://api.github.com/repos/$repo/releases/latest"
        val release = getJsonObject(api)

        val assets = (release["assets"] as? JsonArray).orEmpty()
        val manifestUrl = assets.asSequence()
            .mapNotNull { it as? JsonObject }
            // 用 as? 而不是 jsonPrimitive：assets 里混进非字符串字段时要跳过，不能让检查崩掉
            .firstOrNull { (it["name"] as? JsonPrimitive)?.content == MANIFEST_ASSET }
            ?.get("browser_download_url")
            ?.let { (it as? JsonPrimitive)?.content }
            ?: throw UpdateException(
                "最新 Release 里没有 $MANIFEST_ASSET（发布时要把清单和 APK 一起传上去）",
            )

        return fromUrl(manifestUrl)
    }

    // ------------------------------------------------------------------ 通用

    private fun fromUrl(url: String): UpdateManifest {
        if (!url.startsWith("https://")) throw UpdateException("更新源必须是 https 地址")

        val body = getJsonObject(url).toString()
        val manifest = runCatching { json.decodeFromString<UpdateManifest>(body) }
            .getOrElse { throw UpdateException("版本清单格式不对：${it.message?.take(80)}", it) }

        manifest.validate()?.let { throw UpdateException("版本清单不合法：$it") }
        return manifest
    }

    private fun getJsonObject(url: String): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UpdateConfig.USER_AGENT)
            .header("Accept", "application/vnd.github+json, application/json")
            // 静态清单可能被 CDN 缓存，但要能拿到新版本；GitHub API 本身不缓存
            .header("Cache-Control", "no-cache")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val hint = when (response.code) {
                    403 -> "（GitHub 接口限流或拒绝访问）"
                    404 -> "（仓库或 Release 不存在）"
                    else -> ""
                }
                throw UpdateException("HTTP ${response.code}$hint")
            }
            val text = response.body?.string().orEmpty().trim()
            if (text.isEmpty()) throw UpdateException("更新源返回了空内容")
            return runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw UpdateException("更新源返回的不是 JSON", it) }
        }
    }

    private companion object {
        const val MANIFEST_ASSET = "update.json"
    }
}

/**
 * 检查更新的专用客户端。
 *
 * 超时都压得很短：这是「有则提示、无则静默」的旁路任务，
 * 不能让一个连不通的更新源把启动流程卡住。
 */
private fun defaultUpdateClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()
