package cn.yunqiao.icloud.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Parcelable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 收 `PackageInstaller` 的回调。
 *
 * 提交 session 之后系统有三条路：
 *
 * 1. `STATUS_PENDING_USER_ACTION` —— 最常见，系统把「安装确认页」的 Intent 塞在
 *    `EXTRA_INTENT` 里递回来，**必须由我们自己 startActivity**。不处理的话表现就是
 *    「点了立即更新，什么都没发生」。
 * 2. `STATUS_SUCCESS` —— 进程马上会被替换，这里什么都不用做。
 * 3. 各种 `STATUS_FAILURE*` —— 签名不符、降级安装、用户点了取消。
 *
 * 只在清单里静态注册、且 `exported=false`：广播只可能由系统通过我们自己的
 * PendingIntent 发出，外部应用没法伪造一个「安装成功」来骗界面。
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApkInstaller.ACTION_INSTALL_COMMIT) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.parcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    InstallOutcome.report(InstallResult.Failed(message))
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { InstallOutcome.report(InstallResult.Failed(it.message ?: message)) }
            }

            PackageInstaller.STATUS_SUCCESS -> Unit

            // 用户自己点了「取消」：不是错误，界面退回可重试的状态就好
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.report(InstallResult.Cancelled)

            else -> InstallOutcome.report(
                InstallResult.Failed(message.ifBlank { "status=$status" }),
            )
        }
    }
}

/** 一次安装尝试的终局，由 [InstallResultReceiver] 发出 */
sealed interface InstallResult {
    /** 用户在系统确认页点了取消 */
    data object Cancelled : InstallResult

    /** 安装失败，[message] 是系统给的原因（通常是英文） */
    data class Failed(val message: String) : InstallResult
}

/**
 * 安装结果总线。
 *
 * 用单例而不是 EventBus：只有一处生产（receiver）、一处消费（AppViewModel），
 * 丢了也不影响正确性——安装成功时进程本来就要被换掉。
 */
object InstallOutcome {

    private val _events = MutableSharedFlow<InstallResult>(extraBufferCapacity = 4)

    val events = _events.asSharedFlow()

    fun report(result: InstallResult) {
        _events.tryEmit(result)
    }
}

/** 兼容 API 33 前后两种签名差异 */
private inline fun <reified T : Parcelable> Intent.parcelableExtra(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
