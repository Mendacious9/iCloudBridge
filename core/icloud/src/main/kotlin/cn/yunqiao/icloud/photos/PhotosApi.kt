package cn.yunqiao.icloud.photos

import cn.yunqiao.icloud.ICloudApiException
import cn.yunqiao.icloud.ICloudProtocolException
import cn.yunqiao.icloud.ICloudUnsupportedMediaException
import cn.yunqiao.icloud.auth.ICloudAuth
import cn.yunqiao.icloud.http.ICloudHttp
import cn.yunqiao.icloud.internal.arr
import cn.yunqiao.icloud.internal.asObj
import cn.yunqiao.icloud.internal.fieldBool
import cn.yunqiao.icloud.internal.fieldObj
import cn.yunqiao.icloud.internal.fieldStr
import cn.yunqiao.icloud.internal.obj
import cn.yunqiao.icloud.internal.str
import cn.yunqiao.icloud.model.Album
import cn.yunqiao.icloud.model.AssetSize
import cn.yunqiao.icloud.model.AssetVersion
import cn.yunqiao.icloud.model.PhotoAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Base64
import java.util.TimeZone
import java.util.UUID

/** 一页资产查询结果 */
data class AssetPage(
    val assets: List<PhotoAsset>,
    /** 原始记录数（CPLAsset + CPLMaster 合计），用于判断是否还有下一页 */
    val rawRecordCount: Int,
    val syncToken: String? = null,
) {
    val isEmpty: Boolean get() = assets.isEmpty()
}

/**
 * 一次照片上传的登记结果。
 *
 * [duplicate] 为 true 表示苹果判定「图库里已有同一张」，此时不会新建资产，
 * 而是把已存在资产的记录名回传 —— 这是正常的，不是错误。
 */
data class UploadedPhoto(
    val masterRecordName: String?,
    val assetRecordName: String?,
    val jobId: String?,
    val duplicate: Boolean,
)

/**
 * iCloud 照片服务（CloudKit Web Services 私有库）。
 *
 * 结构：所有资产都在 `PrimarySync` 这一个 zone 里，存两类记录 ——
 * `CPLMaster`（原始文件/各分辨率元数据）与 `CPLAsset`（拍摄时间、收藏、所属相册等），
 * 两者用 `CPLAsset.fields.masterRef` 关联。查询时苹果把成对记录一起返回，
 * 需要自己按 masterRef 配对。
 *
 * 用户相册不是独立 zone（实测会 ZONE_NOT_FOUND），而是在同一 zone 内用
 * `CPLContainerRelationLiveByAssetDate` + `parentId` 过滤得到该相册的资产。
 */
class PhotosApi(
    private val http: ICloudHttp,
    private val auth: ICloudAuth,
) {

    private fun serviceBase(): String {
        val base = auth.requireService(
            "ckdatabasews",
            "照片服务不可用：请在 iCloud 设置中开启「iCloud 照片」，并确认账户未开启「高级数据保护」",
        ).trimEnd('/')
        return "$base/database/1/com.apple.photos.cloud/production/private"
    }

    private fun baseParams(): Map<String, String> =
        http.apiParams(mapOf("remapEnums" to "true", "getCurrentSyncToken" to "true"))

    // ------------------------------------------------------------------ 查询

    /** 照片库索引是否就绪；返回 "FINISHED" 表示可正常查询 */
    suspend fun indexPathingState(): String? = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonObject("query") { put("recordType", PhotoQuery.RECORD_INDEXING) }
            putJsonObject("zoneID") { put("zoneName", PhotoQuery.LIBRARY_ZONE) }
        }
        runCatching {
            post("${serviceBase()}/records/query", payload, baseParams())
                .arr("records")?.firstOrNull()?.asObj()
                ?.obj("fields")?.obj("state")?.get("value")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrNull()
    }

    /** 用户自建相册列表（智能相册不在其中） */
    suspend fun albums(): List<Album> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonObject("query") { put("recordType", PhotoQuery.RECORD_ALBUMS) }
            putJsonObject("zoneID") { put("zoneName", PhotoQuery.LIBRARY_ZONE) }
        }
        val records = post("${serviceBase()}/records/query", payload, baseParams())
            .arr("records") ?: JsonArray(emptyList())

        records.mapNotNull { element ->
            val record = element.asObj() ?: return@mapNotNull null
            val recordName = record.str("recordName") ?: return@mapNotNull null
            if (recordName == "----Root-Folder----" || recordName == "----Project-Root-Folder----") {
                return@mapNotNull null
            }
            // 名字为空的目录记录跳过（苹果偶尔返回残缺记录）
            val encoded = record.fieldStr("albumNameEnc") ?: return@mapNotNull null
            if (record.fieldBool("isDeleted") == true) return@mapNotNull null

            Album(
                recordName = recordName,
                name = decodeBase64Text(encoded) ?: recordName,
                zoneName = PhotoQuery.LIBRARY_ZONE,
            )
        }
    }

    /**
     * 拉一页照片库资产。
     *
     * **顺序语义（实测，与直觉相反）**：本接口的 `ASCENDING` 指的是「索引 rank 升序」，
     * 而 rank 0 就是最新的一条 —— 所以 `ASCENDING + startRank=0` 返回的是**最新在前**，
     * startRank 递增即向更旧的方向翻页。
     *
     * `DESCENDING` 需要从「总数 - 1」开始才有意义，直接传 0 只会返回 1 条；而 Hyperion
     * 计数接口在该账号上返回 0，拿不到可靠总数。因此**不要用 DESCENDING 做「最新」**，
     * 需要「旧 → 新」的顺序时由调用方在内存中反转即可。
     *
     * @param startRank 起始 rank（0 = 最新），用于分页
     * @param limit     期望资产数；请求时会 ×2，因为返回的是 Master + Asset 成对记录
     * @param descending 仅在明确知道尾部 rank 时才用；一般应保持 false
     */
    suspend fun libraryAssets(
        startRank: Int = 0,
        limit: Int = 100,
        descending: Boolean = false,
    ): AssetPage = withContext(Dispatchers.IO) {
        queryAssets(
            recordType = PhotoQuery.RECORD_LIBRARY,
            startRank = startRank,
            limit = limit,
            descending = descending,
            extraFilters = emptyList(),
        )
    }

    /** 拉一页某个用户相册内的资产 */
    suspend fun albumAssets(
        album: Album,
        startRank: Int = 0,
        limit: Int = 100,
        descending: Boolean = false,
    ): AssetPage = withContext(Dispatchers.IO) {
        val parentFilter = buildJsonObject {
            put("fieldName", "parentId")
            putJsonObject("fieldValue") {
                put("type", "STRING")
                put("value", album.recordName)
            }
            put("comparator", "EQUALS")
        }
        queryAssets(
            recordType = PhotoQuery.RECORD_ALBUM_RELATION,
            startRank = startRank,
            limit = limit,
            descending = descending,
            extraFilters = listOf(parentFilter),
        )
    }

    /**
     * 照片库总资产数（内部 Hyperion 计数接口）。
     *
     * 注意：该接口在本项目实测的账号上返回 **0**（索引计数未维护），因此结果 ≤0 时统一
     * 返回 null，调用方不能依赖它做分页。分页请用 `startRank` 递增直到返回空页。
     */
    suspend fun itemCount(): Int? = withContext(Dispatchers.IO) {
        val response = postRaw(
            "${serviceBase()}/internal/records/query/batch",
            countPayload(),
            baseParams(),
        )
        runCatching {
            response.jsonObject()
                .arr("batch")?.firstOrNull()?.asObj()
                ?.arr("records")?.firstOrNull()?.asObj()
                ?.obj("fields")?.obj("itemCount")?.get("value")
                ?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
        }.getOrNull()?.takeIf { it > 0 }
    }

    /** 诊断用：计数接口的原始响应，排查 itemCount 为 0/null 时使用 */
    suspend fun itemCountDebug(): String = withContext(Dispatchers.IO) {
        postRaw("${serviceBase()}/internal/records/query/batch", countPayload(), baseParams()).body
    }

    private fun countPayload(): JsonObject = buildJsonObject {
        put(
            "batch",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("resultsLimit", 1)
                        putJsonObject("query") {
                            putJsonObject("filterBy") {
                                put("fieldName", "indexCountID")
                                putJsonObject("fieldValue") {
                                    put("type", "STRING_LIST")
                                    put(
                                        "value",
                                        buildJsonArray { add(PhotoQuery.RECORD_LIBRARY) },
                                    )
                                }
                                put("comparator", "IN")
                            }
                            put("recordType", PhotoQuery.RECORD_COUNT)
                        }
                        put("zoneWide", true)
                        putJsonObject("zoneID") { put("zoneName", PhotoQuery.LIBRARY_ZONE) }
                    },
                )
            },
        )
    }

    /**
     * 最近的照片/视频（首页用）。
     *
     * 由于接口的 `ASCENDING` 本身就是「最新在前」（见 [libraryAssets] 的顺序说明），
     * 取首页即可，无需倒序查询。
     */
    suspend fun latest(limit: Int = 60): List<PhotoAsset> = withContext(Dispatchers.IO) {
        libraryAssets(startRank = 0, limit = limit, descending = false).assets
    }

    private fun queryAssets(
        recordType: String,
        startRank: Int,
        limit: Int,
        descending: Boolean,
        extraFilters: List<JsonObject>,
    ): AssetPage {
        val payload = buildJsonObject {
            putJsonObject("query") {
                put("recordType", recordType)
                put(
                    "filterBy",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("fieldName", "startRank")
                                putJsonObject("fieldValue") {
                                    put("type", "INT64")
                                    put("value", startRank)
                                }
                                put("comparator", "EQUALS")
                            },
                        )
                        add(
                            buildJsonObject {
                                put("fieldName", "direction")
                                putJsonObject("fieldValue") {
                                    put("type", "STRING")
                                    put("value", if (descending) "DESCENDING" else "ASCENDING")
                                }
                                put("comparator", "EQUALS")
                            },
                        )
                        extraFilters.forEach { add(it) }
                    },
                )
            }
            put("resultsLimit", limit * 2)
            put("desiredKeys", buildJsonArray { PhotoQuery.DESIRED_KEYS.forEach { add(it) } })
            putJsonObject("zoneID") { put("zoneName", PhotoQuery.LIBRARY_ZONE) }
        }

        val body = post("${serviceBase()}/records/query", payload, baseParams())
        val records = body.arr("records") ?: JsonArray(emptyList())
        return AssetPage(
            assets = pairAssets(records),
            rawRecordCount = records.size,
            syncToken = body.str("syncToken"),
        )
    }

    private fun post(url: String, payload: JsonObject, params: Map<String, String>): JsonObject {
        val response = postRaw(url, payload, params)
        if (!response.isSuccessful) {
            throw ICloudApiException(
                response.status,
                response.body,
                "照片查询失败：HTTP ${response.status} ${response.body.take(200)}",
            )
        }
        return response.jsonObject()
    }

    private fun postRaw(
        url: String,
        payload: JsonObject,
        params: Map<String, String>,
    ): cn.yunqiao.icloud.http.HttpResponse = http.postJson(
        url,
        payload.toString(),
        http.apiHeaders(mapOf("Content-type" to "text/plain")),
        params,
    )

    // ------------------------------------------------------------------ 上传

    /**
     * 把本地文件真正上传进 **iCloud 照片图库**（不是云盘）。
     *
     * 苹果在 2026-08 撤掉了单次 POST 的 `uploadimagews`（现在返回 410 Gone），
     * 现行协议是三次请求、两个域名：
     *
     * 1. `POST {photosupload}/photosupload/createUploadUrl`
     *    —— 先用「客户端 UUID → 字节数」换一个按尺寸预留的签名地址；
     * 2. `POST <预留地址>` —— 把原始字节写到内容主机。该地址自带 token，
     *    不能带 `Content-Type: application/json`，否则被拒；
     * 3. `POST {photosupload}/photosupload/putAsset` —— 用上一步回执登记成资产，
     *    响应直接给出 `cplMaster` / `cplAsset` 记录名。
     *
     * 注意两点实测行为：
     * - 同一张图重复上传不报错，苹果返回 409 + 已有资产的记录名（[UploadedPhoto.duplicate]）；
     * - 登记成功 ≠ 立刻可查，CloudKit 还要索引几秒，列表要稍后刷新才看得到。
     */
    suspend fun upload(
        file: File,
        fileName: String = file.name,
        mimeType: String? = null,
        onProgress: (Float) -> Unit = {},
    ): UploadedPhoto = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0L) {
            throw ICloudProtocolException("文件不存在或为空：$fileName")
        }
        val base = auth.requireService(
            "photosupload",
            "照片上传服务不可用：请确认账户已在 iCloud 设置中开启「iCloud 照片」",
        ).trimEnd('/')

        val clientUuid = UUID.randomUUID().toString()

        // ① 预留上传位（按字节数计量）
        val createBody = buildJsonObject {
            put("zoneName", PhotoQuery.LIBRARY_ZONE)
            putJsonObject("assets") { put(clientUuid, file.length()) }
        }
        val created = post("$base/photosupload/createUploadUrl", createBody, http.apiParams())
        val target = created.obj("uploadUrls")?.str(clientUuid)
            ?: throw ICloudProtocolException("苹果没有返回上传地址：${created.toString().take(200)}")

        // ② 写字节到内容主机
        val uploaded = http.postFile(
            url = target,
            file = file,
            contentType = mimeType ?: guessMimeType(fileName),
            onProgress = { sent, total ->
                onProgress(if (total > 0) sent.toFloat() / total else 0f)
            },
        )
        if (!uploaded.isSuccessful) {
            throw ICloudApiException(
                uploaded.status,
                uploaded.body,
                if (uploaded.status == 415) {
                    "苹果不接受这种文件类型（$fileName）：网页版图库只保证支持 JPEG / HEIC / PNG / MP4 / MOV"
                } else {
                    "上传文件失败：HTTP ${uploaded.status} ${uploaded.body.take(200)}"
                },
            )
        }
        val receipt = uploaded.jsonObject().obj("singleFile")
            ?: throw ICloudProtocolException("上传回执缺失：${uploaded.body.take(200)}")

        // ③ 登记为图库资产
        val tz = TimeZone.getDefault()
        // 苹果网页端用的是 JS 的 getTimezoneOffset 约定（UTC 减本地），东八区要发 -480 而不是 480
        val offsetMinutes = -(tz.getOffset(System.currentTimeMillis()) / 60_000)
        val putBody = buildJsonObject {
            put("zoneName", PhotoQuery.LIBRARY_ZONE)
            put(
                "files",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("fileName", fileName)
                            put("lastModDate", file.lastModified())
                            put("timeZoneOffset", offsetMinutes)
                            put("singleFileUploadRequest", receipt)
                        },
                    )
                },
            )
            put("localTimeZoneId", tz.id)
            put("importGroup", clientUuid)
        }
        val registered = http.postJson(
            "$base/photosupload/putAsset",
            putBody.toString(),
            http.apiHeaders(mapOf("Content-Type" to "application/json")),
            http.apiParams(),
        )
        if (!registered.isSuccessful) {
            throw ICloudApiException(
                registered.status,
                registered.body,
                "登记照片失败：HTTP ${registered.status} ${registered.body.take(200)}",
            )
        }
        val first = (registered.jsonElement() as? JsonArray)?.firstOrNull()?.asObj()
            ?: throw ICloudProtocolException("登记响应格式异常：${registered.body.take(200)}")
        val statusNode = first.obj("response")
        val status = statusNode
            ?.get("status")
            ?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
        val errorMessage = statusNode?.str("errorMessage")
        println(
            "[ICloudUpload] putAsset status=$status master=${first.str("cplMaster")} " +
                "asset=${first.str("cplAsset")} msg=${errorMessage ?: "-"}",
        )

        // 关键：登记失败**不会**体现在 HTTP 状态上 —— 外层永远是 200，
        // 真正的结果塞在数组元素的 `response.status` 里。不校验它，失败就会被静默吞掉，
        // 用户只看到「传完了」，图库里却什么都没有。
        if (status != null && status >= 400 && status != 409) {
            // 415 = 苹果拒收该媒体：要么扩展名不在白名单，要么视频编码转不动。
            // 换成专门的异常类型，UI 才能给出「换格式」这种可执行的建议。
            if (status == 415) {
                throw ICloudUnsupportedMediaException(
                    message = "苹果无法接受这个文件：${errorMessage ?: "unsupported media"}",
                    fileName = fileName,
                    detail = errorMessage,
                )
            }
            throw ICloudApiException(
                status,
                registered.body,
                "登记失败（$status）${errorMessage ?: registered.body.take(160)}",
            )
        }

        UploadedPhoto(
            masterRecordName = first.str("cplMaster"),
            assetRecordName = first.str("cplAsset"),
            jobId = first.str("uploadJobId"),
            duplicate = status == 409,
        )
    }

    private fun guessMimeType(fileName: String): String? =
        java.net.URLConnection.guessContentTypeFromName(fileName)

    // ------------------------------------------------------------------ 解析

    /** 把 Master / Asset 记录按 masterRef 配对成 PhotoAsset 列表，保持服务端返回顺序 */
    fun pairAssets(records: JsonArray): List<PhotoAsset> {
        val assetsByMaster = HashMap<String, JsonObject>()
        val masters = ArrayList<JsonObject>(records.size / 2 + 1)

        for (element in records) {
            val record = element.asObj() ?: continue
            when (record.str("recordType")) {
                "CPLAsset" -> {
                    val masterRef = record.fieldObj("masterRef")?.str("recordName") ?: continue
                    assetsByMaster[masterRef] = record
                }

                "CPLMaster" -> masters.add(record)
                else -> Unit
            }
        }

        return masters.mapNotNull { master ->
            val masterName = master.str("recordName") ?: return@mapNotNull null
            val asset = assetsByMaster[masterName] ?: return@mapNotNull null
            buildAsset(master, asset)
        }
    }

    private fun buildAsset(master: JsonObject, asset: JsonObject): PhotoAsset {
        val masterName = master.str("recordName").orEmpty()
        val filename = decodeBase64Text(master.fieldStr("filenameEnc"))
            ?: master.fieldStr("filename")
            ?: "IMG_$masterName"

        // 判定视频必须以 itemType 为主，不能只看「有没有 resVid* 资源」：
        // 实况照片（HEIC）天生带一段 2~3 秒的动态影片，同样有 resVidSmallRes，
        // 按资源判会被当成视频 —— 网格里于是出现「有 ▶ 却没时长」的格子
        // （它们的 duration 是 0，真实时长在 vidComplDurValue/Scale 上）。
        val itemType = master.fieldStr("itemType").orEmpty()
        val hasVideoRes = master.fieldObj("resVidSmallRes") != null ||
            master.fieldObj("resVidMedRes") != null
        val isVideo = when {
            // 明确的视频类型
            itemType.contains("movie", ignoreCase = true) ||
                itemType.contains("mpeg", ignoreCase = true) ||
                itemType.contains("video", ignoreCase = true) ||
                itemType.contains("quicktime", ignoreCase = true) ||
                itemType.contains("avi", ignoreCase = true) -> true
            // 明确的图片类型（含实况照片的 public.heic / public.jpeg）
            itemType.startsWith("public.") ||
                itemType.startsWith("com.adobe.") -> false
            // itemType 缺失时才退回「看有没有视频资源」
            else -> hasVideoRes
        }

        // 实况照片：照片身上带了一段动态影片。这里只存地址，长按时才去下，
        // 免得把一个 1~2 MB 的片段跟着列表一起拉下来。
        val liveVideoUrl = if (!isVideo && hasVideoRes) {
            listOf("resVidMedRes", "resVidSmallRes", "resVidFullRes")
                .asSequence()
                .mapNotNull { key ->
                    (asset.fieldObj(key) ?: master.fieldObj(key))?.str("downloadURL")
                }
                .firstOrNull()
        } else {
            null
        }

        // 视频与照片用不同的分辨率字段前缀
        val prefixMap = if (isVideo) VIDEO_VERSION_PREFIX else PHOTO_VERSION_PREFIX

        val versions = LinkedHashMap<AssetSize, AssetVersion>()
        for ((size, prefix) in prefixMap) {
            val res = asset.fieldObj("${prefix}Res")
                ?: master.fieldObj("${prefix}Res")
                ?: continue
            val url = res.str("downloadURL") ?: continue
            val owner = if (asset.fieldObj("${prefix}Res") != null) asset else master
            versions[size] = AssetVersion(
                filename = filename,
                fileType = owner.fieldStr("${prefix}FileType"),
                sizeBytes = res.long("size"),
                checksum = res.str("fileChecksum"),
                downloadUrl = url,
            )
        }

        // 视频封面：苹果有时在 master 里附带 resJPEGThumbRes，有时不给
        val posterUrl = if (isVideo) {
            (master.fieldObj("resJPEGThumbRes") ?: asset.fieldObj("resJPEGThumbRes"))
                ?.str("downloadURL")
        } else {
            null
        }

        return PhotoAsset(
            recordName = asset.str("recordName").orEmpty(),
            masterRecordName = masterName,
            filename = filename,
            isFavorite = asset.fieldBool("isFavorite") ?: false,
            versions = versions,
            assetDateMillis = asset.fieldStr("assetDate")?.toLongOrNull(),
            isVideo = isVideo,
            // 苹果给的是毫秒：2633 对应 2.6 秒（4.8 MB 的 4K 慢动作），
            // 当秒用会显示成 43:53。0 表示没写这个字段，按「无时长」处理。
            durationSeconds = asset.fieldStr("duration")
                ?.toDoubleOrNull()
                ?.takeIf { it > 0 }
                ?.div(1000.0),
            recordChangeTag = asset.str("recordChangeTag"),
            latitude = asset.fieldStr("locationLatitude")?.toDoubleOrNull(),
            longitude = asset.fieldStr("locationLongitude")?.toDoubleOrNull(),
            originalWidth = master.fieldStr("resOriginalWidth")?.toIntOrNull(),
            originalHeight = master.fieldStr("resOriginalHeight")?.toIntOrNull(),
            posterUrl = posterUrl,
            liveVideoUrl = liveVideoUrl,
        )
    }

    // ------------------------------------------------------------------ 工具

    /** 相册名 / 文件名都是 base64 编码的 UTF-8，解不出来就用兜底值 */
    fun decodeBase64Text(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return try {
            String(Base64.getDecoder().decode(value), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    companion object {
        val PHOTO_VERSION_PREFIX: Map<AssetSize, String> = mapOf(
            AssetSize.ORIGINAL to "resOriginal",
            AssetSize.MEDIUM to "resJPEGMed",
            AssetSize.THUMB to "resJPEGThumb",
        )

        val VIDEO_VERSION_PREFIX: Map<AssetSize, String> = mapOf(
            AssetSize.ORIGINAL to "resOriginal",
            AssetSize.MEDIUM to "resVidMed",
            AssetSize.THUMB to "resVidSmall",
        )
    }
}
