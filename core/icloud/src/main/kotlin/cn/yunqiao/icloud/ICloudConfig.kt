package cn.yunqiao.icloud

/** Apple 账户区域：国区（云上贵州）与国际站使用不同域名，选错会返回 -20101 */
enum class ICloudRegion(
    val authRoot: String,
    val authEndpoint: String,
    val home: String,
    val setup: String,
    val label: String,
) {
    GLOBAL(
        authRoot = "https://idmsa.apple.com",
        authEndpoint = "https://idmsa.apple.com/appleauth/auth",
        home = "https://www.icloud.com",
        setup = "https://setup.icloud.com/setup/ws/1",
        label = "国际版 iCloud",
    ),
    CHINA(
        authRoot = "https://idmsa.apple.com.cn",
        authEndpoint = "https://idmsa.apple.com.cn/appleauth/auth",
        home = "https://www.icloud.com.cn",
        setup = "https://setup.icloud.com.cn/setup/ws/1",
        label = "国区 iCloud（云上贵州）",
    ),
}

/**
 * 与 iCloud 网页端对齐的客户端常量。
 * 这些值随苹果网页端改版而变化，需要跟随 icloudpd 等项目更新。
 */
object ICloudConstants {
    const val WIDGET_KEY: String =
        "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d"
    const val USER_AGENT: String =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    const val CLIENT_BUILD_NUMBER: String = "2522Project44"
    const val CLIENT_MASTERING_NUMBER: String = "2522B2"

    /** 登录响应头 -> 会话字段名（登录过程中需要逐步捕获并持久化） */
    val HEADER_TO_SESSION_KEY: Map<String, String> = mapOf(
        "X-Apple-ID-Account-Country" to "accountCountry",
        "X-Apple-ID-Session-Id" to "sessionId",
        "X-Apple-Session-Token" to "sessionToken",
        "X-Apple-TwoSV-Trust-Token" to "trustToken",
        "X-Apple-TwoSV-Trust-Eligible" to "trustEligible",
        "scnt" to "scnt",
    )

    val AUTH_HEADER_NAMES: List<String> = listOf(
        "X-Apple-OAuth-Client-Id",
        "X-Apple-OAuth-Client-Type",
        "X-Apple-OAuth-Redirect-URI",
        "X-Apple-OAuth-Require-Grant-Code",
        "X-Apple-OAuth-Response-Mode",
        "X-Apple-OAuth-Response-Type",
        "X-Apple-OAuth-State",
        "X-Apple-Widget-Key",
    )
}
