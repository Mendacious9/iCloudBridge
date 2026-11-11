package cn.yunqiao.icloud

import cn.yunqiao.icloud.account.AccountApi
import cn.yunqiao.icloud.auth.ICloudAuth
import cn.yunqiao.icloud.drive.DriveApi
import cn.yunqiao.icloud.http.ICloudHttp
import cn.yunqiao.icloud.model.AccountInfo
import cn.yunqiao.icloud.photos.PhotosApi
import cn.yunqiao.icloud.store.SessionStore
import okhttp3.OkHttpClient

/**
 * 对外唯一入口。
 *
 * 分层：`ICloudHttp`（传输/会话） → `ICloudAuth`（认证） → `PhotosApi` / `DriveApi`（业务）。
 * Android 端 ViewModel 只依赖这个门面，不在 UI 层碰任何协议细节。
 *
 * 典型用法：
 * ```
 * val client = ICloudClient.create("me@example.com", ICloudRegion.CHINA, store)
 * if (client.auth.restoreSession() == null) {
 *     when (client.auth.signIn(password)) {
 *         SignInResult.Completed -> client.auth.accountLogin()
 *         SignInResult.TwoFactorRequired -> {
 *             val phones = client.auth.trustedPhones()
 *             client.auth.sendSmsCode(phones.first().id)
 *             client.auth.submitSmsCode(phones.first().id, code)
 *             client.auth.accountLogin()
 *         }
 *     }
 * }
 * val albums = client.photos.albums()
 * ```
 */
class ICloudClient(
    val http: ICloudHttp,
) {
    val auth: ICloudAuth = ICloudAuth(http)
    val photos: PhotosApi = PhotosApi(http, auth)
    val drive: DriveApi = DriveApi(http, auth)
    val account: AccountApi = AccountApi(http)

    val region: ICloudRegion get() = http.region
    val appleId: String get() = http.appleId

    /** 复用已保存会话；返回 null 表示需要重新登录 */
    suspend fun connect(): AccountInfo? = auth.restoreSession()

    /** 清空本地登录态（密码从未落盘，只需清令牌与 Cookie） */
    suspend fun signOut() = auth.signOut()

    companion object {
        fun create(
            appleId: String,
            region: ICloudRegion,
            store: SessionStore,
            baseClient: OkHttpClient? = null,
        ): ICloudClient {
            val http = if (baseClient == null) {
                ICloudHttp(appleId = appleId, region = region, store = store)
            } else {
                ICloudHttp(
                    appleId = appleId,
                    region = region,
                    store = store,
                    baseClient = baseClient,
                )
            }
            return ICloudClient(http)
        }
    }
}
