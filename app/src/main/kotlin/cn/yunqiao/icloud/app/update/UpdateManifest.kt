package cn.yunqiao.icloud.app.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * 线上更新清单。
 *
 * 这是一个纯数据契约：既可以从 GitHub Release 的 `update.json` 资源读，
 * 也可以从你自己的静态地址读，两边内容必须是同一份格式。
 *
 * ```json
 * {
 *   "versionCode": 7,
 *   "versionName": "1.0.5",
 *   "apkUrl": "https://github.com/<owner>/<repo>/releases/download/v1.0.5/app-release.apk",
 *   "sha256": "3f2a...（64 位小写十六进制）",
 *   "sizeBytes": 18930122,
 *   "minSupportedVersionCode": 6,
 *   "changelog": {
 *     "en": "Faster album loading.",
 *     "zh-CN": "相册加载更快。"
 *   },
 *   "publishedAt": "2026-11-11"
 * }
 * ```
 *
 * 字段约定：
 * - [versionCode] 是**唯一**的版本比较依据。versionName 只用来显示，
 *   允许写成 `1.0.5` 这种人类可读形式，不参与比较。
 * - [sha256] 留空表示清单不提供校验值，此时仍然会做签名校验（见
 *   [UpdateDownloader.verifySignature]）——但**强烈建议填**，多一道防线。
 * - [minSupportedVersionCode] 是强制更新的下界：当前版本低于它就不可跳过。
 *   留 0 表示不强制。
 */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String = "",
    val sizeBytes: Long = 0,
    @SerialName("minSupportedVersionCode") val minSupportedVersionCode: Int = 0,
    /**
     * 按语言标签给更新日志：`en` / `zh-CN` / `zh-TW` / `ja` / `ko`。
     * 也接受只写语言部分（`zh`）—— 匹配时先精确后退化，见 [changelogFor]。
     */
    val changelog: Map<String, String> = emptyMap(),
    val publishedAt: String? = null,
) {

    /** 当前版本是否必须更新（低过强制下界就不可跳过） */
    fun isForcedFor(currentVersionCode: Int): Boolean =
        minSupportedVersionCode > 0 && currentVersionCode < minSupportedVersionCode

    /**
     * 取当前系统语言对应的更新日志。
     *
     * 匹配顺序：完整标签（`zh-CN`）→ 语言部分（`zh`）→ 简繁退化 → 英文 → 任意一条。
     * 都没有就返回 null，界面会退化成只显示版本号，不会出现空白对话框。
     */
    fun changelogFor(locale: Locale = Locale.getDefault()): String? {
        if (changelog.isEmpty()) return null
        val full = locale.toLanguageTag().replace('_', '-')
        changelog[full]?.let { return it }
        changelog[full.substringBefore('-')]?.let { return it }

        if (full.startsWith("zh", ignoreCase = true)) {
            // 简繁必须分开判断：简体设备（zh-Hans-CN）落进「先取 zh-TW」的旧逻辑，
            // 会出现简中用户看到繁体更新日志。脚本标记优先，其次才看地区。
            val traditional = when (full.split('-').getOrNull(1)?.uppercase()) {
                "HANT" -> true
                "HANS" -> false
                // 没有脚本标记时按地区判断：tw / hk / mo 是繁体区
                else -> listOf("TW", "HK", "MO").any { full.endsWith("-$it", ignoreCase = true) }
            }
            val first = if (traditional) "zh-TW" else "zh-CN"
            val second = if (traditional) "zh-CN" else "zh-TW"
            changelog[first]?.let { return it }
            changelog[second]?.let { return it }
        }
        changelog["en"]?.let { return it }
        return changelog.values.firstOrNull()
    }

    /** 清单是否自洽：版本号必须为正、下载地址必须是 https */
    fun validate(): String? = when {
        versionCode <= 0 -> "versionCode 必须大于 0"
        versionName.isBlank() -> "versionName 不能为空"
        !apkUrl.startsWith("https://") -> "apkUrl 必须是 https 地址"
        sha256.isNotEmpty() && !sha256.matches(Regex("[0-9a-fA-F]{64}")) -> "sha256 必须是 64 位十六进制"
        else -> null
    }
}

/**
 * 更新源配置。
 *
 * 两个源是「先 A 后 B」的关系，不是二选一：
 * 1. [GITHUB_REPO] 非空时，先查 GitHub 的 `releases/latest`；
 * 2. 任一环节失败（网络不通、没有 release、没有 update.json 资源），
 *    就退回到 [MANIFEST_URL]。
 *
 * GitHub 在国内经常连不上，所以**建议两个都配**：静态源可以是对象存储、
 * Cloudflare R2、又拍云，甚至 raw.githubusercontent 的镜像。
 * 只配一个也能工作。
 */
object UpdateConfig {

    /**
     * `owner/repo`。发布到自己的仓库前请改成实际地址。
     * 留空 = 不启用 GitHub 源。
     */
    const val GITHUB_REPO: String = "Mendacious9/iCloudBridge"

    /**
     * 静态清单地址（直链到 update.json 本身，不是所在目录）。
     * 留空 = 不启用静态源。
     */
    const val MANIFEST_URL: String = ""

    /** 更新检查的 User-Agent：GitHub API 强制要求带，否则返回 403 */
    const val USER_AGENT: String = "iCloudBridge-Android"

    /** 两次自动检查之间的最小间隔，避免每次冷启动都打一次接口 */
    const val AUTO_CHECK_INTERVAL_MS: Long = 6 * 60 * 60 * 1000L
}
