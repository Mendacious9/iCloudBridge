package cn.yunqiao.icloud.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cn.yunqiao.icloud.model.SessionState
import cn.yunqiao.icloud.store.SessionStore
import cn.yunqiao.icloud.store.StoredCookie
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 安卓侧的会话存储：**账号令牌与 Cookie 不再以明文落盘**。
 *
 * 之前的实现直接把会话 JSON 写在 `files/session/` 目录下的明文文件里，任何拿到
 * 应用私有目录（root、备份、取证镜像）的人都能直接读走 `sessionToken` 与
 * `X-APPLE-WEBAUTH-TOKEN`，从而在**不需要 2FA** 的情况下接管整个账号。
 * 这两样东西等价于长期密码，必须当作密码对待。
 *
 * 现在的做法：
 * - 密钥由 **AndroidKeyStore** 生成并保管，私钥材料永远不出 TEE/StrongBox；
 * - 用 AES-256-GCM 加密整段 JSON（会话 + Cookie 一起），每次写入换新 IV；
 * - 密文与 IV 存在 SharedPreferences 里，应用被卸载/清除数据即销毁。
 *
 * 为什么不做「指纹解锁才能解密」：
 * 那会把 [SessionStore] 的三个读写方法变成会弹指纹的异步操作，而后台刷新、
 * 上传任务都需要静默读 Cookie；一旦系统弹窗就会把定时任务打断。
 * 生物识别已经作为**界面层**的进门锁实现（[BiometricLock]），职责不重叠：
 * 那道锁管「谁能看到界面」，这道锁管「谁能从磁盘拿走令牌」。
 *
 * 兼容处理：首次运行时如果发现旧的明文文件，会读出来加密后**删除明文**，
 * 用户升级 App 不需要重新登录。
 */
class EncryptedSessionStore(
    context: Context,
    /** 旧版明文会话目录（files/session），仅用于一次性迁移 */
    private val legacyDir: File?,
) : SessionStore {

    private companion object {
        const val PREFS = "icloud_bridge_secure"
        const val KEY_ALIAS = "icloud_bridge_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val PREFIX = "v1:"
        val log: (String) -> Unit = { println("[ICloudSecure] $it") }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class Bundle(
        val session: SessionState? = null,
        val cookies: List<StoredCookie> = emptyList(),
    )

    // ------------------------------------------------------------------ 接口

    @Synchronized
    override fun readSession(appleId: String): SessionState? = read(appleId).session

    @Synchronized
    override fun writeSession(state: SessionState) {
        val current = read(state.appleId)
        write(state.appleId, current.copy(session = state))
    }

    @Synchronized
    override fun readCookies(appleId: String): List<StoredCookie> = read(appleId).cookies

    @Synchronized
    override fun writeCookies(appleId: String, cookies: List<StoredCookie>) {
        val current = read(appleId)
        write(appleId, current.copy(cookies = cookies))
    }

    @Synchronized
    override fun clear(appleId: String) {
        prefs.edit().remove(prefKey(appleId)).apply()
        // 顺手清掉可能残留的旧明文，避免「退出登录后旧令牌还躺在磁盘上」
        legacyFile(appleId)?.delete()
    }

    // ------------------------------------------------------------------ 加解密

    private fun prefKey(appleId: String): String = "bundle_" + safeName(appleId)

    private fun read(appleId: String): Bundle {
        val blob = prefs.getString(prefKey(appleId), null)
        if (blob != null) {
            val plain = runCatching { decrypt(blob) }.getOrElse { e ->
                // 密钥被系统作废（改锁屏密码、恢复出厂、换设备迁移）时密文再也解不开。
                // 这时只能丢掉会话重新登录 —— 报错给用户看没有意义。
                log("会话密文无法解密（${e.javaClass.simpleName}），按未登录处理")
                prefs.edit().remove(prefKey(appleId)).apply()
                null
            }
            if (plain != null) {
                return runCatching { json.decodeFromString<Bundle>(plain) }
                    .getOrElse {
                        log("会话 JSON 解析失败，按空会话处理")
                        Bundle()
                    }
            }
        }
        return migrateLegacy(appleId)
    }

    private fun write(appleId: String, bundle: Bundle) {
        runCatching {
            val text = json.encodeToString(Bundle.serializer(), bundle)
            prefs.edit().putString(prefKey(appleId), encrypt(text)).apply()
        }.onFailure { log("会话加密保存失败：${it.javaClass.simpleName}") }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // 密文格式：v1:base64(iv) + ":" + base64(ciphertext)
        return PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        require(blob.startsWith(PREFIX)) { "未知的密文版本" }
        val parts = blob.removePrefix(PREFIX).split(":")
        require(parts.size == 2) { "密文格式异常" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    /**
     * 取（或首次创建）Keystore 里的 AES 密钥。
     *
     * 不设 `setUserAuthenticationRequired`：见类注释——密钥的用途是防磁盘泄露，
     * 不是防当面操作；设置后所有后台读取都要先过指纹，会把同步流程卡死。
     */
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 随机 IV 由系统生成，绝不复用 —— GCM 下 IV 重用等于直接泄露明文
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    // ------------------------------------------------------------------ 迁移

    /** 旧版明文文件：files/session/<appleId>.session.json */
    private fun legacyFile(appleId: String): File? {
        val dir = legacyDir ?: return null
        return File(dir, "${safeName(appleId)}.session.json").takeIf { it.exists() }
    }

    private fun migrateLegacy(appleId: String): Bundle {
        val file = legacyFile(appleId) ?: return Bundle()
        val parsed = runCatching {
            json.decodeFromString<Bundle>(file.readText(Charsets.UTF_8))
        }.getOrElse {
            log("旧明文会话无法解析，直接删除")
            file.delete()
            return Bundle()
        }
        log("把旧明文会话迁移进 Keystore 加密存储（${file.name}）")
        write(appleId, parsed)
        // 只有写成功才删明文，避免迁移中途异常导致会话彻底丢失
        if (prefs.getString(prefKey(appleId), null) != null) file.delete()
        return parsed
    }

    private fun safeName(appleId: String): String =
        appleId.filter { it.isLetterOrDigit() }.ifEmpty { "account" }
}
