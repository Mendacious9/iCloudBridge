package cn.yunqiao.icloud.app

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.yunqiao.icloud.ICloudApiException
import cn.yunqiao.icloud.ICloudAuthException
import cn.yunqiao.icloud.ICloudClient
import cn.yunqiao.icloud.ICloudNetworkException
import cn.yunqiao.icloud.ICloudRegion
import cn.yunqiao.icloud.ICloudRegionMismatchException
import cn.yunqiao.icloud.ICloudUnsupportedMediaException
import cn.yunqiao.icloud.account.StorageUsage
import cn.yunqiao.icloud.auth.SignInResult
import cn.yunqiao.icloud.auth.TrustedPhone
import cn.yunqiao.icloud.drive.DriveApi
import cn.yunqiao.icloud.app.media.MediaInspector
import cn.yunqiao.icloud.app.media.VideoTranscoder
import cn.yunqiao.icloud.model.AccountInfo
import cn.yunqiao.icloud.model.AssetSize
import cn.yunqiao.icloud.model.Album
import cn.yunqiao.icloud.model.DriveEntry
import cn.yunqiao.icloud.model.PhotoAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** 区域选择：iCloud 中国区与全球区是两套完全独立的认证域名与账号体系 */
enum class RegionChoice(val region: ICloudRegion) {
    CHINA(ICloudRegion.CHINA),
    GLOBAL(ICloudRegion.GLOBAL),
}

sealed interface Stage {
    /** 启动中，尝试复用已保存的会话 */
    data object Booting : Stage

    data object Login : Stage

    data class TwoFactor(
        val phones: List<TrustedPhone>,
        val smsSent: Boolean = false,
        val busy: Boolean = false,
        /** 读取可信手机号失败的原因；有值时界面要显示出来并提供重试，不能静默成「只有受信设备」 */
        val phoneError: UiText? = null,
    ) : Stage

    data class Ready(val account: AccountInfo) : Stage
}

/** 正在进行的上传（照片或云盘），用于界面显示进度 */
data class UploadState(
    val total: Int,
    val finished: Int,
    val name: String,
    val progress: Float,
    val toDrive: Boolean,
    /** 视频编码苹果不收时，先在本机转 H.264 再传；界面要区分这两个阶段 */
    val phase: UploadPhase = UploadPhase.UPLOADING,
)

enum class UploadPhase { UPLOADING, TRANSCODING }

/**
 * 云盘的两种浏览方式，对应 iOS「文件」App 的列表视图与图标视图。
 *
 * 列表适合看文件名/大小/时间这类元信息，图标适合在一堆文件夹里靠视觉快速定位。
 * 选择会持久化——用户选过一次之后，每次进来都该还是他习惯的那种。
 */
enum class DriveView { LIST, GRID }

/** 云盘文件传输状态（打开 / 保存共用一条） */
data class DriveTransfer(
    val name: String,
    /** 0..1；服务端没给 Content-Length 时恒为 0，界面应显示成不确定进度 */
    val progress: Float,
    val opening: Boolean,
)

/**
 * 照片/视频「保存原图到相册」的进行状态。
 *
 * 只保留一个当前任务：查看器一次只能看一张，同时下载两张没有场景，
 * 反而会让进度环互相覆盖。
 */
data class PhotoSaveState(
    /** 哪一张在存，用来让界面只给当前这张显示进度 */
    val recordName: String,
    val progress: Float,
    val done: Boolean,
)

/**
 * 苹果对 0 字节文件直接返回空 400，没有可下载的内容。
 * 单独一个异常类型，是为了给用户一句人话，而不是「未返回下载地址」。
 */
class DriveEmptyFileException(val fileName: String) :
    IOException("该文件是空的（0 字节），没有可下载的内容：$fileName")

/**
 * 全应用唯一的状态持有者。
 *
 * 设计原则：UI 只读 [stage] 与各列表状态、只调本类的方法，绝不直接接触协议层。
 * 所有异常都在这里转成 [UiText]（资源 id + 参数），由 UI 层按当前语言解析 ——
 * 这样切换语言后，连错误提示也会跟着变。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        /** 当前已建立会话的账号（只用于启动时恢复会话） */
        const val KEY_APPLE_ID = "apple_id"

        /** 「记住账号」记下的账号，仅用于登录页预填；与会话解耦，退出登录后仍保留 */
        const val KEY_SAVED_ID = "saved_apple_id"

        /** 「记住账号」开关状态 */
        const val KEY_REMEMBER = "remember_account"

        /** 生物识别解锁开关 */
        const val KEY_BIOMETRIC = "biometric_unlock"

        const val KEY_REGION = "region"

        /** 云盘浏览方式（LIST / GRID） */
        const val KEY_DRIVE_VIEW = "drive_view"

        /** 相册网格的缩略图边长（dp）；0 表示用户还没调过，界面按屏幕宽度给默认值 */
        const val KEY_PHOTO_CELL = "photo_cell_dp"

        /** 双指缩放的一次性提示是否已展示 */
        const val KEY_ZOOM_HINT = "photo_zoom_hint_shown"

        /** 相册缩略图能被双指拉到的极限：再小就点不准，再大就一屏一张 */
        const val MIN_PHOTO_CELL = 56f
        const val MAX_PHOTO_CELL = 240f

        const val PAGE = 60
    }

    private val prefs = app.getSharedPreferences("icloud_bridge", Context.MODE_PRIVATE)

    /**
     * 会话落盘一律走 Keystore 加密版；[sessionDir] 只作为旧明文的迁移来源。
     * 参见 [EncryptedSessionStore] 的类注释 —— 令牌泄露等于绕过 2FA 接管账号。
     */
    private val sessionDir = File(app.filesDir, "session")
    private val sessionStore = EncryptedSessionStore(app, sessionDir)

    var client: ICloudClient? = null
        private set

    // ---------------------------------------------------------------- 全局状态

    var stage by mutableStateOf<Stage>(Stage.Booting)
        private set

    var error by mutableStateOf<UiText?>(null)
        private set

    var notice by mutableStateOf<UiText?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    /** 生物识别锁定：true 时界面停在解锁页，不渲染任何账户数据 */
    var locked by mutableStateOf(false)
        private set

    // ---------------------------------------------------------------- 界面偏好

    var language by mutableStateOf(LocaleSupport.current(app))
        private set

    var biometricEnabled by mutableStateOf(prefs.getBoolean(KEY_BIOMETRIC, false))
        private set

    // ---------------------------------------------------------------- 浏览偏好

    /** 云盘用列表还是图标视图，随用户切换持久化 */
    var driveView by mutableStateOf(
        DriveView.entries.firstOrNull { it.name == prefs.getString(KEY_DRIVE_VIEW, null) }
            ?: DriveView.LIST,
    )
        private set

    /**
     * 相册缩略图的边长（dp），即双指缩放的直接结果。
     *
     * 0 = 用户还没捏过，由界面按窗口宽度给默认值（窄屏 104dp，宽屏 132dp）。
     * 之所以存「边长」而不是「列数」：列数在平滑缩放里是跳变的，边长才是连续量，
     * 双指过程才能做到跟手。列数由 LazyVerticalGrid 的 Adaptive 自己算。
     */
    var photoCellDp by mutableStateOf(prefs.getFloat(KEY_PHOTO_CELL, 0f))
        private set

    /**
     * 「双指可以捏」这条提示是否已经放过。
     *
     * 捏合缩放没有任何可见入口，不放一次提示就等于没做——用户永远不会去试。
     * 但每次进来都弹又很烦，所以整个安装周期只提示一次。
     */
    var zoomHintShown by mutableStateOf(prefs.getBoolean(KEY_ZOOM_HINT, false))
        private set

    // ---------------------------------------------------------------- 登录表单记忆

    /** 登录页预填的账号（「记住账号」勾选过才有值） */
    var savedAppleId by mutableStateOf("")
        private set

    var rememberAccount by mutableStateOf(true)
        private set

    /** 上次选择的区域，下次进登录页直接沿用 */
    var lastRegion by mutableStateOf(RegionChoice.CHINA)
        private set

    // ---------------------------------------------------------------- 照片

    var albums by mutableStateOf<List<Album>>(emptyList())
        private set

    var selectedAlbum by mutableStateOf<Album?>(null)
        private set

    var photos by mutableStateOf<List<PhotoAsset>>(emptyList())
        private set

    var photosLoading by mutableStateOf(false)
        private set

    var photosEnd by mutableStateOf(false)
        private set

    private var nextRank = 0

    // ---------------------------------------------------------------- 云盘

    var driveEntries by mutableStateOf<List<DriveEntry>>(emptyList())
        private set

    var driveStack by mutableStateOf<List<DriveEntry>>(emptyList())
        private set

    var driveLoading by mutableStateOf(false)
        private set

    /**
     * 正在传输的云盘文件。
     *
     * [opening] 区分两种意图：true = 下到缓存并交给系统应用打开，
     * false = 存进系统「下载」目录。界面上同一行要显示不同的文案与进度。
     */
    var driveTransfer by mutableStateOf<DriveTransfer?>(null)
        private set

    /** 查看器里「保存原图」的进度；null 表示当前没有进行中的保存 */
    var photoSave by mutableStateOf<PhotoSaveState?>(null)
        private set

    // ---------------------------------------------------------------- 上传

    var upload by mutableStateOf<UploadState?>(null)
        private set

    // ---------------------------------------------------------------- 存储空间

    var storage by mutableStateOf<StorageUsage?>(null)
        private set

    var storageLoading by mutableStateOf(false)
        private set

    var storageFailed by mutableStateOf(false)
        private set

    private var pendingAppleId: String = ""
    private var pendingRegion: RegionChoice = RegionChoice.CHINA

    init {
        bootstrap()
    }

    // ---------------------------------------------------------------- 启动

    /** 有可用会话就直接进主页，没有才回登录页 —— 这是「持续登录」体验的关键 */
    private fun bootstrap() {
        val appleId = prefs.getString(KEY_APPLE_ID, null)
        val regionName = prefs.getString(KEY_REGION, RegionChoice.CHINA.name)
        val region = runCatching { RegionChoice.valueOf(regionName.orEmpty()) }
            .getOrDefault(RegionChoice.CHINA)

        // 先把表单记忆恢复出来：无论走不走到登录页，登录页打开时就该是填好的
        lastRegion = region
        savedAppleId = prefs.getString(KEY_SAVED_ID, "").orEmpty()
        rememberAccount = prefs.getBoolean(KEY_REMEMBER, true)

        if (appleId.isNullOrBlank()) {
            stage = Stage.Login
            return
        }

        viewModelScope.launch {
            try {
                val c = ICloudClient.create(
                    appleId = appleId,
                    region = region.region,
                    store = sessionStore,
                )
                val account = retryNetwork("恢复会话") { c.connect() }
                println("[ICloudLogin] restore=${account != null} appleId=${maskAppleId(appleId)}")
                if (account == null) {
                    stage = Stage.Login
                    return@launch
                }
                client = c
                pendingAppleId = appleId
                pendingRegion = region
                stage = Stage.Ready(account)
                // 会话恢复成功但开了生物识别：先锁住，别让内容一闪而过
                locked = biometricEnabled && BiometricLock.isAvailable(getApplication())
                loadAlbums()
                loadMorePhotos()
                loadDrive(null)
            } catch (e: Throwable) {
                // 会话过期是最常见的情况，静默回登录页，不要用报错吓用户
                stage = Stage.Login
                if (e !is ICloudAuthException) error = describe(e)
            }
        }
    }

    // ---------------------------------------------------------------- 界面偏好

    fun chooseLanguage(value: AppLanguage) {
        language = value
        LocaleSupport.set(getApplication(), value)
    }

    fun updateBiometric(value: Boolean) {
        biometricEnabled = value
        prefs.edit().putBoolean(KEY_BIOMETRIC, value).apply()
        // 打开开关后立刻锁一次，让用户马上确认「确实生效了」
        locked = value && BiometricLock.isAvailable(getApplication())
    }

    fun unlock() {
        locked = false
    }

    // ---------------------------------------------------------------- 登录

    fun login(
        appleIdInput: String,
        password: String,
        choice: RegionChoice,
        remember: Boolean = true,
    ) {
        val appleId = appleIdInput.trim()
        if (appleId.isBlank() || password.isBlank()) {
            error = uiText(R.string.err_input_required)
            return
        }
        busy = true
        error = null

        // 表单记忆：与会话分开存，退出登录后账号仍在，下次不用重新输入
        lastRegion = choice
        rememberAccount = remember
        savedAppleId = if (remember) appleId else ""
        prefs.edit()
            .putBoolean(KEY_REMEMBER, remember)
            .putString(KEY_REGION, choice.name)
            .apply()
        prefs.edit().apply {
            if (remember) putString(KEY_SAVED_ID, appleId) else remove(KEY_SAVED_ID)
        }.apply()

        viewModelScope.launch {
            try {
                val c = ICloudClient.create(
                    appleId = appleId,
                    region = choice.region,
                    store = sessionStore,
                )
                // 全新登录前必须清掉上一条会话残留的 scnt / sessionId / Cookie：
                // 那些是旧会话的上下文，带着它去 /signin/init，苹果会先拒一次，
                // 用户看到的就是「第一次点登录报错、再点一次才进验证页」。
                c.http.resetSession()
                val result = retryNetwork("登录") { c.auth.signIn(password) }
                println("[ICloudLogin] signIn=$result")
                when (result) {
                    SignInResult.Completed -> {
                        client = c
                        pendingAppleId = appleId
                        pendingRegion = choice
                        finishLogin(c)
                    }

                    SignInResult.TwoFactorRequired -> {
                        client = c
                        pendingAppleId = appleId
                        pendingRegion = choice
                        stage = Stage.TwoFactor(phones = emptyList())
                        loadTrustedPhones(c)
                    }
                }
            } catch (e: Throwable) {
                error = describe(e)
                client = null
            } finally {
                busy = false
            }
        }
    }

    /**
     * 网络抖动自动重试（最多 3 次，退避 0.8s / 1.6s）。
     *
     * 只对**网络异常**重试：认证失败（密码错、被拒）绝不重试，
     * 那只会让苹果把账户判成撞库而触发安全硬化。
     */
    private suspend fun <T> retryNetwork(what: String, block: suspend () -> T): T {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (e: Throwable) {
                val retryable = e is ICloudNetworkException || e is IOException
                if (!retryable || attempt >= 2) throw e
                lastError = e
                val wait = 800L * (attempt + 1)
                println("[ICloudLogin] $what 网络失败（第 ${attempt + 1} 次），${wait}ms 后重试：${e.message}")
                delay(wait)
            }
        }
        throw lastError ?: ICloudAuthException("$what 失败，请重试")
    }

    private suspend fun finishLogin(c: ICloudClient) {
        val account = c.auth.accountLogin()
        val state = c.http.session
        println(
            "[ICloudLogin] accountLogin ok dsid=${account.dsid} " +
                "sessionToken=${state.sessionToken?.isNotBlank()} " +
                "trustToken=${state.trustToken?.isNotBlank()} " +
                "cookies=${c.http.cookieNamesFor("https://www.icloud.com").size}",
        )
        prefs.edit()
            .putString(KEY_APPLE_ID, pendingAppleId)
            .putString(KEY_REGION, pendingRegion.name)
            .apply()
        stage = Stage.Ready(account)
        loadAlbums()
        loadMorePhotos()
        loadDrive(null)
    }

    // ---------------------------------------------------------------- 双重认证

    /**
     * 拉取可信手机号。
     *
     * 这一步失败**不能静默**：界面若默默拿不到手机号，就只剩「受信设备」一条路，
     * 而换到安卓的用户往往没有别的苹果设备，等于卡死。所以失败原因要显示在页面上。
     */
    private fun loadTrustedPhones(c: ICloudClient) {
        val current = stage as? Stage.TwoFactor ?: return
        stage = current.copy(busy = true, phoneError = null)
        viewModelScope.launch {
            try {
                val phones = c.auth.trustedPhones()
                // 只记条数：可信手机号是用户的真实号码，不能进 logcat
                println("[ICloudLogin] trustedPhones=${phones.size} 个")
                val s = stage as? Stage.TwoFactor ?: return@launch
                stage = s.copy(
                    phones = phones,
                    busy = false,
                    phoneError = if (phones.isEmpty()) uiText(R.string.err_phone_empty) else null,
                )
            } catch (e: Throwable) {
                println("[ICloudLogin] trustedPhones 失败：${e.javaClass.simpleName} ${e.message}")
                val s = stage as? Stage.TwoFactor ?: return@launch
                stage = s.copy(busy = false, phoneError = describe(e))
            }
        }
    }

    /** 用户手动重试拉取手机号（网络刚恢复时很有用） */
    fun retryTrustedPhones() {
        val c = client ?: return
        loadTrustedPhones(c)
    }

    fun sendSms(phoneId: Int) {
        val c = client ?: return
        val current = stage as? Stage.TwoFactor ?: return
        stage = current.copy(busy = true)
        viewModelScope.launch {
            try {
                c.auth.sendSmsCode(phoneId)
                stage = (stage as? Stage.TwoFactor)?.copy(smsSent = true, busy = false) ?: stage
                notice = uiText(R.string.notice_sms_sent)
            } catch (e: Throwable) {
                stage = (stage as? Stage.TwoFactor)?.copy(busy = false) ?: stage
                error = describe(e)
            }
        }
    }

    fun verifySms(phoneId: Int, code: String) = verifyCode(code) { c ->
        c.auth.submitSmsCode(phoneId, code)
    }

    fun pushToDevice() {
        val c = client ?: return
        val current = stage as? Stage.TwoFactor ?: return
        stage = current.copy(busy = true)
        viewModelScope.launch {
            try {
                c.auth.triggerDevicePush()
                stage = (stage as? Stage.TwoFactor)?.copy(busy = false) ?: stage
                notice = uiText(R.string.notice_push_sent)
            } catch (e: Throwable) {
                stage = (stage as? Stage.TwoFactor)?.copy(busy = false) ?: stage
                error = describe(e)
            }
        }
    }

    fun verifyDeviceCode(code: String) = verifyCode(code) { c ->
        c.auth.submitDeviceCode(code)
    }

    private fun verifyCode(code: String, submit: suspend (ICloudClient) -> Boolean) {
        if (code.length < 6) {
            error = uiText(R.string.err_code_length)
            return
        }
        val c = client ?: return
        val current = stage as? Stage.TwoFactor ?: return
        stage = current.copy(busy = true)
        error = null
        viewModelScope.launch {
            try {
                if (!submit(c)) {
                    stage = (stage as? Stage.TwoFactor)?.copy(busy = false) ?: stage
                    error = uiText(R.string.err_code_wrong)
                    return@launch
                }
                finishLogin(c)
            } catch (e: Throwable) {
                stage = (stage as? Stage.TwoFactor)?.copy(busy = false) ?: stage
                error = describe(e)
            }
        }
    }

    /** 放弃本次登录，回到账号密码页（清掉半途会话，避免脏 Cookie 干扰下次） */
    fun cancelTwoFactor() {
        val c = client
        client = null
        stage = Stage.Login
        viewModelScope.launch { runCatching { c?.signOut() } }
    }

    // ---------------------------------------------------------------- 照片

    private fun loadAlbums() {
        val c = client ?: return
        viewModelScope.launch {
            try {
                val list = c.photos.albums()
                println("[ICloudPhotos] albums=${list.size}")
                albums = list
            } catch (e: Throwable) {
                println("[ICloudPhotos] albums 失败：${e.javaClass.simpleName} ${e.message}")
                albums = emptyList()
            }
        }
    }

    /**
     * 往照片墙塞一批假条目。
     *
     * 只给 debug 的布局检查页和 UI 测试用：真机验证双指缩放要先登录、再等 60 张缩略图，
     * 一条「手感」相关的测试如果依赖账号和网络，早晚会因为网络抽风变成假红。
     * 正常流程里没有任何地方调用它，`internal` 也挡住了模块外的使用。
     */
    internal fun seedPreviewPhotos(count: Int = 60) {
        photos = (1..count).map { i ->
            cn.yunqiao.icloud.model.PhotoAsset(
                recordName = "seed-$i",
                masterRecordName = "master-$i",
                filename = "IMG_$i.JPG",
                isFavorite = false,
                versions = emptyMap(),
            )
        }
        photosEnd = true
        albums = emptyList()
    }

    fun refreshPhotos() {
        photos = emptyList()
        nextRank = 0
        photosEnd = false
        loadMorePhotos()
    }

    fun selectAlbum(album: Album?) {
        if (selectedAlbum?.recordName == album?.recordName) return
        selectedAlbum = album
        photos = emptyList()
        nextRank = 0
        photosEnd = false
        loadMorePhotos()
    }

    /**
     * 记住双指缩放后的缩略图边长。
     *
     * 只在**手指抬起**时调一次，不在缩放过程中逐帧调：
     * `SharedPreferences.apply()` 每次都会排一次磁盘写入，逐帧调用会在缩放时堆出几百个待写任务，
     * 手感直接变卡。拖动过程中的连续值由界面自己拿 `mutableFloatStateOf` 顶着。
     */
    fun savePhotoCellDp(value: Float) {
        val clamped = value.coerceIn(MIN_PHOTO_CELL, MAX_PHOTO_CELL)
        if (photoCellDp == clamped) return
        photoCellDp = clamped
        prefs.edit().putFloat(KEY_PHOTO_CELL, clamped).apply()
    }

    fun markZoomHintShown() {
        if (zoomHintShown) return
        zoomHintShown = true
        prefs.edit().putBoolean(KEY_ZOOM_HINT, true).apply()
    }

    fun loadMorePhotos() {
        val c = client ?: return
        if (photosLoading || photosEnd) return
        photosLoading = true
        viewModelScope.launch {
            try {
                val album = selectedAlbum
                val page = if (album == null) {
                    c.photos.libraryAssets(startRank = nextRank, limit = PAGE)
                } else {
                    c.photos.albumAssets(album = album, startRank = nextRank, limit = PAGE)
                }
                println(
                    "[ICloudPhotos] assets=${page.assets.size} raw=${page.rawRecordCount} " +
                        "rank=${nextRank} album=${album?.name ?: "全部"}",
                )
                if (page.assets.isNotEmpty()) photos = photos + page.assets
                nextRank += page.rawRecordCount.coerceAtLeast(page.assets.size * 2)
                // 接口按 rank 升序返回（rank 0 = 最新），记录数少于请求量即说明到底
                if (page.rawRecordCount < PAGE * 2 || page.assets.isEmpty()) photosEnd = true
            } catch (e: Throwable) {
                println("[ICloudPhotos] 分页失败：${e.javaClass.simpleName} ${e.message}")
                if (isAuthFailure(e)) {
                    // 会话存在但缺少 CloudKit 授权（2FA 没走完就恢复过会话），只能重新登录
                    needsRelogin()
                } else {
                    error = describe(e)
                }
                photosEnd = true
            } finally {
                photosLoading = false
            }
        }
    }

    // ---------------------------------------------------------------- 上传

    /**
     * 把系统相册里选中的照片/视频上传进 **iCloud 照片图库**。
     *
     * 为什么先落到 cacheDir：苹果的上传接口要「按字节数预留上传位」，
     * 拿不到准确大小就没法预留；而 content:// 的流长度经常未知。落盘一次还能顺带
     * 把修改时间（EXIF 缺失时的拍摄时间兜底）一起带上。
     */
    fun uploadPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) {
            notice = uiText(R.string.upload_nothing)
            return
        }
        val c = client ?: return
        if (upload != null) return
        upload = UploadState(uris.size, 0, "", 0f, toDrive = false)

        viewModelScope.launch {
            var ok = 0
            var failed = 0
            var duplicate = 0
            var lastFailure: UiText? = null

            for ((index, uri) in uris.withIndex()) {
                var temp: File? = null
                var converted: File? = null
                try {
                    val pick = copyToCache(uri, index)
                    temp = pick.file
                    upload = UploadState(uris.size, index, pick.name, 0f, toDrive = false)
                    println("[ICloudUpload] ${pick.name} mime=${pick.mime}")

                    // 苹果只收 H.264 / HEVC 的视频（实测 AV1、MPEG-4 Part2、ProRes 全 415）。
                    // 与其让用户上传完看一句「不支持」，不如在这里先转成 H.264 —— 反正
                    // 手机上最贵的资源是用户的注意力，不是 CPU。
                    var source = pick.file
                    var uploadName = pick.name
                    var uploadMime = pick.mime
                    if (pick.mime?.startsWith("video/") == true) {
                        val profile = MediaInspector.inspect(pick.file)
                        if (profile != null && !MediaInspector.appleAccepts(profile.videoMime)) {
                            println(
                                "[ICloudUpload] ${pick.name} 是 ${profile.videoCodecLabel}" +
                                    "（${profile.width}x${profile.height}），苹果不收，开始本机转 H.264",
                            )
                            upload = UploadState(
                                uris.size, index, pick.name, 0f, toDrive = false,
                                phase = UploadPhase.TRANSCODING,
                            )
                            val out = File(
                                getApplication<Application>().cacheDir,
                                "tc_${System.currentTimeMillis()}_$index.mp4",
                            )
                            converted = out
                            val after = VideoTranscoder.transcode(pick.file, out) { p ->
                                upload = UploadState(
                                    uris.size, index, pick.name, p, toDrive = false,
                                    phase = UploadPhase.TRANSCODING,
                                )
                            }
                            source = out
                            uploadName = pick.name.replaceAfterLast('.', "mp4", pick.name)
                            uploadMime = "video/mp4"
                            println(
                                "[ICloudUpload] 转码完成 ${after.videoCodecLabel} " +
                                    "${out.length() / 1024} KB（原始 ${pick.file.length() / 1024} KB）",
                            )
                        }
                    }

                    upload = UploadState(uris.size, index, uploadName, 0f, toDrive = false)
                    val result = c.photos.upload(
                        file = source,
                        fileName = uploadName,
                        // 选择器给的 MIME 比按后缀猜的准（.mp4 既可能是 H.264 也可能是 HEVC）
                        mimeType = uploadMime,
                    ) { p ->
                        upload = UploadState(uris.size, index, uploadName, p, toDrive = false)
                    }
                    if (result.duplicate) duplicate++ else ok++
                } catch (e: Throwable) {
                    failed++
                    lastFailure = when (e) {
                        // 类型/编码不被接受：换文件才有用，重试没意义
                        is ICloudUnsupportedMediaException ->
                            uiText(R.string.err_media_unsupported, e.fileName ?: "")

                        is VideoTranscoder.TranscodeException ->
                            uiText(R.string.err_transcode_failed, e.message ?: "")

                        else -> uiText(R.string.err_upload_failed, e.message ?: e.javaClass.simpleName)
                    }
                    println("[ICloudUpload] 照片上传失败：${e.javaClass.simpleName} ${e.message}")
                } finally {
                    temp?.delete()
                    converted?.delete()
                }
                upload = UploadState(uris.size, index + 1, "", 0f, toDrive = false)
            }

            upload = null
            when {
                failed == 0 && ok == 0 && duplicate > 0 ->
                    notice = uiText(R.string.upload_indexing)

                failed == 0 && duplicate == 0 -> notice = uiText(R.string.upload_done, ok)

                failed == 0 -> notice = uiText(R.string.upload_done, ok + duplicate)

                ok + duplicate > 0 -> notice = uiText(R.string.upload_done_with_failures, ok + duplicate, failed)

                else -> error = lastFailure ?: uiText(R.string.err_upload_failed, "")

            }
            // 部分失败时把原因也说出来，否则用户只知道「失败了一个」，不知道为什么
            if (failed > 0 && ok + duplicate > 0) {
                lastFailure?.let { error = it }
            }
            // 图库要几秒索引，稍等一下再刷新，用户才看得到刚上传的图
            if (ok + duplicate > 0) {
                delay(2500)
                refreshPhotos()
            }
        }
    }

    /** 把任意文件上传到当前云盘目录 */
    fun uploadToDrive(uri: Uri) {
        val c = client ?: return
        if (upload != null) return
        val parentDocwsid = driveStack.lastOrNull()?.docwsid ?: DriveApi.ROOT_DOCWSID
        upload = UploadState(1, 0, "", 0f, toDrive = true)

        viewModelScope.launch {
            var temp: File? = null
            try {
                val pick = copyToCache(uri, 0)
                temp = pick.file
                upload = UploadState(1, 0, pick.name, 0f, toDrive = true)
                c.drive.upload(
                    file = pick.file,
                    fileName = pick.name,
                    parentDocwsid = parentDocwsid,
                ) { p ->
                    upload = UploadState(1, 0, pick.name, p, toDrive = true)
                }
                notice = uiText(R.string.upload_done, 1)
                refreshDrive()
            } catch (e: Throwable) {
                error = uiText(R.string.err_upload_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                temp?.delete()
                upload = null
            }
        }
    }

    private class LocalPick(val file: File, val name: String, val mime: String?)

    /** 把 content:// 复制到缓存目录，并尽量还原真实文件名与修改时间 */
    private fun copyToCache(uri: Uri, index: Int): LocalPick {
        val ctx = getApplication<Application>()
        val resolver = ctx.contentResolver

        var displayName: String? = null
        var modifiedSeconds: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                val dateIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                if (dateIndex >= 0 && !cursor.isNull(dateIndex)) {
                    modifiedSeconds = cursor.getLong(dateIndex)
                }
            }
        }

        val mime = resolver.getType(uri)
        var name = displayName?.takeIf { it.isNotBlank() }
            ?: "upload_${System.currentTimeMillis()}_$index"
        if (!name.contains('.')) {
            val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            if (!ext.isNullOrBlank()) name = "$name.$ext"
        }
        // 文件名会进 URL/JSON，去掉文件系统与 URL 的敏感字符
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").take(120)

        val temp = File(ctx.cacheDir, "up_${System.currentTimeMillis()}_$index")
        resolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("无法读取所选文件")
        if (temp.length() <= 0L) {
            temp.delete()
            throw IOException("文件为空：$safeName")
        }
        modifiedSeconds?.let { temp.setLastModified(it * 1000) }
        return LocalPick(temp, safeName, mime)
    }

    // ---------------------------------------------------------------- 云盘

    fun updateDriveView(value: DriveView) {
        if (driveView == value) return
        driveView = value
        prefs.edit().putString(KEY_DRIVE_VIEW, value.name).apply()
    }

    /**
     * 云盘条目的展示顺序：文件夹在前，同类按名字排。
     *
     * iOS「文件」把文件夹顶在最前面不是随便定的——用户进目录的第一件事通常是继续往下钻，
     * 而不是找某个具体文件。苹果返回的原始顺序是服务端决定的，混着排会让人反复扫。
     * 比较用 [String.CASE_INSENSITIVE_ORDER]，并单独处理数字段，
     * 这样「第 2 章」排在「第 10 章」前面，而不是按字符逐位比。
     */
    fun sortedDrive(): List<DriveEntry> = driveEntries.sortedWith(
        compareByDescending<DriveEntry> { it.isBrowsable }
            .thenComparator { a, b -> naturalCompare(a.name, b.name) },
    )

    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                // 去前导零后按长度比，长度相同再逐位比——避免 Long 溢出
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                val byLength = na.length.compareTo(nb.length)
                if (byLength != 0) return byLength
                val byDigits = na.compareTo(nb)
                if (byDigits != 0) return byDigits
                i = ei
                j = ej
            } else {
                val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    fun refreshDrive() = loadDrive(driveStack.lastOrNull()?.drivewsid)

    fun openFolder(entry: DriveEntry) {
        val id = entry.drivewsid ?: return
        driveStack = driveStack + entry
        loadDrive(id)
    }

    /**
     * 点云盘条目的统一入口。
     *
     * 是不是「能进去」不能只看 `type == FOLDER`：App 的 iCloud 文档容器类型是
     * `APP_LIBRARY`，同样可以下钻；以前把它当文件走下载，点一下就报错。
     */
    fun openDriveEntry(entry: DriveEntry) {
        if (entry.isBrowsable) openFolder(entry) else openDriveFile(entry)
    }

    fun driveUp() {
        if (driveStack.isEmpty()) return
        driveStack = driveStack.dropLast(1)
        loadDrive(driveStack.lastOrNull()?.drivewsid)
    }

    private fun loadDrive(drivewsid: String?) {
        val c = client ?: return
        driveLoading = true
        viewModelScope.launch {
            try {
                driveEntries = c.drive.listChildren(drivewsid ?: DriveApi.ROOT)
            } catch (e: Throwable) {
                if (isAuthFailure(e)) {
                    needsRelogin()
                } else {
                    error = describe(e)
                }
                driveEntries = emptyList()
            } finally {
                driveLoading = false
            }
        }
    }

    /**
     * 点云盘文件：**直接打开**，而不是先存到「下载」再让用户自己去翻。
     *
     * 落点放在 `cache/drive`，由 FileProvider 临时授权给系统查看器；
     * 同一文件第二次点会命中缓存，秒开。
     */
    fun openDriveFile(entry: DriveEntry) {
        val c = client ?: return
        val docwsid = entry.docwsid
        if (docwsid.isNullOrBlank()) {
            // 以前这里是 `?: return`：点了完全没反应，用户只会以为「打不开」
            error = uiText(R.string.err_drive_no_docwsid, entry.name)
            return
        }
        if (driveTransfer != null) return
        driveTransfer = DriveTransfer(entry.name, 0f, opening = true)
        viewModelScope.launch {
            try {
                val file = fetchToCache(c, entry, docwsid) { p ->
                    driveTransfer = DriveTransfer(entry.name, p, opening = true)
                }
                driveTransfer = null
                if (!FileOpener.open(getApplication(), file, entry.name)) {
                    // 手机上没有能处理这种格式的应用（.band/.dwg 很常见）：
                    // 退回「保存到下载目录」，并如实说明原因
                    val target = saveToDownloads(entry.name) { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    notice = uiText(R.string.notice_saved_no_viewer, target)
                }
            } catch (e: Throwable) {
                driveTransfer = null
                error = describe(e)
            }
        }
    }

    /** 行尾按钮：明确「保存到系统下载目录」，留在本地随时可用 */
    fun saveDriveFile(entry: DriveEntry) {
        val c = client ?: return
        val docwsid = entry.docwsid
        if (docwsid.isNullOrBlank()) {
            error = uiText(R.string.err_drive_no_docwsid, entry.name)
            return
        }
        if (driveTransfer != null) return
        driveTransfer = DriveTransfer(entry.name, 0f, opening = false)
        viewModelScope.launch {
            try {
                val target = saveToDownloads(entry.name) { out ->
                    streamTo(c, docwsid, entry, out) { p ->
                        driveTransfer = DriveTransfer(entry.name, p, opening = false)
                    }
                }
                notice = uiText(R.string.notice_saved_to, target)
            } catch (e: Throwable) {
                error = describe(e)
            } finally {
                driveTransfer = null
            }
        }
    }

    // ------------------------------------------------------------ 保存原图到相册

    /**
     * 把查看器里正在看的这张**原图**存进系统相册。
     *
     * 与云盘的「保存到下载目录」是两回事：这里是给人在系统相册 App 里看的，
     * 所以进 `Pictures/iCloudBridge`（视频进 `Movies/iCloudBridge`），
     * MIME 必须准确 —— 写错的话相册不会收录，只能算个无法打开的普通文件。
     *
     * HEIC 原样保存，不转 JPEG：用户要的是「原文件」，转码会掉画质和 EXIF。
     */
    fun saveOriginalToGallery(asset: PhotoAsset) {
        val c = client ?: return
        if (photoSave != null) return
        val version = asset.versions[AssetSize.ORIGINAL] ?: asset.bestVersion
        val url = version?.downloadUrl ?: run {
            error = uiText(R.string.err_photo_no_download)
            return
        }

        photoSave = PhotoSaveState(asset.recordName, 0f, done = false)
        viewModelScope.launch {
            try {
                val target = saveToGallery(asset.filename, asset.isVideo) { out ->
                    // downloadTo 是同步阻塞的 OkHttp 调用，saveToGallery 已经在 IO 线程上
                    c.http.downloadTo(url, out) { received, total ->
                        photoSave = PhotoSaveState(
                            recordName = asset.recordName,
                            progress = if (total > 0) received.toFloat() / total else 0f,
                            done = false,
                        )
                    }
                }
                photoSave = PhotoSaveState(asset.recordName, 1f, done = true)
                notice = uiText(R.string.notice_photo_saved, target)
            } catch (e: Throwable) {
                photoSave = null
                error = describe(e)
            }
        }
    }

    private suspend fun saveToGallery(
        name: String,
        isVideo: Boolean,
        write: suspend (OutputStream) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val cleanName = safeFileName(name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val collection = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val relativePath = if (isVideo) {
                "${Environment.DIRECTORY_MOVIES}/iCloudBridge"
            } else {
                "${Environment.DIRECTORY_PICTURES}/iCloudBridge"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
                put(MediaStore.MediaColumns.MIME_TYPE, galleryMimeOf(cleanName, isVideo))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                // 写完之前对相册不可见，避免半截文件被扫描进去
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IOException("无法在相册中创建文件")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("无法写入相册文件")
                out.use { write(it) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Throwable) {
                // 和下载目录一样：失败要抹掉这条 pending 记录，否则相册里留一个空条目
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            return@withContext if (isVideo) "Movies/iCloudBridge/$cleanName"
            else "Pictures/iCloudBridge/$cleanName"
        }

        // Android 8/9 没有 RELATIVE_PATH，只能自己建目录再靠相册扫描
        val base = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val dir = File(Environment.getExternalStoragePublicDirectory(base), "iCloudBridge")
            .apply { mkdirs() }
        val file = File(dir, cleanName)
        val tmp = File(dir, "$cleanName.part")
        try {
            tmp.outputStream().use { write(it) }
            if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
        } finally {
            tmp.delete()
        }
        file.absolutePath
    }

    /** 相册收录靠 MIME：HEIC / HEIF 要写对，否则系统不会把它当图片 */
    private fun galleryMimeOf(name: String, isVideo: Boolean): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "dng" -> "image/x-adobe-dng"
            "mov" -> "video/quicktime"
            "mp4" -> "video/mp4"
            "m4v" -> "video/x-m4v"
            else -> MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext)
                ?: if (isVideo) "video/mp4" else "image/jpeg"
        }
    }

    // ------------------------------------------------------------ 云盘下载内部

    private suspend fun streamTo(
        c: ICloudClient,
        docwsid: String,
        entry: DriveEntry,
        out: OutputStream,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val url = c.drive.downloadUrl(docwsid) ?: throw DriveEmptyFileException(entry.name)
        // 关键：downloadTo 是**同步阻塞**的 OkHttp 调用，不自己切线程。
        // 之前的写法在 viewModelScope（主线程）里直接调，安卓立刻抛
        // NetworkOnMainThreadException —— 这就是「云盘点文件没反应」的真正原因。
        // downloadUrl 内部有 withContext(IO) 只保护了它自己那一步，下载本身没被保护。
        c.http.downloadTo(url, out) { received, total ->
            onProgress(if (total > 0) received.toFloat() / total else 0f)
        }
        Unit
    }

    /** 下载到 cache/drive，返回本地文件；同一版本直接复用，避免重复下几十 MB */
    private suspend fun fetchToCache(
        c: ICloudClient,
        entry: DriveEntry,
        docwsid: String,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val dir = File(ctx.cacheDir, "drive").apply { mkdirs() }
        val target = File(dir, safeFileName(entry.name))
        // 用「指纹文件」判断缓存是否还有效，而不是拿目录里显示的大小去比：
        // iWork 包（.band/.pages）下载下来是 zip，字节数和列表里的 size 对不上，
        // 按大小比会永远判失效 —— 每次点开都重下 30 MB。
        val stamp = File(dir, target.name + ".stamp")
        val fingerprint = "${entry.docwsid}:${entry.sizeBytes}:${entry.modifiedAt}"
        val cached = target.exists() &&
            runCatching { stamp.readText() }.getOrNull() == fingerprint
        if (cached) {
            onProgress(1f)
            return@withContext target
        }
        // 先写临时文件再改名：中途失败不会把上一个能用的缓存覆盖成半截文件
        val tmp = File(dir, target.name + ".part")
        try {
            tmp.outputStream().use { out -> streamTo(c, docwsid, entry, out, onProgress) }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            runCatching { stamp.writeText(fingerprint) }
            target
        } finally {
            tmp.delete()
        }
    }

    /**
     * 文件名净化：云盘里可能出现 `/`、`:`、换行甚至控制字符，
     * 直接拿去当本地文件名会抛 FileNotFoundException 或建出奇怪的嵌套目录。
     */
    private fun safeFileName(name: String): String {
        val cleaned = name.map { ch ->
            if (ch.isISOControl() || ch in "/\\:*?\"<>|") '_' else ch
        }.joinToString("").trim().trimEnd('.')
        return cleaned.ifBlank { "file" }.take(120)
    }

    private suspend fun saveToDownloads(
        name: String,
        write: suspend (OutputStream) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                // 带上 MIME 与相对路径：缺 RELATIVE_PATH 时部分 ROM 会把文件丢到根目录，
                // 缺 MIME 时相册/文件管理器不会给出「打开」入口
                put(MediaStore.Downloads.MIME_TYPE, FileOpener.mimeOf(name))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在下载目录创建文件")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("无法写入下载文件")
                out.use { write(it) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Throwable) {
                // 失败必须删掉这条 pending 记录：否则「下载」目录里会留下一个
                // 0 字节、点不开、也删不掉的幽灵文件，下次同名下载还会被它挤成 (1)
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            return@withContext "下载/$name"
        }
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(ctx.filesDir, "downloads").also { it.mkdirs() }
        val file = File(dir, safeFileName(name))
        // 同样先写临时文件：磁盘写满时不能把已有的同名文件毁掉
        val tmp = File(dir, file.name + ".part")
        try {
            val out = tmp.outputStream()
            out.use { write(it) }
            if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
        } finally {
            tmp.delete()
        }
        file.absolutePath
    }

    // ---------------------------------------------------------------- 存储空间

    fun loadStorage() {
        val c = client ?: return
        if (storageLoading) return
        storageLoading = true
        storageFailed = false
        viewModelScope.launch {
            try {
                storage = c.account.storageUsage()
            } catch (e: Throwable) {
                println("[ICloudAccount] storage 失败：${e.javaClass.simpleName} ${e.message}")
                storageFailed = true
            } finally {
                storageLoading = false
            }
        }
    }

    // ---------------------------------------------------------------- 会话

    fun signOut() {
        val c = client
        viewModelScope.launch {
            runCatching { c?.signOut() }
            // 只清「会话」，不清「记住的账号」—— 否则退出一次账号就白记了
            prefs.edit().remove(KEY_APPLE_ID).apply()
            client = null
            albums = emptyList()
            photos = emptyList()
            driveEntries = emptyList()
            driveStack = emptyList()
            selectedAlbum = null
            storage = null
            locked = false
            stage = Stage.Login
        }
    }

    fun clearError() {
        error = null
    }

    /** 业务接口 401 说明会话缺少 CloudKit 授权，留在主页也没有任何数据可看 */
    private fun isAuthFailure(e: Throwable): Boolean =
        e is ICloudApiException && e.statusCode == 401

    /**
     * 日志里给 Apple ID 打码：`u***r@example.com`。
     *
     * 完整账号属于个人信息，logcat 在 debug 包里对所有 adb 连接可见；
     * 保留首尾各一个字符就够了——排查时能认出「是我哪个号」，又不足以拿去撞库。
     */
    private fun maskAppleId(id: String): String {
        val at = id.indexOf('@')
        if (at <= 1) return "***"
        return id.first() + "***" + id[at - 1] + id.substring(at)
    }

    private fun needsRelogin() {
        val c = client
        client = null
        albums = emptyList()
        photos = emptyList()
        driveEntries = emptyList()
        driveStack = emptyList()
        selectedAlbum = null
        locked = false
        prefs.edit().remove(KEY_APPLE_ID).apply()
        stage = Stage.Login
        error = uiText(R.string.err_relogin)
        viewModelScope.launch { runCatching { c?.signOut() } }
    }

    fun clearNotice() {
        notice = null
    }

    /**
     * 异常 → 可翻译文案。
     *
     * 模板从资源里取，苹果的原始消息（通常是英文）只作为参数塞进 %1$s：
     * 既不丢诊断信息，也不会整句英文糊在中文界面上。
     */
    private fun describe(e: Throwable): UiText = when (e) {
        is ICloudRegionMismatchException -> uiText(R.string.err_region_mismatch)

        is DriveEmptyFileException -> uiText(R.string.err_drive_empty, e.fileName)

        is ICloudNetworkException -> uiText(R.string.err_network, e.message.orEmpty())

        is ICloudAuthException -> uiText(R.string.err_auth, e.message.orEmpty())

        else -> uiText(R.string.err_unknown, e.message ?: e.javaClass.simpleName)
    }
}
