package cn.yunqiao.icloud.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.IOException

/**
 * 把下载好的 APK 交给系统安装器。
 *
 * 这里是 Android 8 以后唯一可行的自更新方式，有两道门槛绕不过去：
 *
 * 1. 清单里声明 `REQUEST_INSTALL_PACKAGES`；
 * 2. 用户得为本应用打开「安装未知应用」开关。系统不会给 App 自己打开这个权限
 *    （否则等于谁都能静默装东西），只能跳设置页让用户点。
 *
 * 走的是 `PackageInstaller.Session` 而不是 `ACTION_VIEW` + content://：
 * 后者在装了多个包管理界面（应用商店、网盘、文件管理器都爱声明能接 apk）的设备上，
 * 会先弹一个「打开方式」选择器让用户自己挑，选错就跳去别的 App。Session 直接
 * 进系统安装确认页，没有中间这一步。
 */
object ApkInstaller {

    /** 安装结果回传用的 action，见 [InstallResultReceiver] */
    const val ACTION_INSTALL_COMMIT = "cn.yunqiao.icloud.app.action.INSTALL_COMMIT"

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
     * 把 APK 写进 installer session 并提交，系统随后弹出安装确认页。
     *
     * 这是**阻塞**调用（要把整个包复制进 session），必须在 IO 线程执行。
     *
     * @throws IOException 创建 session 或写流失败，调用方应提示用户手动安装。
     */
    @Throws(IOException::class)
    fun install(context: Context, apk: File) {
        if (!apk.isFile || apk.length() <= 0L) {
            throw IOException("安装包不存在或为空：${apk.absolutePath}")
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            // 不设的话部分 ROM 会把安装包当成未知来源的独立应用，覆盖安装判不出来
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }

        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite(APK_NAME, 0L, apk.length()).use { out ->
                    apk.inputStream().use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
                    session.fsync(out)
                }
                // 提交之后写流已关闭，剩下的交给系统；
                // 用户确认或拒绝都会回调到 PendingIntent 指向的广播
                session.commit(buildCommitIntent(context, sessionId).intentSender)
            }
        } catch (e: Throwable) {
            // 半途失败必须把 session 主动丢掉，否则会占着临时空间直到系统清理
            runCatching { installer.abandonSession(sessionId) }
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    private fun buildCommitIntent(context: Context, sessionId: Int): android.app.PendingIntent {
        // 用 sessionId 当 requestCode：同一进程里多个 session 的回调不会串
        val intent = Intent(context, InstallResultReceiver::class.java).apply {
            action = ACTION_INSTALL_COMMIT
        }
        // Android 12 起必须显式声明可变性：系统要往这个 Intent 里塞 EXTRA_STATUS 等字段
        var flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or android.app.PendingIntent.FLAG_MUTABLE
        }
        return android.app.PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    private const val APK_NAME = "base.apk"
}
