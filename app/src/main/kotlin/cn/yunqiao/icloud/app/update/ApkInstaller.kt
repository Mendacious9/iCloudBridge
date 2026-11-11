package cn.yunqiao.icloud.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把下载好的 APK 交给系统安装器。
 *
 * 这里是 Android 8 以后唯一可行的自更新方式，有两道门槛绕不过去：
 *
 * 1. 清单里声明 `REQUEST_INSTALL_PACKAGES`；
 * 2. 用户得为本应用打开「安装未知应用」开关。系统不会给 App 自己打开这个权限
 *    （否则等于谁都能静默装东西），只能跳设置页让用户点。
 *
 * 装的是 content:// 而不是 file://：Android 7 起直接给 file:// 会抛
 * FileUriExposedException，表现为「点安装没反应」。
 */
object ApkInstaller {

    /** 是否已经允许本应用安装 APK */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true // 8.0 以下按「设置 → 安全 → 未知来源」全局开关，App 无法查询
        }

    /** 跳到「安装未知应用」里本应用的授权页 */
    fun openInstallPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            // 少数定制 ROM 没有这个页面，退回应用详情页，用户仍可找到权限入口
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            false
        }
    }

    /**
     * 调起系统安装界面。
     *
     * @return true 表示已成功拉起安装器；false 表示设备上没有能处理 apk 的组件
     *         （极少数精简 ROM 会这样），调用方应给出「去设置里手动安装」的提示。
     */
    fun install(context: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
