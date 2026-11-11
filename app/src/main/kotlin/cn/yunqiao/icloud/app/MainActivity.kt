package cn.yunqiao.icloud.app

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yunqiao.icloud.app.ui.AppRoot
import cn.yunqiao.icloud.app.ui.MediaCache
import cn.yunqiao.icloud.app.ui.theme.ICloudBridgeTheme

/**
 * 宿主必须是 FragmentActivity —— BiometricPrompt 内部靠它拿 FragmentManager。
 * Compose 本身不关心宿主是 ComponentActivity 还是 FragmentActivity。
 */
class MainActivity : FragmentActivity() {

    /**
     * 语言覆盖必须发生在 `super.onCreate` 之前，否则资源已经按系统语言解析完了。
     * 没有选择语言时 [LocaleSupport.wrap] 原样返回，等价于「跟随系统」。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleSupport.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 图片/视频缓存需要 cacheDir，尽早初始化
        MediaCache.init(applicationContext)
        setContent {
            ICloudBridgeTheme {
                AppRoot(vm = viewModel())
            }
        }
    }
}

/** 从 Compose 的 Context 里找出宿主 Activity（可能被 ContextWrapper 包了几层） */
internal fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return current as? FragmentActivity
}
