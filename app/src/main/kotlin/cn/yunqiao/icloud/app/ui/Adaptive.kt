package cn.yunqiao.icloud.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 屏幕适配的唯一入口。
 *
 * 判断依据是 **窗口宽度**（`screenWidthDp`），不是物理分辨率和 dpi —— 后者在三星/华为/OPPO/vivo
 * 各家的缩放策略下差异极大（同一块 1080×2400 屏可能被标成 360dp、411dp 或 480dp），
 * 只有 dp 宽度才真正决定「排得下几列」。分屏、折叠屏展开、平板横屏也都被同一个值覆盖。
 *
 * 分档沿用 Material 3 的窗口尺寸分级：
 * - COMPACT  < 600dp：绝大多数手机（三星 S 系列 360、小米 411、OPPO/vivo 412、华为 360）
 * - MEDIUM  600~839dp：折叠屏展开（Z Fold 内屏 673）、小平板
 * - EXPANDED ≥ 840dp：平板横屏、投屏
 */
enum class WindowWidth { COMPACT, MEDIUM, EXPANDED }

@Composable
@ReadOnlyComposable
fun currentWindowWidth(): WindowWidth {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 600 -> WindowWidth.COMPACT
        widthDp < 840 -> WindowWidth.MEDIUM
        else -> WindowWidth.EXPANDED
    }
}

/** 系统字体缩放的倍数。各家 ROM 都能把这个值拉到 1.3~2.0，布局必须让位。 */
@Composable
@ReadOnlyComposable
fun currentFontScale(): Float = LocalDensity.current.fontScale

/**
 * 表单页（登录、二次验证）的内容最大宽度。
 *
 * 手机上（COMPACT）这个上限永远碰不到，等于铺满；到了平板/折叠屏它开始起作用：
 * 输入框拉成 1200dp 宽既难看也难点，居中收窄才是正确做法。
 */
val FormMaxWidth: Dp = 448.dp

/** 列表/设置页的最大宽度，比表单宽一些，因为一行里要放标题和值 */
val ListMaxWidth: Dp = 640.dp

/** 窄屏收一点边距，宽屏给足呼吸空间——24dp 在 320dp 宽的机器上会把内容挤扁 */
@Composable
@ReadOnlyComposable
fun screenHorizontalPadding(): Dp = when (currentWindowWidth()) {
    WindowWidth.COMPACT -> 20.dp
    else -> 32.dp
}

/**
 * 表单页的滚动容器：负责安全区、输入法避让、居中和最大宽度。
 *
 * 高度逻辑说明：
 * - 顶部/左右用 `safeDrawing`，刘海、挖孔、曲面屏边缘都算在内，横屏时左右也能让开；
 * - 底部不直接用 `safeDrawing.bottom`，因为 `imePadding()` 已经包含导航栏高度，
 *   两个都加会多出一截空白，所以底部改成「内容内边距 + 导航栏高度」；
 * - 整体可滚动 + `imePadding`，键盘弹出时不是压缩内容而是把内容顶上去，
 *   320dp 小屏 + 字体放大到 1.3 倍也不会出现按钮被键盘盖住的情况。
 */
@Composable
fun AdaptiveFormColumn(
    maxWidth: Dp = FormMaxWidth,
    verticalPadding: Dp = 24.dp,
    horizontalPadding: Dp = screenHorizontalPadding(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
            )
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                // widthIn 必须写在 fillMaxWidth 前面：外层先把上限压到 maxWidth，
                // 内层再铺满，才是「最宽不超过 maxWidth 且居中」的效果
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = verticalPadding,
                    bottom = verticalPadding + navigationBar,
                ),
            content = content,
        )
    }
}

/** 带背景色的表单页外壳，等价于「Surface + AdaptiveFormColumn」的常用组合 */
@Composable
fun AdaptiveFormScreen(
    maxWidth: Dp = FormMaxWidth,
    verticalPadding: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        AdaptiveFormColumn(
            maxWidth = maxWidth,
            verticalPadding = verticalPadding,
            content = content,
        )
    }
}
