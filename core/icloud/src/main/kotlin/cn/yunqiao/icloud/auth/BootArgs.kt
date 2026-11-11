package cn.yunqiao.icloud.auth

import cn.yunqiao.icloud.internal.arr
import cn.yunqiao.icloud.internal.int
import cn.yunqiao.icloud.internal.obj
import cn.yunqiao.icloud.internal.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 一个可信手机号（短信 2FA 的目标） */
data class TrustedPhone(
    /** Apple 内部 id，发短信/校验验证码都要带上 */
    val id: Int,
    /** 脱敏号码，如 +86 138****8888 */
    val number: String,
)

/**
 * 从 iCloud 登录页 HTML 里抽取 `boot_args`，再取可信手机号列表。
 *
 * 为什么不能只用正则：`<script>` 标签的属性顺序不固定（`type` 在前还是在后都出现过），
 * 早期用正则写死顺序，苹果一改版就解析失败。这里按标签扫描 + 属性包含判断，
 * 与 Python 侧用 HTMLParser 的思路一致。
 *
 * 2026 年起手机号从 `direct.twoSV.phoneNumberVerification` 迁到了
 * `direct.twoSV.bridgeInitiateData.phoneNumberVerification`，两条路径都要试。
 */
object BootArgs {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 提取 `<script class="boot_args" type="application/json">` 里的 JSON 原文 */
    fun extractPayload(html: String): String? {
        var cursor = 0
        while (true) {
            val start = html.indexOf("<script", cursor, ignoreCase = true)
            if (start < 0) return null
            val tagEnd = html.indexOf('>', start)
            if (tagEnd < 0) return null
            val tag = html.substring(start, tagEnd)
            cursor = tagEnd + 1
            if (!tag.contains("boot_args", ignoreCase = true)) continue
            if (!tag.contains("application/json", ignoreCase = true)) continue

            val close = html.indexOf("</script", cursor, ignoreCase = true)
            if (close < 0) return null
            val payload = html.substring(cursor, close).trim()
            if (payload.startsWith("{")) return payload
        }
    }

    fun parse(html: String): JsonObject? {
        val payload = extractPayload(html) ?: return null
        return runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
    }

    /**
     * 解析可信手机号；返回空列表表示页面里确实没有（而不是解析失败）。
     */
    fun parseTrustedPhones(html: String): List<TrustedPhone> {
        val boot = parse(html) ?: return emptyList()
        return parseTrustedPhones(boot)
    }

    fun parseTrustedPhones(boot: JsonObject): List<TrustedPhone> {
        val twoSv = boot.obj("direct")?.obj("twoSV") ?: return emptyList()
        val candidates = sequenceOf(
            twoSv.obj("phoneNumberVerification"),
            twoSv.obj("bridgeInitiateData")?.obj("phoneNumberVerification"),
        )
        for (container in candidates) {
            val numbers = container?.arr("trustedPhoneNumbers") ?: continue
            val phones = numbers.mapNotNull { element ->
                val node = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                val id = node.int("id") ?: return@mapNotNull null
                val raw = node.str("obfuscatedNumber")
                    ?: node.str("numberWithDialCode")
                    ?: return@mapNotNull null
                TrustedPhone(id = id, number = raw.replace('\u2022', '*'))
            }
            if (phones.isNotEmpty()) return phones
        }
        return emptyList()
    }

    /** 页面里是否明确要求双重认证 */
    fun isTwoFactorRequired(boot: JsonObject): Boolean {
        val direct = boot.obj("direct") ?: return false
        val twoSv = direct.obj("twoSV") ?: return false
        return twoSv.arr("trustedPhoneNumbers") != null ||
            twoSv.obj("phoneNumberVerification") != null ||
            twoSv.obj("bridgeInitiateData") != null
    }
}
