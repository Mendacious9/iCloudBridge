package cn.yunqiao.icloud.model

import kotlinx.serialization.Serializable

/**
 * 会话状态：本文件可被序列化后加密存储（安卓端存入 Keystore 加密的 DataStore）。
 * 注意：不包含账户密码，只有会话令牌与信任令牌。
 */
@Serializable
data class SessionState(
    val appleId: String,
    val clientId: String,
    val scnt: String? = null,
    val sessionId: String? = null,
    val sessionToken: String? = null,
    val trustToken: String? = null,
    val accountCountry: String? = null,
    val dsid: String? = null,
)

/** iCloud 服务条目（webservices 字段） */
@Serializable
data class WebService(
    val url: String? = null,
    val status: String? = null,
)

/** 账户信息（accountLogin 返回的 dsInfo 摘要） */
data class AccountInfo(
    val appleId: String,
    val fullName: String?,
    val dsid: String,
    val region: String,
    val webservices: Map<String, WebService>,
) {
    fun serviceUrl(key: String): String? = webservices[key]?.url

    val availableServices: List<String>
        get() = webservices.filterValues { it.status == "active" }.keys.sorted()
}

/** 相册 */
data class Album(
    val recordName: String,
    val name: String,
    val isUserAlbum: Boolean = true,
    /** 该相册对应的 CloudKit zone 名，通常等于 recordName */
    val zoneName: String = recordName,
)

/** 照片/视频资产的下载信息（某一分辨率版本） */
data class AssetVersion(
    val filename: String,
    val fileType: String?,
    val sizeBytes: Long?,
    val checksum: String?,
    val downloadUrl: String,
)

/** 照片/视频资产 */
data class PhotoAsset(
    val recordName: String,
    val masterRecordName: String,
    val filename: String,
    val isFavorite: Boolean,
    val versions: Map<AssetSize, AssetVersion>,
    /** 拍摄时间（epoch millis），排序与分页依赖它 */
    val assetDateMillis: Long? = null,
    val isVideo: Boolean = false,
    /**
     * 视频时长（**秒**）。
     *
     * 注意单位换算：苹果在 `CPLAsset.duration` 里给的是**毫秒**（实测
     * IMG_0950.MOV = 2633，对应 4.8 MB 的一段 4K 慢动作，真实长度 2.6 秒）。
     * 当成秒用就会显示成 `00:43:53`，看上去像数据错了 —— 转换在解析层就做完，
     * 上层一律按秒处理。
     */
    val durationSeconds: Double? = null,
    val recordChangeTag: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /**
     * 原图像素尺寸。
     *
     * 必须用它而不是解码出来的位图尺寸：列表和查看器显示的都是苹果转码的
     * 中等图（一两千像素），拿那个当「尺寸」会和旁边「文件大小 46 MB」自相矛盾。
     */
    val originalWidth: Int? = null,
    val originalHeight: Int? = null,
    /**
     * 视频的静态封面（若苹果给了 `resJPEGThumbRes`）。
     *
     * 视频的 [AssetSize.THUMB] 指向 `resVidSmall`，那是个 **mp4 小视频**，不是图片，
     * 不能直接当缩略图解码。有封面就用它；没有则客户端从 resVidSmall 里抽第一帧。
     */
    val posterUrl: String? = null,
    /**
     * 实况照片（Live Photo）那段动态影片的下载地址。
     *
     * 实况照片本身是照片（`isVideo = false`），但苹果会附一段约 2~3 秒的
     * H.264 影片。这里存它的地址，查看器长按时才去下载播放；为 null 表示普通照片。
     *
     * 判定不能只看「有没有 `resVid*` 资源」—— 这正是之前把它误判成视频、
     * 网格里出现「有 ▶ 没时长」的原因。
     */
    val liveVideoUrl: String? = null,
) {
    val bestVersion: AssetVersion?
        get() = versions[AssetSize.ORIGINAL] ?: versions[AssetSize.MEDIUM]

    val displaySizeBytes: Long? get() = bestVersion?.sizeBytes
}

enum class AssetSize { ORIGINAL, MEDIUM, THUMB }

/** 云盘条目 */
data class DriveEntry(
    val name: String,
    val type: DriveEntryType,
    val sizeBytes: Long?,
    val modifiedAt: String?,
    val docwsid: String?,
    val zone: String,
    val childCount: Int?,
    /** 文件夹导航要用它（形如 FOLDER::com.apple.CloudDocs::<uuid>） */
    val drivewsid: String? = null,
) {
    val isFolder: Boolean get() = type == DriveEntryType.FOLDER

    /**
     * 能否继续下钻。
     *
     * 除了普通文件夹，iCloud 还会给每个 App 的文档容器返回 `APP_LIBRARY`
     * （界面上看到的是「Pages」「GarageBand for iOS」这类条目）。它们没有扩展名、
     * `numberOfItems` 也常常缺失，只按 [isFolder] 判断的话会被当成文件去下载，
     * 结果是点一下必然失败。iOS 自己也是把这类条目画成文件夹让人点进去的。
     */
    val isBrowsable: Boolean
        get() = type == DriveEntryType.FOLDER || type == DriveEntryType.APP_LIBRARY
}

enum class DriveEntryType { FOLDER, FILE, APP_LIBRARY, UNKNOWN }
