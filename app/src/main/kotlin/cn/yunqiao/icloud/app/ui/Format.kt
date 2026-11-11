package cn.yunqiao.icloud.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 字节数转人类可读，如 29.75 MB */
fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) {
        "${bytes} B"
    } else {
        String.format(Locale.getDefault(), "%.1f %s", value, units[index])
    }
}

/** 毫秒时间戳 → 本地日期，照片按拍摄时间展示 */
fun formatMillis(millis: Long?): String {
    if (millis == null || millis <= 0) return ""
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return format.format(Date(millis))
}

/** 苹果云盘的 modifiedAt 是 ISO8601 字符串，这里只取日期部分够用了 */
fun formatIsoDate(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw.substringBefore('T').ifBlank { raw }
}

/** 视频时长（秒）→ m:ss / h:mm:ss */
/**
 * 视频时长，**始终保持「时:分:秒」三段**。
 *
 * 之前不足一小时会省略小时位（`43:53`），用户反馈「看不懂这是 43 分还是 43 秒」。
 * 缩略图角标本来就窄，但歧义比宽度更糟；`00:43:53` 一眼就能读出量级，
 * 也和相册里其它时间信息（播放器进度条、照片时间）保持一致的三段式。
 */
fun formatDuration(seconds: Double): String {
    if (seconds <= 0) return ""
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
