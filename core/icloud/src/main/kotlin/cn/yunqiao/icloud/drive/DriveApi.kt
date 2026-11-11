package cn.yunqiao.icloud.drive

import cn.yunqiao.icloud.ICloudApiException
import cn.yunqiao.icloud.ICloudProtocolException
import cn.yunqiao.icloud.auth.ICloudAuth
import cn.yunqiao.icloud.http.ICloudHttp
import cn.yunqiao.icloud.internal.arr
import cn.yunqiao.icloud.internal.int
import cn.yunqiao.icloud.internal.str
import cn.yunqiao.icloud.model.DriveEntry
import cn.yunqiao.icloud.model.DriveEntryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * iCloud 云盘（drivews）。
 *
 * 与照片的 CloudKit 完全是另一套协议：目录用 `retrieveItemDetailsInFolders`，
 * 下载要先 `download/by_id` 换一个带签名的 `data_token.url`。
 *
 * 最大的坑：`retrieveItemDetailsInFolders` 的响应在不同时间点可能是**数组**（目录树）
 * 也可能是**对象**（`{"items": [...]}`），M0 阶段就因为只按对象处理而崩过一次。
 * 这里两种都兼容。
 */
class DriveApi(
    private val http: ICloudHttp,
    private val auth: ICloudAuth,
) {

    private fun endpoint(): String = auth.requireService(
        "drivews",
        "云盘服务不可用：请在 iCloud 设置中开启「iCloud 云盘」",
    ).trimEnd('/')

    /** 列出某个目录的子项；默认根目录 */
    suspend fun listChildren(drivewsid: String = ROOT): List<DriveEntry> =
        withContext(Dispatchers.IO) {
            val payload = JsonArray(
                listOf(
                    buildJsonObject {
                        put("drivewsid", drivewsid)
                        put("partialData", false)
                    },
                ),
            )
            val response = http.postJson(
                "${endpoint()}/retrieveItemDetailsInFolders",
                payload.toString(),
                http.apiHeaders(mapOf("Content-type" to "text/plain")),
                http.apiParams(),
            )
            if (!response.isSuccessful) {
                throw ICloudApiException(
                    response.status,
                    response.body,
                    "云盘目录读取失败：HTTP ${response.status} ${response.body.take(300)}",
                )
            }
            parseEntries(response.jsonElement())
        }

    /** 逐层下钻用的便捷方法：列出文件夹子项 */
    suspend fun listFolder(entry: DriveEntry): List<DriveEntry> {
        val id = entry.drivewsid
            ?: throw ICloudProtocolException("该条目不是文件夹：${entry.name}")
        return listChildren(id)
    }

    /**
     * 取云盘文件的下载地址。
     *
     * 协议细节（实测 + 参考 pyicloud）：
     * - 走的是 `docws` 服务而不是 `drivews`，路径是 `/ws/com.apple.CloudDocs/download/by_id`；
     * - 用 **GET** + query 参数 `document_id`，不是 POST + JSON body（POST 会返回 400 空体）；
     * - 响应里可能是 `data_token.url`（普通文件）或 `package_token.url`（iWork 包）；
     * - 0 字节文件苹果会直接返回 400，这种情况返回 null，由 UI 提示「空文件」。
     *
     * @param docwsid 条目的 document id（不是 drivewsid）
     */
    suspend fun downloadUrl(docwsid: String): String? = withContext(Dispatchers.IO) {
        val base = auth.requireService(
            "docws",
            "云盘下载服务不可用：请确认已在 iCloud 设置中开启「iCloud 云盘」",
        ).trimEnd('/')
        val url = "$base/ws/com.apple.CloudDocs/download/by_id"

        val response = http.get(
            url,
            http.apiHeaders(mapOf("Accept" to "application/json")),
            http.apiParams(mapOf("document_id" to docwsid)),
        )
        if (response.status == 400 && response.body.isBlank()) {
            // 苹果对 0 字节文件返回空 400
            return@withContext null
        }
        if (!response.isSuccessful) {
            throw ICloudApiException(
                response.status,
                response.body,
                "获取下载地址失败：HTTP ${response.status} ${response.body.take(300)}",
            )
        }

        val body = response.jsonObject()
        listOf("data_token", "package_token")
            .asSequence()
            .mapNotNull { body.obj(it) }
            .mapNotNull { token ->
                token.str("url")?.takeIf { it.isNotBlank() }
                    ?: (token["url"] as? JsonArray)?.firstOrNull()
                        ?.let { (it as? JsonPrimitive)?.contentOrNull }
                        ?.takeIf { it.isNotBlank() }
            }
            .firstOrNull()
            ?: throw ICloudProtocolException("下载响应中没有地址：${response.body.take(200)}")
    }

    // ------------------------------------------------------------------ 上传

    /**
     * 上传本地文件到云盘目录（默认根目录）。
     *
     * 与照片上传是两套完全不同的协议，别混用：
     * 1. `POST {docws}/ws/com.apple.CloudDocs/upload/web`
     *    用 `{filename, type:"FILE", content_type, size}` 换 `{document_id, url}`；
     * 2. `POST <url>` 写字节，拿到 `singleFile` 回执（校验和 + wrapping key）；
     * 3. `POST {docws}/ws/com.apple.CloudDocs/update/documents`
     *    必须带 `command:"add_file"` 才会真正挂到目录上 —— 少了这个字段，
     *    苹果只回一句含义模糊的 `Unexpected input at column:56`，文件就成了孤儿。
     *
     * @param parentDocwsid 目标文件夹的 docwsid；根目录是字符串 `root`
     */
    suspend fun upload(
        file: File,
        fileName: String = file.name,
        parentDocwsid: String = ROOT_DOCWSID,
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        if (!file.exists()) throw ICloudProtocolException("文件不存在：$fileName")
        val base = auth.requireService(
            "docws",
            "云盘上传服务不可用：请确认已在 iCloud 设置中开启「iCloud 云盘」",
        ).trimEnd('/')
        val endpoint = "$base/ws/$DEFAULT_ZONE"
        val contentType = java.net.URLConnection.guessContentTypeFromName(fileName)
            ?: "application/octet-stream"

        // ① 申请上传位
        val initBody = buildJsonObject {
            put("filename", fileName)
            put("type", "FILE")
            put("content_type", contentType)
            put("size", file.length())
        }
        val initResponse = http.postJson(
            "$endpoint/upload/web",
            initBody.toString(),
            http.apiHeaders(mapOf("Content-type" to "text/plain")),
            http.apiParams(),
        )
        if (!initResponse.isSuccessful) {
            throw ICloudApiException(
                initResponse.status,
                initResponse.body,
                "云盘上传初始化失败：HTTP ${initResponse.status} ${initResponse.body.take(200)}",
            )
        }
        val slot = (initResponse.jsonElement() as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw ICloudProtocolException("云盘上传初始化响应异常：${initResponse.body.take(200)}")
        val documentId = slot.str("document_id")
            ?: throw ICloudProtocolException("云盘没有返回 document_id")
        val uploadUrl = slot.str("url")
            ?: throw ICloudProtocolException("云盘没有返回上传地址")

        // ② 写字节
        val uploaded = http.postFile(
            url = uploadUrl,
            file = file,
            contentType = contentType,
            onProgress = { sent, total ->
                onProgress(if (total > 0) sent.toFloat() / total else 0f)
            },
        )
        if (!uploaded.isSuccessful) {
            throw ICloudApiException(
                uploaded.status,
                uploaded.body,
                "云盘文件上传失败：HTTP ${uploaded.status} ${uploaded.body.take(200)}",
            )
        }
        val receipt = uploaded.jsonObject().obj("singleFile")
            ?: throw ICloudProtocolException("上传回执缺失，无法登记到云盘")

        // ③ 挂到目录
        val now = System.currentTimeMillis()
        val receiptToken = receipt.str("receipt")
            ?: throw ICloudProtocolException("上传回执缺失，无法登记到云盘")
        val registerBody = buildJsonObject {
            putJsonObject("data") {
                receipt.str("fileChecksum")?.let { put("signature", it) }
                receipt.str("wrappingKey")?.let { put("wrapping_key", it) }
                receipt.str("referenceChecksum")?.let { put("reference_signature", it) }
                put("size", (receipt["size"] as? JsonPrimitive)?.longOrNull ?: file.length())
                put("receipt", receiptToken)
            }
            put("command", "add_file")
            put("create_short_guid", true)
            put("document_id", documentId)
            putJsonObject("path") {
                put("starting_document_id", parentDocwsid)
                put("path", fileName)
            }
            put("allow_conflict", true)
            putJsonObject("file_flags") {
                put("is_writable", true)
                put("is_executable", false)
                put("is_hidden", false)
            }
            put("mtime", now)
            put("btime", now)
        }
        val registered = http.postJson(
            "$endpoint/update/documents",
            registerBody.toString(),
            http.apiHeaders(mapOf("Content-type" to "text/plain")),
            http.apiParams(),
        )
        if (!registered.isSuccessful) {
            throw ICloudApiException(
                registered.status,
                registered.body,
                "云盘文件登记失败：HTTP ${registered.status} ${registered.body.take(200)}",
            )
        }
        documentId
    }

    /** 删除云盘条目（进「最近删除」，不会立刻物理清除） */
    suspend fun delete(docwsid: String): Unit = withContext(Dispatchers.IO) {
        val base = auth.requireService("docws", "云盘服务不可用").trimEnd('/')
        val body = buildJsonObject {
            put("command", "delete")
            put("document_id", docwsid)
        }
        val response = http.postJson(
            "$base/ws/$DEFAULT_ZONE/update/documents",
            body.toString(),
            http.apiHeaders(mapOf("Content-type" to "text/plain")),
            http.apiParams(),
        )
        if (!response.isSuccessful) {
            throw ICloudApiException(
                response.status,
                response.body,
                "删除失败：HTTP ${response.status} ${response.body.take(200)}",
            )
        }
    }

    // ------------------------------------------------------------------ 解析

    /** 兼容数组（目录树）与对象（{"items": [...]}）两种响应结构 */
    internal fun parseEntries(element: JsonElement): List<DriveEntry> {
        val rawItems: List<JsonObject> = when (element) {
            is JsonArray -> element.flatMap { node ->
                val obj = node as? JsonObject ?: return@flatMap emptyList()
                val children = obj.arr("items")
                when {
                    children != null && children.isNotEmpty() -> children.mapNotNull { it as? JsonObject }
                    children != null -> emptyList()      // 空文件夹，本身就是请求的目录节点
                    else -> listOf(obj)
                }
            }

            is JsonObject -> {
                val errorCode = element.str("errorCode")
                if (!errorCode.isNullOrBlank()) {
                    throw ICloudApiException(
                        200,
                        element.toString(),
                        "云盘目录读取失败：${element.str("errorReason") ?: errorCode}",
                    )
                }
                element.arr("items")?.mapNotNull { it as? JsonObject } ?: emptyList()
            }

            else -> throw ICloudProtocolException("云盘响应格式异常：${element.toString().take(200)}")
        }
        return rawItems.map { toEntry(it) }
    }

    private fun toEntry(node: JsonObject): DriveEntry {
        val rawName = node.str("name") ?: node.str("docwsid").orEmpty()
        // 苹果把扩展名单独放在 `extension` 字段里，`name` 只有主名（"s5" + "png"）。
        // 直接拿 name 展示会少后缀，按它存盘更狠 —— 下载下来的文件双击打不开。
        val extension = node.str("extension").orEmpty()
        val displayName = when {
            extension.isBlank() -> rawName
            rawName.endsWith(".$extension", ignoreCase = true) -> rawName
            else -> "$rawName.$extension"
        }
        return DriveEntry(
            name = displayName,
            type = parseType(node.str("type")),
            sizeBytes = node["size"]?.let { (it as? JsonPrimitive)?.longOrNull },
            modifiedAt = node.str("dateModified"),
            docwsid = node.str("docwsid"),
            zone = node.str("zoneName") ?: DEFAULT_ZONE,
            childCount = node.int("numberOfItems"),
            drivewsid = node.str("drivewsid"),
        )
    }

    private fun parseType(raw: String?): DriveEntryType = when (raw?.uppercase()) {
        "FOLDER" -> DriveEntryType.FOLDER
        "FILE" -> DriveEntryType.FILE
        "APP_LIBRARY" -> DriveEntryType.APP_LIBRARY
        else -> DriveEntryType.UNKNOWN
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    companion object {
        /** 云盘根目录的 drivewsid，固定值 */
        const val ROOT = "FOLDER::com.apple.CloudDocs::root"

        /** 云盘根目录的 docwsid：就是字面量 `root`（实测，别去别处找 UUID） */
        const val ROOT_DOCWSID = "root"

        const val DEFAULT_ZONE = "com.apple.CloudDocs"
    }
}
