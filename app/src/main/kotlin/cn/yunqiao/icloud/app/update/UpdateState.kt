package cn.yunqiao.icloud.app.update

import java.io.File

/**
 * 更新流程的状态机。
 *
 * 界面完全由它驱动：有 [Available] 就弹对话框，[Downloading] 显示进度条，
 * [ReadyToInstall] 把控制权交给系统安装器。
 *
 * [forced] 单独存一份而不是每次从 manifest 现算，是因为「强制」是**检查那一刻**
 * 的判定结果：用户在对话框停留期间升级了别的渠道包，不该让界面中途改变行为。
 */
sealed interface UpdateState {

    /** 什么都没发生（含：已是最新、用户关掉了对话框） */
    data object Idle : UpdateState

    /** 正在请求清单；自动检查时不显示任何加载态，避免启动闪一下进度圈 */
    data object Checking : UpdateState

    /** 有新版本，等用户决定 */
    data class Available(
        val manifest: UpdateManifest,
        val forced: Boolean,
    ) : UpdateState

    /** 正在下载安装包 */
    data class Downloading(
        val manifest: UpdateManifest,
        val progress: Float,
        val forced: Boolean,
    ) : UpdateState

    /** 下载并校验通过，等用户点「安装」 */
    data class ReadyToInstall(
        val manifest: UpdateManifest,
        val apk: File,
        val forced: Boolean,
    ) : UpdateState
}
