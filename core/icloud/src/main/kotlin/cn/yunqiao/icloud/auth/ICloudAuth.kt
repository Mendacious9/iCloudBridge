package cn.yunqiao.icloud.auth

import cn.yunqiao.icloud.ICloudAuthException
import cn.yunqiao.icloud.ICloudException
import cn.yunqiao.icloud.ICloudProtocolException
import cn.yunqiao.icloud.ICloudRegionMismatchException
import cn.yunqiao.icloud.ICloudTwoFactorRequiredException
import cn.yunqiao.icloud.http.ICloudHttp
import cn.yunqiao.icloud.internal.arr
import cn.yunqiao.icloud.internal.bool
import cn.yunqiao.icloud.internal.int
import cn.yunqiao.icloud.internal.long
import cn.yunqiao.icloud.internal.obj
import cn.yunqiao.icloud.internal.str
import cn.yunqiao.icloud.model.AccountInfo
import cn.yunqiao.icloud.model.WebService
import cn.yunqiao.icloud.srp.SrpClient
import cn.yunqiao.icloud.srp.SrpPassword
import cn.yunqiao.icloud.srp.toFixed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.math.BigInteger
import java.util.Base64

/** 一次 SRP 登录的结果 */
sealed interface SignInResult {
    /** 已直接登录成功（凭 trust token 免 2FA） */
    data object Completed : SignInResult

    /** 账户要求双重认证，需要走验证码（受信设备推送或短信） */
    data object TwoFactorRequired : SignInResult
}

/**
 * iCloud 认证：SRP 登录 + 双重认证 + 会话建立。
 *
 * 协议要点（2026 现行流程，已在 Python PoC 上逐条验证）：
 * - 登录拆成 `/signin/init`（拿 salt / B / c）与 `/signin/complete`（交 M1/M2）；
 * - complete 返回 409 = 需要 2FA，不是失败；
 * - 2FA 有两条通道：受信设备推送（PUT /verify/trusteddevice/securitycode）
 *   和短信（PUT /verify/phone，然后在 /verify/phone/securitycode 校验）；
 * - 验证通过后 GET `/2sv/trust` 才会把当前会话标为可信，之后 accountLogin 才能拿到 dsInfo；
 * - 会话令牌（sessionToken）+ 信任令牌（trustToken）+ Cookie 三者都齐，才能长期免登录。
 *
 * 注意：网页 API 只接受 Apple ID **账户主密码**。App 专用密码（xxxx-xxxx-xxxx-xxxx）
 * 只对 IMAP/CalDAV 有效，用它必然返回 -20101。
 */
class ICloudAuth(private val http: ICloudHttp) {

    @Volatile
    var account: AccountInfo? = null
        private set

    /** 最近一次 accountLogin 的完整响应，用于判断 2FA 状态与取 webservices */
    @Volatile
    private var lastLoginResponse: JsonObject? = null

    val isSignedIn: Boolean get() = account != null

    private val authEndpoint get() = http.region.authEndpoint

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    // ------------------------------------------------------------ 会话复用

    /**
     * 尝试用已保存的会话直接恢复登录态。
     *
     * @return 恢复成功返回账户信息；会话过期/需要补 2FA 时返回 null（调用方需重新登录）
     */
    /**
     * 尝试用已保存的会话直接恢复登录态。
     *
     * 判定标准只有一个：**accountLogin 是否返回了 dsInfo**。
     * 不能再用 `hsaTrustedBrowser` 之类的标志——2026 年的流程里它实测长期是 false
     * （`hsaChallengeRequired` 也是 true），但会话本身完全可用。
     * 早期按那套标志判断，会把好好的会话丢掉，表现就是「刚登录过、重启又要重登」。
     */
    suspend fun restoreSession(): AccountInfo? = withContext(Dispatchers.IO) {
        if (!http.hasSessionToken) {
            println("[ICloudAuth] 本地没有会话令牌，跳过恢复")
            return@withContext null
        }
        try {
            accountLogin()
        } catch (e: Throwable) {
            // 这里必须留痕：静默失败会让用户以为「登录又丢了」，却没有任何线索
            println("[ICloudAuth] 会话恢复失败：${e.javaClass.simpleName} ${e.message}")
            // 网络问题 ≠ 会话失效：抛出去让上层重试，否则一次 DNS 抖动就把用户踢回登录页
            if (e is cn.yunqiao.icloud.ICloudNetworkException) throw e
            null
        }
    }

    // ------------------------------------------------------------ SRP 登录

    /**
     * 用账户主密码走 SRP 登录。密码不会离开设备——SRP 只上传验证子 proof。
     */
    suspend fun signIn(password: String): SignInResult = withContext(Dispatchers.IO) {
        if (password.isBlank()) throw ICloudAuthException("请输入 Apple ID 账户密码（主密码）")
        if (looksLikeAppPassword(password)) {
            throw ICloudAuthException(
                "这 19 位格式是「App 专用密码」，网页 API 不接受。" +
                    "请改用在 iPhone/网页上登录 iCloud 时使用的账户主密码。",
            )
        }

        val srpClient = SrpClient(http.appleId, SrpPassword(password))
        val headers = http.authHeaders(
            mapOf(
                "Origin" to http.region.authRoot,
                "Referer" to "${http.region.authRoot}/",
            ),
        )

        // 1) /signin/init —— 提交 A，换回 salt / B / c / protocol / iteration
        val initPayload = buildJsonObject {
            put("a", Base64.getEncoder().encodeToString(srpClient.publicA.toFixed()))
            put("accountName", http.appleId)
            putJsonArray("protocols") {
                add("s2k")
                add("s2k_fo")
            }
        }
        val initResponse = http.postJson("$authEndpoint/signin/init", initPayload.toString(), headers)
        if (initResponse.status == 401) {
            throw ICloudAuthException(
                "登录被拒绝（HTTP 401）：Apple ID 或密码不正确。",
                initResponse.status,
            )
        }
        if (!initResponse.isSuccessful) {
            throw authFailure(initResponse.status, initResponse.body, "signin/init")
        }

        val initJson = initResponse.jsonObject()
        val salt = decodeBase64(initJson.str("salt"), "salt")
        val serverB = BigInteger(1, decodeBase64(initJson.str("b"), "b"))
        val cToken = initJson.str("c")
            ?: throw ICloudProtocolException("signin/init 响应缺少 c 字段")
        val protocol = initJson.str("protocol") ?: "s2k_fo"
        val iterations = initJson.int("iteration") ?: 20000

        val proof = srpClient.challenge(salt, serverB, protocol, iterations)

        // 2) /signin/complete —— 提交 M1/M2
        val completePayload = buildJsonObject {
            put("accountName", http.appleId)
            put("c", cToken)
            put("m1", Base64.getEncoder().encodeToString(proof.m1))
            put("m2", Base64.getEncoder().encodeToString(proof.m2))
            put("rememberMe", true)
            putJsonArray("trustTokens") {
                http.session.trustToken?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
        val completeResponse = http.postJson(
            "$authEndpoint/signin/complete",
            completePayload.toString(),
            headers,
            query = mapOf("isRememberMeEnabled" to "true"),
        )

        when {
            // 409 不是错误：账户开启了双重认证，下一步要验证码
            completeResponse.status == 409 -> SignInResult.TwoFactorRequired

            // 412：账户信息需要在网页端补全（很少见），补全后才能继续
            completeResponse.status == 412 -> {
                http.postJson("$authEndpoint/repair/complete", "{}", headers)
                SignInResult.TwoFactorRequired
            }

            completeResponse.status >= 400 ->
                throw authFailure(completeResponse.status, completeResponse.body, "signin/complete")

            else -> SignInResult.Completed
        }
    }

    // ------------------------------------------------------------ 双重认证

    /** 向受信苹果设备推送 6 位验证码（账户上还留有 iPhone/iPad/Mac 时可用） */
    suspend fun triggerDevicePush(): Boolean = withContext(Dispatchers.IO) {
        val response = http.putJson(
            "$authEndpoint/verify/trusteddevice/securitycode",
            headers = http.authHeaders(mapOf("Accept" to "application/json")),
        )
        response.isSuccessful
    }

    /** 提交受信设备上显示的 6 位验证码 */
    suspend fun submitDeviceCode(code: String): Boolean = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonObject("securityCode") { put("code", code.trim()) }
        }
        val response = http.postJson(
            "$authEndpoint/verify/trusteddevice/securitycode",
            payload.toString(),
            http.authHeaders(mapOf("Accept" to "application/json")),
        )
        when {
            response.status in setOf(400, 401, 409) -> false
            !response.isSuccessful -> throw authFailure(response.status, response.body, "2FA 验证")
            else -> trustSession()
        }
    }

    /**
     * 读取账户上的可信手机号。
     * 换了安卓、没有苹果设备时，这是唯一的 2FA 通道。
     *
     * 优先走同一个认证端点的 **JSON 返回**（`Accept: application/json`）：
     * 它直接给出 `trustedPhoneNumbers`，结构稳定、体积 2KB。
     * 早期版本解析登录页 HTML 里的 `boot_args`（70KB），苹果一改版就解析不到，
     * 表现就是「2FA 页面只剩受信设备、没有短信」——实测正是这个原因。
     */
    suspend fun trustedPhones(): List<TrustedPhone> = withContext(Dispatchers.IO) {
        val jsonResponse = http.get(
            authEndpoint,
            http.authHeaders(
                mapOf(
                    "Accept" to "application/json",
                    "X-Apple-OAuth-Redirect-URI" to http.region.home,
                ),
            ),
        )
        if (jsonResponse.isSuccessful) {
            val fromJson = parseTrustedPhonesJson(jsonResponse.body)
            if (fromJson.isNotEmpty()) return@withContext fromJson
        }

        // 回退：JSON 里没有时再解析登录页 HTML
        val htmlResponse = http.get(
            authEndpoint,
            http.authHeaders(
                mapOf(
                    "X-Apple-OAuth-Redirect-URI" to http.region.home,
                    "Accept" to "text/html,application/xhtml+xml",
                ),
            ),
        )
        if (!htmlResponse.isSuccessful) {
            throw ICloudAuthException(
                "读取可信手机号失败：HTTP ${htmlResponse.status}",
                htmlResponse.status,
            )
        }
        BootArgs.parseTrustedPhones(htmlResponse.body)
    }

    /** 从 `GET /appleauth/auth` 的 JSON 里取可信手机号 */
    private fun parseTrustedPhonesJson(body: String): List<TrustedPhone> {
        val json = runCatching { jsonParser.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: return emptyList()
        val numbers = json.arr("trustedPhoneNumbers") ?: return emptyList()
        return numbers.mapNotNull { element ->
            val node = runCatching { element as JsonObject }.getOrNull() ?: return@mapNotNull null
            val id = node.int("id") ?: return@mapNotNull null
            val raw = node.str("numberWithDialCode")
                ?: node.str("obfuscatedNumber")
                ?: return@mapNotNull null
            TrustedPhone(id = id, number = raw.replace('\u2022', '*'))
        }
    }

    /** 向指定手机号发送短信验证码 */
    suspend fun sendSmsCode(phoneId: Int): Boolean = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonObject("phoneNumber") { put("id", phoneId) }
            put("mode", "sms")
        }
        val response = http.putJson(
            "$authEndpoint/verify/phone",
            payload.toString(),
            http.authHeaders(mapOf("Content-type" to "application/json; charset=utf-8")),
        )
        if (!response.isSuccessful) {
            throw ICloudAuthException(
                "短信验证码发送失败：HTTP ${response.status} ${response.body.take(160)}",
                response.status,
            )
        }
        true
    }

    /** 校验短信验证码，成功后把本机标为可信设备 */
    suspend fun submitSmsCode(phoneId: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonObject("phoneNumber") { put("id", phoneId) }
            putJsonObject("securityCode") { put("code", code.trim()) }
            put("mode", "sms")
        }
        val response = http.postJson(
            "$authEndpoint/verify/phone/securitycode",
            payload.toString(),
            http.authHeaders(
                mapOf(
                    "Content-type" to "application/json; charset=utf-8",
                    "Accept" to "application/json; charset=utf-8",
                ),
            ),
        )
        if (!response.isSuccessful) return@withContext false
        trustSession()
    }

    /**
     * GET /2sv/trust —— 把当前会话标记为受信任浏览器，这一步常被漏掉导致 accountLogin 拿不到 dsInfo。
     *
     * 它还会下发 `X-Apple-TwoSV-Trust-Token` 与 `X-APPLE-WEBAUTH-TOKEN` Cookie。
     * **后者是 CloudKit（照片/云盘）的唯一认证方式**：缺了它，accountLogin 照样成功，
     * 但所有业务接口都会 401 `no auth method found`。所以失败一定要留痕。
     */
    private fun trustSession(): Boolean {
        val response = http.get("$authEndpoint/2sv/trust", http.authHeaders())
        if (!response.isSuccessful) {
            println("[ICloudAuth] 2sv/trust 失败：HTTP ${response.status}（CloudKit 授权将缺失）")
        }
        return response.isSuccessful
    }

    // ------------------------------------------------------------ 会话建立

    /**
     * 用 sessionToken 换取 iCloud 服务入口（dsInfo + webservices）。
     * 成功后才能访问照片（ckdatabasews）与云盘（drivews）。
     */
    suspend fun accountLogin(): AccountInfo = withContext(Dispatchers.IO) {
        val token = http.session.sessionToken
            ?: throw ICloudAuthException("缺少会话令牌，需要先完成登录")

        val payload = buildJsonObject {
            http.session.accountCountry?.takeIf { it.isNotBlank() }
                ?.let { put("accountCountryCode", it) }
            put("dsWebAuthToken", token)
            put("extended_login", true)
            put("trustToken", http.session.trustToken.orEmpty())
        }
        val response = http.postJson(
            "${http.region.setup}/accountLogin",
            payload.toString(),
            http.apiHeaders(mapOf("Content-Type" to "application/json")),
        )
        if (!response.isSuccessful) {
            // 421/401/400 几乎都是「sessionToken（dsWebAuthToken）过期」，但
            // **Cookie 往往还活着**：X-APPLE-WEBAUTH-TOKEN 的有效期（约 30 天）
            // 比 sessionToken 长得多。此时 /validate 仍能拿到 dsInfo + webservices。
            // 以前不兜这一手，结果就是「明明还能用，一启动却被踢回登录页」。
            val recovered = runCatching { restoreByValidate() }
                .onFailure {
                    println("[ICloudAuth] /validate 兜底也失败：${it.javaClass.simpleName} ${it.message}")
                }
                .getOrNull()
            if (recovered != null) {
                println("[ICloudAuth] accountLogin 返回 ${response.status}，已用 /validate + Cookie 恢复会话")
                return@withContext recovered
            }
            throw ICloudAuthException(
                "会话建立失败：HTTP ${response.status}（会话令牌可能已过期）",
                response.status,
            )
        }

        val body = response.jsonObject()
        body.str("domainToUse")?.takeIf { it.isNotBlank() }?.let {
            throw ICloudRegionMismatchException(it)
        }
        applyAccount(body, response.status)
    }

    /**
     * 只用 Cookie 恢复会话。
     *
     * `/validate` 与 `accountLogin` 返回同一套结构（dsInfo + webservices），
     * 区别是前者不要求请求体里带 dsWebAuthToken，只要 `X-APPLE-WEBAUTH-*` 系列
     * Cookie 还有效就能过。它是「令牌过期但会话没死」这一档的救命通道。
     */
    private fun restoreByValidate(): AccountInfo? {
        val response = http.get(
            "${http.region.setup}/validate",
            http.apiHeaders(mapOf("Accept" to "application/json", "Content-Type" to "application/json")),
        )
        if (!response.isSuccessful) {
            println("[ICloudAuth] /validate 返回 HTTP ${response.status}")
            return null
        }
        val body = runCatching { response.jsonObject() }.getOrNull() ?: return null
        if (body.obj("dsInfo") == null) {
            println("[ICloudAuth] /validate 响应没有 dsInfo，Cookie 也失效了")
            return null
        }
        return applyAccount(body, 200)
    }

    /** accountLogin 与 /validate 的共同解析：两者响应结构一致 */
    private fun applyAccount(body: kotlinx.serialization.json.JsonObject, status: Int): AccountInfo {
        val dsInfo = body.obj("dsInfo")
        if (dsInfo == null) {
            lastLoginResponse = body
            throw ICloudAuthException(
                "会话尚未受信任（响应缺少 dsInfo）：需要先完成双重认证",
                status,
            )
        }

        val dsid = dsInfo.str("dsid") ?: dsInfo.long("dsid")?.toString()
            ?: throw ICloudProtocolException("dsInfo 缺少 dsid")
        val fullName = listOfNotNull(dsInfo.str("firstName"), dsInfo.str("lastName"))
            .joinToString(" ")
            .ifBlank { null }

        val services = body.obj("webservices")?.mapValues { (_, value) ->
            val node = value as? JsonObject
            WebService(url = node?.str("url"), status = node?.str("status"))
        }.orEmpty()

        val info = AccountInfo(
            appleId = dsInfo.str("appleId") ?: http.appleId,
            fullName = fullName,
            dsid = dsid,
            region = body.str("region") ?: http.session.accountCountry.orEmpty(),
            webservices = services,
        )

        http.updateSession(dsid = dsid, accountCountry = body.str("region"))
        lastLoginResponse = body
        account = info
        return info
    }

    /**
     * 账户是否仍需要 2FA。口径与 pyicloud 一致：
     * hsaVersion==2 且（被要求挑战 或 浏览器不受信）且账户有合格设备。
     */
    fun requiresTwoFactor(): Boolean {
        val data = lastLoginResponse ?: return true
        val dsInfo = data.obj("dsInfo") ?: return true
        val hsaVersion = dsInfo.int("hsaVersion") ?: 0
        val challengeRequired = data.bool("hsaChallengeRequired") ?: false
        val trustedBrowser = data.bool("hsaTrustedBrowser") ?: false
        val qualifyingDevice = dsInfo.bool("hasICloudQualifyingDevice") ?: false
        return hsaVersion == 2 && (challengeRequired || !trustedBrowser) && qualifyingDevice
    }

    /** 是否有可用的双重认证上下文（登录被 409 拦下） */
    fun twoFactorPending(): Boolean = lastLoginResponse == null && http.hasSessionToken

    /** 清空本地登录态 */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        account = null
        lastLoginResponse = null
        http.resetSession()
    }

    /** 服务入口地址，照片/云盘 API 用 */
    fun requireService(key: String, hint: String): String {
        val info = account ?: throw ICloudAuthException("尚未登录")
        return info.serviceUrl(key)?.takeIf { it.isNotBlank() }
            ?: throw cn.yunqiao.icloud.ICloudServiceException(hint)
    }

    // ------------------------------------------------------------ 内部工具

    private fun looksLikeAppPassword(password: String): Boolean =
        password.length == 19 && password.count { it == '-' } == 3

    private fun decodeBase64(value: String?, field: String): ByteArray {
        if (value.isNullOrBlank()) {
            throw ICloudProtocolException("signin/init 响应缺少 $field 字段")
        }
        return try {
            Base64.getDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            throw ICloudProtocolException("$field 字段不是合法 Base64", e)
        }
    }

    private fun authFailure(status: Int, body: String, stage: String): ICloudAuthException {
        val detail = body.take(300).ifBlank { "(无响应体)" }
        val hint = if (body.contains("-20101")) {
            """
            -20101 = 账户名或密码不匹配，按顺序排查：
              1) 用户名必须是账户【主要 Apple ID】，别名邮箱可能被拒
              2) 密码要用 Apple ID 账户主密码，不是 App 专用密码
              3) 近期多次失败或改过密码，账户可能处于 24-72 小时安全硬化期
              4) 主密码在浏览器能登录但这里失败，说明账户已迁移到新认证体系
            """.trimIndent()
        } else {
            ""
        }
        return ICloudAuthException(
            "$stage 失败：HTTP $status\n$hint\n苹果返回：$detail".trim(),
            status,
        )
    }
}

/** 登录被 409 拦下、还不知道走哪条 2FA 通道时给 UI 的可选项 */
fun twoFactorChannels(phones: List<TrustedPhone>): List<String> = buildList {
    add("受信设备推送")
    if (phones.isNotEmpty()) add("短信（${phones.first().number}）")
}
