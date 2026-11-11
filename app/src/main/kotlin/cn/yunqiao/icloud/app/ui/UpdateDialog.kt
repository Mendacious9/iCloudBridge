package cn.yunqiao.icloud.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.yunqiao.icloud.app.AppViewModel
import cn.yunqiao.icloud.app.BuildConfig
import cn.yunqiao.icloud.app.R
import cn.yunqiao.icloud.app.update.UpdateState

/**
 * 线上更新的对话框。
 *
 * 只负责「有新版本 / 正在下载」两种状态；装完之后的系统安装界面在应用之外，
 * 无需也不应该自己画。
 */
@Composable
fun UpdateDialog(vm: AppViewModel) {
    when (val state = vm.updateState) {
        is UpdateState.Available -> AvailableDialog(vm, state)
        is UpdateState.Downloading -> DownloadingDialog(vm, state)
        else -> Unit
    }
}

@Composable
private fun AvailableDialog(vm: AppViewModel, state: UpdateState.Available) {
    val manifest = state.manifest
    val changelog = manifest.changelogFor()

    // stringResource 是 @Composable，必须在 buildString 外面取好
    val currentLine = stringResource(R.string.update_current, BuildConfig.VERSION_NAME)
    val sizeText = formatBytes(manifest.sizeBytes)
    val meta = if (sizeText.isNotEmpty()) {
        currentLine + " · " + stringResource(R.string.update_size, sizeText)
    } else {
        currentLine
    }

    AlertDialog(
        // 强制更新时点外部、按返回键都不关闭 —— 这是「必须升级才能用」的唯一实现方式
        onDismissRequest = { if (!state.forced) vm.dismissUpdate() },
        title = { Text(stringResource(R.string.update_available_title, manifest.versionName)) },
        text = {
            Column {
                if (state.forced) {
                    Text(
                        text = stringResource(R.string.update_forced_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                if (!changelog.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.update_changelog_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = changelog,
                        style = MaterialTheme.typography.bodyMedium,
                        // 更新日志可能很长，限高滚动，别把按钮顶出屏幕
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!state.forced) {
                    TextButton(
                        onClick = { vm.skipUpdateVersion() },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.update_skip),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.downloadUpdate() }) {
                Text(stringResource(R.string.update_now))
            }
        },
        dismissButton = if (state.forced) {
            null
        } else {
            {
                TextButton(onClick = { vm.dismissUpdate() }) {
                    Text(stringResource(R.string.update_later))
                }
            }
        },
    )
}

@Composable
private fun DownloadingDialog(vm: AppViewModel, state: UpdateState.Downloading) {
    val percent = (state.progress * 100).toInt().coerceIn(0, 100)

    AlertDialog(
        // 下载中点外部不关闭：关掉之后用户就找不到这个下载了
        onDismissRequest = { },
        title = { Text(stringResource(R.string.update_downloading_title, state.manifest.versionName)) },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.update_downloading, percent),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.cancelDownload() }) {
                Text(stringResource(R.string.update_cancel))
            }
        },
    )
}
