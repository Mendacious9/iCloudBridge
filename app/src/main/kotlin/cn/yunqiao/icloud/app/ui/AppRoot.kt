package cn.yunqiao.icloud.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.yunqiao.icloud.app.AppViewModel
import cn.yunqiao.icloud.app.BiometricLock
import cn.yunqiao.icloud.app.R
import cn.yunqiao.icloud.app.Stage
import cn.yunqiao.icloud.app.findFragmentActivity

/**
 * 顶层路由：完全由 [AppViewModel.stage] 驱动。
 * 不引 Navigation 库 —— 这里只有几个状态，状态机比路由表更直观。
 */
@Composable
fun AppRoot(vm: AppViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    // 错误/提示文案在渲染时才解析：切了语言，连提示都会跟着变
    LaunchedEffect(vm.error) {
        vm.error?.let {
            snackbar.showSnackbar(it.resolve(context))
            vm.clearError()
        }
    }
    LaunchedEffect(vm.notice) {
        vm.notice?.let {
            snackbar.showSnackbar(it.resolve(context))
            vm.clearNotice()
        }
    }
    LaunchedEffect(vm.stage) {
        if (vm.stage !is Stage.Ready) {
            showSettings = false
            showAbout = false
        } else {
            // 进入主页才查更新：登录/2FA 阶段不该先联网查版本
            vm.maybeCheckUpdateOnLaunch()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val stage = vm.stage) {
            Stage.Booting -> BootingScreen()

            Stage.Login -> LoginScreen(vm)

            is Stage.TwoFactor -> TwoFactorScreen(vm, stage)

            is Stage.Ready -> when {
                showAbout -> AboutScreen(onBack = { showAbout = false })

                showSettings -> SettingsScreen(
                    vm = vm,
                    account = stage.account,
                    onBack = { showSettings = false },
                    onOpenAbout = { showAbout = true },
                )

                else -> HomeScreen(vm, stage.account, onOpenSettings = { showSettings = true })
            }
        }

        // 上传遮罩压在最上层，且不可取消：中途退出会留下半个文件
        vm.upload?.let { UploadOverlay(it) }

        // 更新对话框在自己的下载/校验完成后调起系统安装器；这里只管提示与进度
        UpdateDialog(vm)

        // 生物识别锁：盖住一切，锁住时连照片都不渲染
        if (vm.locked && vm.stage is Stage.Ready) {
            LockScreen(vm)
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 全面屏手势条会盖住底部，Snackbar 必须让开导航栏，否则点不到「撤销」
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp),
        )
    }
}

@Composable
private fun BootingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.booting),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/**
 * 解锁页。
 *
 * 进入即自动拉起系统验证；用户取消后停在这里，可以点按钮再来一次。
 * 注意这里**不提供**「跳过」 —— 开关关掉要去设置里关，否则锁就是摆设。
 */
@Composable
private fun LockScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val promptTitle = stringResource(R.string.bio_prompt_title)
    var failed by remember { mutableStateOf(false) }

    fun authenticate() {
        if (activity == null) {
            vm.unlock()
            return
        }
        failed = false
        BiometricLock.prompt(
            activity = activity,
            title = promptTitle,
            onSuccess = { vm.unlock() },
            onFailure = { failed = true },
        )
    }

    LaunchedEffect(Unit) { authenticate() }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                // 内容窄、居中；宽度封顶后平板上不会出现一个孤零零飘在中间的图标。
                // verticalScroll 兜住「小屏 + 字体放大 2 倍」的情况，宁可滚动也不裁切
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.bio_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.bio_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (failed) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.bio_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(28.dp))
                Button(onClick = { authenticate() }) {
                    Text(stringResource(R.string.bio_unlock))
                }
            }
        }
    }
}
