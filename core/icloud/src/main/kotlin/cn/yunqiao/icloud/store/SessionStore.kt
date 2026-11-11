package cn.yunqiao.icloud.store

import cn.yunqiao.icloud.model.SessionState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 需要持久化的 Cookie。
 *
 * 不复用 `java.net.HttpCookie` / LWP 文件格式：安卓端最终要把这份数据塞进
 * Keystore 加密的 DataStore，用 kotlinx.serialization 的纯数据类最省事，
 * 同时能避开 OkHttp `Cookie.Builder.domain()` 不接受带前导点域名的问题。
 */
@Serializable
data class StoredCookie(
    val name: String,
    val value: String,
    /** 原始 domain，可能带前导点（表示域名 cookie 而非 host-only） */
    val domain: String,
    val path: String,
    /** epoch millis；会话级 Cookie 落盘时会被赋一个足够远的未来时间 */
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    /**
     * 是否「仅精确主机」。
     *
     * 必须显式存：OkHttp 的 `Cookie.domain` **从不带前导点**，
     * 靠 `domain.startsWith(".")` 反推会把 `icloud.com.cn` 误判成 host-only，
     * 结果发往 `p228-ckdatabasews.icloud.com.cn` 的请求一个 Cookie 都带不上，
     * CloudKit 直接回 401 `no auth method found`。
     */
    val hostOnly: Boolean = false,
)

/**
 * 会话与 Cookie 的存储抽象。
 *
 * JVM 侧用 [FileSessionStore]，安卓侧应实现一个基于 Keystore AES-GCM +
 * SharedPreferences/DataStore 的版本（密码永不落盘，只有令牌）。
 */
interface SessionStore {
    fun readSession(appleId: String): SessionState?

    fun writeSession(state: SessionState)

    fun readCookies(appleId: String): List<StoredCookie>

    fun writeCookies(appleId: String, cookies: List<StoredCookie>)

    fun clear(appleId: String)
}

/** 落盘结构：会话 + Cookie 放同一个文件，避免两份文件不同步 */
@Serializable
private data class PersistedBundle(
    val session: SessionState? = null,
    val cookies: List<StoredCookie> = emptyList(),
)

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

internal fun safeFileName(appleId: String): String {
    val base = appleId.filter { it.isLetterOrDigit() }.ifEmpty { "account" }
    return base
}

/**
 * 文件版存储（JVM / 桌面验证用）。
 *
 * Windows 上文件可能被杀软或索引服务瞬时锁定，因此所有写入都走
 * 「临时文件 + 原子 rename + 重试」，失败也不抛异常——会话丢一次可以重登，
 * 但绝不能让整个同步流程因为存不进一个文件而中断。
 */
class FileSessionStore(
    private val dir: File,
    private val onWarning: (String) -> Unit = {},
) : SessionStore {

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    private fun fileFor(appleId: String): File =
        File(dir, "${safeFileName(appleId)}.session.json")

    @Synchronized
    override fun readSession(appleId: String): SessionState? =
        readBundle(appleId).session

    @Synchronized
    override fun writeSession(state: SessionState) {
        val current = readBundle(state.appleId)
        writeBundle(state.appleId, current.copy(session = state))
    }

    @Synchronized
    override fun readCookies(appleId: String): List<StoredCookie> =
        readBundle(appleId).cookies

    @Synchronized
    override fun writeCookies(appleId: String, cookies: List<StoredCookie>) {
        val current = readBundle(appleId)
        writeBundle(appleId, current.copy(cookies = cookies))
    }

    @Synchronized
    override fun clear(appleId: String) {
        runCatching { fileFor(appleId).delete() }
    }

    /** 一次性写入会话与 Cookie，供导入等场景使用 */
    @Synchronized
    fun writeAll(state: SessionState, cookies: List<StoredCookie>) {
        writeBundle(state.appleId, PersistedBundle(session = state, cookies = cookies))
    }

    @Synchronized
    private fun readBundle(appleId: String): PersistedBundle {
        val file = fileFor(appleId)
        if (!file.exists()) return PersistedBundle()
        return runCatching {
            json.decodeFromString<PersistedBundle>(file.readText(Charsets.UTF_8))
        }.getOrElse {
            onWarning("会话文件解析失败（${file.name}），将按空会话处理")
            PersistedBundle()
        }
    }

    @Synchronized
    private fun writeBundle(appleId: String, bundle: PersistedBundle) {
        val file = fileFor(appleId)
        val text = json.encodeToString(PersistedBundle.serializer(), bundle)
        val tmp = File(dir, "${file.name}.${System.nanoTime()}.tmp")
        repeat(3) { attempt ->
            try {
                tmp.writeText(text, Charsets.UTF_8)
                try {
                    Files.move(
                        tmp.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: Exception) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                return
            } catch (e: Exception) {
                if (attempt == 2) {
                    onWarning("会话保存失败（文件被占用）：${e.message}")
                } else {
                    Thread.sleep(120)
                }
            }
        }
    }
}

/** 纯内存实现，测试与「用完即弃」场景使用 */
class MemorySessionStore : SessionStore {
    private val sessions = mutableMapOf<String, SessionState>()
    private val cookies = mutableMapOf<String, List<StoredCookie>>()

    override fun readSession(appleId: String): SessionState? = sessions[appleId]
    @Synchronized
    override fun writeSession(state: SessionState) {
        sessions[state.appleId] = state
    }

    override fun readCookies(appleId: String): List<StoredCookie> = cookies[appleId].orEmpty()
    @Synchronized
    override fun writeCookies(appleId: String, cookies: List<StoredCookie>) {
        this.cookies[appleId] = cookies
    }

    @Synchronized
    override fun clear(appleId: String) {
        sessions.remove(appleId)
        cookies.remove(appleId)
    }
}
