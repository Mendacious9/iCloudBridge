package cn.yunqiao.icloud.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * 云盘文件「打开」能力的落地实现。
 *
 * 安卓没有「双击文件」这回事：要打开一个本地文件，必须显式构造 `ACTION_VIEW`
 * 交给系统去挑应用，而且从 Android 7.0 起不允许再用 `file://` 传递私有目录的文件
 * （`FileUriExposedException`），必须走 [FileProvider] 换成带临时授权的 `content://`。
 *
 * 另一个容易被忽略的点：**必须有接收方**。iWork 包（`.band`/`.pages`）、
 * `.dwg` 这类文件在手机上往往没有任何应用能打开，`startActivity` 会抛
 * `ActivityNotFoundException`。这里返回 false 让调用方退回「保存到下载目录」，
 * 而不是静默失败或者直接崩。
 */
object FileOpener {

    /** 系统 MimeTypeMap 对部分办公格式返回 null，这里补一份兜底表 */
    private val FALLBACK_MIME = mapOf(
        "txt" to "text/plain",
        "md" to "text/plain",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "text/xml",
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "epub" to "application/epub+zip",
        "pages" to "application/vnd.apple.pages",
        "numbers" to "application/vnd.apple.numbers",
        "key" to "application/vnd.apple.keynote",
        "band" to "application/octet-stream",
    )

    fun mimeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: FALLBACK_MIME[ext]
            ?: "application/octet-stream"
    }

    /**
     * 用系统应用打开 [file]。
     *
     * @return false 表示没有任何应用能处理这种类型，调用方应改走「保存」路径
     */
    fun open(context: Context, file: File, displayName: String): Boolean {
        val authority = "${context.packageName}.files"
        val uri: Uri = try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (_: IllegalArgumentException) {
            // 文件不在 FileProvider 声明的目录里（理论上不会发生），别崩
            return false
        }
        val mime = mimeOf(displayName)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            // 部分 ROM 的图库/Office 只认 ClipData 里的授权，不读 flags
            clipData = ClipData.newRawUri(displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
