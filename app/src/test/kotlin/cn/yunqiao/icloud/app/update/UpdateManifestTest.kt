package cn.yunqiao.icloud.app.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 更新清单是**从网络来的不可信输入**，解析与判定必须钉死。
 *
 * 这几条测试覆盖的是最容易出错、且出错后后果最严重的分支：
 * 版本比较、强制更新边界、语言回退、以及不合法的清单必须被拒绝。
 */
class UpdateManifestTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun manifest(
        versionCode: Int = 7,
        minSupported: Int = 0,
        changelog: Map<String, String> = emptyMap(),
    ) = UpdateManifest(
        versionCode = versionCode,
        versionName = "1.0.5",
        apkUrl = "https://example.com/app.apk",
        sha256 = "a".repeat(64),
        sizeBytes = 1024,
        minSupportedVersionCode = minSupported,
        changelog = changelog,
    )

    @Test
    fun `强制更新的下界是严格小于`() {
        val m = manifest(versionCode = 8, minSupported = 7)
        assertTrue(m.isForcedFor(6))
        // 正好等于下界不算强制：否则用户永远跨不过这一版
        assertFalse(m.isForcedFor(7))
        assertFalse(m.isForcedFor(8))
    }

    @Test
    fun `下界为 0 表示永不强制`() {
        val m = manifest(minSupported = 0)
        assertFalse(m.isForcedFor(1))
        assertFalse(m.isForcedFor(0))
    }

    @Test
    fun `更新日志优先精确匹配语言标签`() {
        val m = manifest(
            changelog = mapOf("en" to "EN", "zh-CN" to "简中", "zh-TW" to "繁中"),
        )
        assertEquals("简中", m.changelogFor(Locale.SIMPLIFIED_CHINESE))
        assertEquals("EN", m.changelogFor(Locale.US))
    }

    @Test
    fun `只写了语言部分时也能退化匹配`() {
        val m = manifest(changelog = mapOf("zh" to "中文"))
        assertEquals("中文", m.changelogFor(Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `只有繁中时简中用户也能看到内容`() {
        val m = manifest(changelog = mapOf("zh-TW" to "繁中"))
        assertEquals("繁中", m.changelogFor(Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `简体设备不会拿到繁体日志`() {
        val m = manifest(changelog = mapOf("zh-CN" to "简中", "zh-TW" to "繁中"))

        // zh-Hans-CN 这类完整标签没有精确命中，必须落到「简体优先」而不是繁体的旧逻辑
        assertEquals("简中", m.changelogFor(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("简中", m.changelogFor(Locale.forLanguageTag("zh-Hans")))
        assertEquals("简中", m.changelogFor(Locale.forLanguageTag("zh")))

        assertEquals("繁中", m.changelogFor(Locale.forLanguageTag("zh-Hant-HK")))
        assertEquals("繁中", m.changelogFor(Locale.forLanguageTag("zh-HK")))
        assertEquals("繁中", m.changelogFor(Locale.TRADITIONAL_CHINESE))
    }

    @Test
    fun `没有对应语言时退到英文`() {
        val m = manifest(changelog = mapOf("en" to "EN only"))
        assertEquals("EN only", m.changelogFor(Locale.JAPAN))
    }

    @Test
    fun `完全没有日志时返回 null 而不是空对话框`() {
        assertNull(manifest().changelogFor(Locale.US))
    }

    @Test
    fun `清单字段校验`() {
        assertNull(manifest().validate())

        // 非 https 的下载地址必须拒绝：明文的安装包等于谁都能换掉它
        assertTrue(manifest().copy(apkUrl = "http://example.com/app.apk").validate() != null)
        assertTrue(manifest().copy(versionCode = 0).validate() != null)
        assertTrue(manifest().copy(versionName = "").validate() != null)
        assertTrue(manifest().copy(sha256 = "abc").validate() != null)
        // 留空表示不提供校验值，仍然允许（后续还有签名校验兜底）
        assertNull(manifest().copy(sha256 = "").validate())
    }

    @Test
    fun `能解析带未知字段的清单`() {
        val raw = """
            {
              "versionCode": 9,
              "versionName": "1.0.6",
              "apkUrl": "https://example.com/a.apk",
              "sha256": "${"b".repeat(64)}",
              "sizeBytes": 2048,
              "minSupportedVersionCode": 7,
              "changelog": {"en": "Fixes."},
              "publishedAt": "2026-11-11",
              "someFutureField": {"nested": true}
            }
        """.trimIndent()

        val m = json.decodeFromString<UpdateManifest>(raw)
        assertEquals(9, m.versionCode)
        assertEquals(7, m.minSupportedVersionCode)
        assertEquals("Fixes.", m.changelogFor(Locale.US))
    }
}
