package cn.yunqiao.icloud.tools

import cn.yunqiao.icloud.model.SessionState
import cn.yunqiao.icloud.store.FileSessionStore
import cn.yunqiao.icloud.store.StoredCookie
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 把 Python PoC 阶段留下的会话（poc_session 目录下的 session.json 与 cookies.txt）
 * 导入成 App 用的会话存储。
 *
 * 存在的意义有两个：
 * 1. M1 联调时不必反复向用户索要密码和短信验证码；
 * 2. 将来用户若先用 icloudpd 等工具登录过，也能平滑迁移会话。
 */
object PocSessionImporter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val expiresFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private const val SESSION_COOKIE_TTL_MS = 30L * 24 * 60 * 60 * 1000

    data class Imported(
        val session: SessionState,
        val cookies: List<StoredCookie>,
    )

    /**
     * @param pocSessionDir 形如 `poc/poc_session` 的目录
     * @param appleId       账户，用于定位文件与建立 [SessionState]
     */
    fun read(pocSessionDir: File, appleId: String): Imported? {
        val safe = appleId.filter { it.isLetterOrDigit() }.ifEmpty { "account" }
        val sessionFile = File(pocSessionDir, "$safe.session.json")
        val cookieFile = File(pocSessionDir, "$safe.cookies.txt")
        if (!sessionFile.exists()) return null

        val raw = runCatching {
            json.parseToJsonElement(sessionFile.readText(Charsets.UTF_8)) as JsonObject
        }.getOrNull() ?: return null

        fun field(vararg names: String): String? {
            for (name in names) {
                val value = raw[name]?.jsonPrimitive?.contentOrNull
                if (!value.isNullOrBlank()) return value
            }
            return null
        }

        val session = SessionState(
            appleId = appleId,
            clientId = field("client_id") ?: "auth-imported",
            scnt = field("scnt"),
            sessionId = field("session_id"),
            sessionToken = field("session_token"),
            trustToken = field("trust_token"),
            accountCountry = field("account_country"),
            dsid = field("dsid"),
        )

        val cookies = if (cookieFile.exists()) parseLwpCookies(cookieFile) else emptyList()
        return Imported(session, cookies)
    }

    fun importInto(
        pocSessionDir: File,
        appleId: String,
        store: FileSessionStore,
    ): Imported? {
        val imported = read(pocSessionDir, appleId) ?: return null
        store.writeAll(imported.session, imported.cookies)
        return imported
    }

    /**
     * 解析 Python `http.cookiejar.LWPCookieJar` 的文本格式：
     * `Set-Cookie3: name="v"; path="/"; domain=.icloud.com; secure; discard; ...`
     */
    fun parseLwpCookies(file: File): List<StoredCookie> {
        val result = mutableListOf<StoredCookie>()
        file.readLines(Charsets.UTF_8).forEach { line ->
            if (!line.startsWith("Set-Cookie3:")) return@forEach
            val cookie = parseLwpLine(line.removePrefix("Set-Cookie3:").trim()) ?: return@forEach
            result.add(cookie)
        }
        return result
    }

    private fun parseLwpLine(body: String): StoredCookie? {
        val parts = body.split("; ")
        val head = parts.firstOrNull() ?: return null
        val eq = head.indexOf('=')
        if (eq <= 0) return null

        val name = head.substring(0, eq).trim()
        var value = head.substring(eq + 1).trim()
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }

        var domain = ""
        var path = "/"
        var secure = false
        var httpOnly = false
        var expiresAt = 0L
        var discard = false

        for (attr in parts.drop(1)) {
            val lower = attr.lowercase()
            when {
                lower == "secure" -> secure = true
                lower == "discard" -> discard = true
                lower.startsWith("path=") -> path = attr.substring(5).trim('"').ifBlank { "/" }
                lower.startsWith("domain=") -> domain = attr.substring(7).trim('"')
                lower.startsWith("httponly=") -> httpOnly = attr.substring(9).equals("true", true)
                lower.startsWith("expires=") -> expiresAt = parseExpires(attr.substring(8).trim('"'))
            }
        }
        if (domain.isBlank()) return null

        val effectiveExpiry = if (discard || expiresAt <= 0L) {
            System.currentTimeMillis() + SESSION_COOKIE_TTL_MS
        } else {
            expiresAt
        }

        return StoredCookie(
            name = name,
            value = value,
            domain = domain,
            path = path,
            expiresAt = effectiveExpiry,
            secure = secure,
            httpOnly = httpOnly,
        )
    }

    private fun parseExpires(raw: String): Long {
        val cleaned = raw.trim().trimEnd('Z')
        return runCatching {
            LocalDateTime.parse(cleaned, expiresFormat).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrDefault(0L)
    }
}
