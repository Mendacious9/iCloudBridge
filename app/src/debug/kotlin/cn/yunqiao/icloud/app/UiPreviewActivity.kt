package cn.yunqiao.icloud.app

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yunqiao.icloud.app.ui.AboutScreen
import cn.yunqiao.icloud.app.ui.HomeScreen
import cn.yunqiao.icloud.app.ui.SettingsScreen
import cn.yunqiao.icloud.app.ui.theme.ICloudBridgeTheme
import cn.yunqiao.icloud.model.AccountInfo

/**
 * 只在 debug 构建里存在的布局检查页。
 *
 * 主界面要登录 + 短信 2FA 才看得到，而屏幕适配恰恰是主界面最容易出问题的地方
 * （顶栏两行标题、底部导航栏、照片网格列数）。这里用假账号直接渲染同一个
 * composable，就能在不登录的情况下把各种分辨率、字体倍数跑一遍：
 *
 * ```
 * adb shell am start -n cn.yunqiao.icloud.app/.UiPreviewActivity --es screen home
 * adb shell am start -n cn.yunqiao.icloud.app/.UiPreviewActivity --es screen settings
 * ```
 *
 * `--ez seed true` 会灌 60 条假照片；再带 `--es pinch out|in` 时，页面起来后会自己
 * 派发一串双指事件（out = 张开手指把单张变小，in = 捏合把单张放大），
 * 用来验证「捏合改密度」这条链路。
 * `adb shell input` 只支持单指，instrumentation 在慢速模拟器上又会因为进程启动超时
 * 被 ANR 杀掉，所以让页面自己往 decorView 派发事件是这里唯一稳定的做法：
 *
 * ```
 * adb shell am start -n cn.yunqiao.icloud.app/.UiPreviewActivity \
 *     --es screen home --ez seed true --es pinch out
 * adb shell run-as cn.yunqiao.icloud.app cat shared_prefs/icloud_bridge.xml
 * ```
 *
 * 放在 `src/debug` 下，release 包里不存在，不会被发出去。
 */
class UiPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val screen = intent.getStringExtra("screen") ?: "home"
        setContent {
            ICloudBridgeTheme {
                // 复用真实 ViewModel：布局里那些 vm.photos / vm.albums 的分支
                // 走的是空数据路径，正是没登录时的样子，不影响核对框架布局
                val vm: AppViewModel = viewModel()
                // 灌一批假照片，双指缩放的验证才有东西可捏；不加这个参数时仍是空列表，
                // 保持原来看框架布局的用途
                if (intent.getBooleanExtra("seed", false)) {
                    remember { vm.seedPreviewPhotos(); true }
                }
                val account = remember {
                    AccountInfo(
                        appleId = "preview@example.com",
                        fullName = "Preview Account",
                        dsid = "0",
                        region = "china",
                        webservices = emptyMap(),
                    )
                }
                when (screen) {
                    "settings" -> SettingsScreen(vm, account, onBack = {}, onOpenAbout = {})
                    // 关于页不依赖登录态，直接渲染，方便核对版本号与构建日期
                    "about" -> AboutScreen(onBack = {})
                    else -> HomeScreen(vm, account, onOpenSettings = {})
                }
            }
        }

        // --ez hold true 时按住不放 6 秒，用来抓缩放中途的画面
        val hold = if (intent.getBooleanExtra("hold", false)) 6_000L else 0L
        when (intent.getStringExtra("pinch")) {
            // 双指分开 = 放大：单张变大、列数变少
            "out" -> window.decorView.postDelayed({ simulatePinch(zoomIn = true, holdMillis = hold) }, 1_500)
            // 双指合拢 = 缩小：单张变小、列数变多
            "in" -> window.decorView.postDelayed({ simulatePinch(zoomIn = false, holdMillis = hold) }, 1_500)
        }
    }

    /**
     * 派发一串「双指对称开合」的触摸事件。
     *
     * 手指数从 1 → 2 是关键：合成事件必须严格按 DOWN → POINTER_DOWN → MOVE… →
     * POINTER_UP → UP 的顺序，一次性发一个多指 MOVE 是识别不出来的。
     */
    private fun simulatePinch(zoomIn: Boolean, holdMillis: Long = 0L) {
        val view = window.decorView
        if (view.width == 0 || view.height == 0) {
            Log.w(TAG, "decorView 还没有尺寸，放弃这次模拟")
            return
        }
        val cx = view.width / 2f
        val cy = view.height * 0.58f
        // 放大（分开）：两指从相距 100px 张到 340px；缩小（合拢）则反过来
        val halfStart = if (zoomIn) 50f else 170f
        val halfEnd = if (zoomIn) 170f else 50f
        val downTime = SystemClock.uptimeMillis()
        var stamp = downTime

        val events = mutableListOf<MotionEvent>()
        fun emit(action: Int, points: List<Pair<Float, Float>>) {
            stamp += 16
            events += obtain(downTime, stamp, action, points)
        }

        emit(MotionEvent.ACTION_DOWN, listOf(cx - halfStart to cy))
        emit(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(cx - halfStart to cy, cx + halfStart to cy),
        )
        for (step in 1..20) {
            val half = halfStart + (halfEnd - halfStart) * step / 20f
            emit(MotionEvent.ACTION_MOVE, listOf(cx - half to cy, cx + half to cy))
        }
        // 抬手事件单独拎出来：holdMillis > 0 时可以「按住不放」，
        // 方便在缩放过程中的某一帧截图，确认图层缩放确实生效
        val half = halfEnd
        emit(
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(cx - half to cy, cx + half to cy),
        )
        emit(MotionEvent.ACTION_UP, listOf(cx - half to cy))

        val moveCount = events.size - 2
        events.forEachIndexed { index, event ->
            val delay = if (index < moveCount) {
                index * 16L
            } else {
                holdMillis + (index - moveCount) * 16L
            }
            view.postDelayed({
                // 不 recycle：Compose 会把事件转成内部快照后再异步处理，
                // 提前回收有机会撞上「已回收对象」崩溃；一次验证只发几十个事件，不值得省
                view.dispatchTouchEvent(event)
            }, delay)
        }
        Log.i(TAG, "已派发 ${events.size} 个双指事件，中心点=($cx, $cy)，按住=${holdMillis}ms")
    }

    private fun obtain(
        downTime: Long,
        eventTime: Long,
        action: Int,
        points: List<Pair<Float, Float>>,
    ): MotionEvent {
        val props = Array(points.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = points[i].first
                y = points[i].second
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            points.size,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    private companion object {
        const val TAG = "UiPreviewActivity"
    }
}
