package cn.yunqiao.icloud.account

import cn.yunqiao.icloud.ICloudApiException
import cn.yunqiao.icloud.ICloudProtocolException
import cn.yunqiao.icloud.http.ICloudHttp
import cn.yunqiao.icloud.internal.arr
import cn.yunqiao.icloud.internal.asObj
import cn.yunqiao.icloud.internal.bool
import cn.yunqiao.icloud.internal.long
import cn.yunqiao.icloud.internal.obj
import cn.yunqiao.icloud.internal.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 存储占用的一类（照片 / 备份 / 文档 / 邮件 / 信息…） */
data class StorageItem(
    val mediaKey: String,
    /** 苹果给的人类可读标签，英文；界面按 [mediaKey] 自己翻译，翻不到才用它 */
    val label: String,
    /** 苹果网页端环形图用的颜色（6 位 hex，无 #） */
    val colorHex: String?,
    val bytes: Long,
)

/** 账户存储空间快照 */
data class StorageUsage(
    val totalBytes: Long,
    val usedBytes: Long,
    val items: List<StorageItem>,
    val paidQuota: Boolean,
    val overQuota: Boolean,
) {
    val freeBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0)

    /** 已用比例 0..1，用于环形进度 */
    val usedRatio: Float
        get() = if (totalBytes <= 0) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)

    /** 按占用从大到小排；苹果原始顺序是固定的产品顺序，不便于阅读 */
    fun itemsBySize(): List<StorageItem> = items.sortedByDescending { it.bytes }
}

/**
 * 账户与配额。
 *
 * 只有**读**能力：iCloud 的订阅变更、家人共享、设备列表都不走这套私有 Web API
 * （账户的 webservices 里根本没有 commerce/subscription，getDevices 也会返回 400 空体）。
 * 所以「升级套餐」这类操作只能给官网入口，不要在这里假装能改。
 */
class AccountApi(private val http: ICloudHttp) {

    /** 存储空间占用明细 */
    suspend fun storageUsage(): StorageUsage = withContext(Dispatchers.IO) {
        val response = http.postJson(
            "${http.region.setup}/storageUsageInfo",
            "{}",
            http.apiHeaders(mapOf("Content-type" to "text/plain")),
            http.apiParams(),
        )
        if (!response.isSuccessful) {
            throw ICloudApiException(
                response.status,
                response.body,
                "读取存储空间失败：HTTP ${response.status} ${response.body.take(200)}",
            )
        }
        parseStorageUsage(response.body)
    }

    /** 抽成纯函数，方便单测：接口字段缺失时不能崩，只能降级 */
    fun parseStorageUsage(raw: String): StorageUsage {
        val root = try {
            kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
        } catch (e: Exception) {
            throw ICloudProtocolException("存储空间响应不是合法 JSON", e)
        } ?: throw ICloudProtocolException("存储空间响应格式异常")

        val info = root.obj("storageUsageInfo")
            ?: throw ICloudProtocolException("存储空间响应缺少 storageUsageInfo")
        val total = info.long("totalStorageInBytes") ?: 0L
        val used = info.long("usedStorageInBytes") ?: 0L

        val items = root.arr("storageUsageByMedia").orEmpty().mapNotNull { element ->
            val node = element.asObj() ?: return@mapNotNull null
            val key = node.str("mediaKey") ?: return@mapNotNull null
            StorageItem(
                mediaKey = key,
                label = node.str("displayLabel") ?: key,
                colorHex = node.str("displayColor"),
                bytes = node.long("usageInBytes") ?: 0L,
            )
        }

        val quota = root.obj("quotaStatus")
        return StorageUsage(
            totalBytes = total,
            usedBytes = used,
            items = items,
            paidQuota = quota?.bool("paidQuota") ?: false,
            overQuota = quota?.bool("overQuota") ?: false,
        )
    }
}
