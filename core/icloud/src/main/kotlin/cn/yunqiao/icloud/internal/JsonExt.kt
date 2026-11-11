package cn.yunqiao.icloud.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * CloudKit / drivews 的响应是自由结构的 JSON，用 kotlinx.serialization 建完整 data class
 * 不现实（字段上百个且随时增删）。这里统一用「安全取值」扩展：取不到就返回 null，
 * 绝不因为苹果多返回/少返回一个字段而抛异常。
 */

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.objOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArr(): JsonArray? = this as? JsonArray

/** CloudKit 的字段值统一包在 {"value": ...} 里 */
internal fun JsonObject.fieldValue(key: String): JsonElement? =
    obj("fields")?.obj(key)?.get("value")

internal fun JsonObject.fieldStr(key: String): String? =
    (fieldValue(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

internal fun JsonObject.fieldBool(key: String): Boolean? =
    (fieldValue(key) as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.fieldObj(key: String): JsonObject? = fieldValue(key) as? JsonObject

internal fun JsonObject.fieldArr(key: String): JsonArray? = fieldValue(key) as? JsonArray

/** 逐层下钻：`direct.twoSV.bridgeInitiateData...` */
internal fun JsonObject.path(vararg keys: String): JsonElement? {
    var current: JsonElement = this
    for (key in keys) {
        current = (current as? JsonObject)?.get(key) ?: return null
    }
    return current
}
