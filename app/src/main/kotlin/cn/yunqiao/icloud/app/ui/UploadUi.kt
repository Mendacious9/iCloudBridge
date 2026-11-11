package cn.yunqiao.icloud.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.yunqiao.icloud.app.R
import cn.yunqiao.icloud.app.UploadPhase
import cn.yunqiao.icloud.app.UploadState

/**
 * 上传进度遮罩。
 *
 * 用模态而不是进度条挂在角落：上传过程中切相册、进云盘目录都不影响已经开始的请求，
 * 但用户很容易误以为「界面还能点 = 可以退出」。这里挡住交互，传完自动消失。
 */
@Composable
fun UploadOverlay(state: UploadState) {
    val ratio = if (state.total <= 1) {
        state.progress
    } else {
        (state.finished + state.progress) / state.total
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            // 吞掉所有点击，避免上传途中误操作
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        color = Color.Black.copy(alpha = 0.45f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Card {
                Column(
                    Modifier
                        .widthIn(min = 260.dp, max = 320.dp)
                        .padding(20.dp),
                ) {
                    Text(
                        text = when {
                            state.name.isBlank() ->
                                stringResource(R.string.upload_preparing, state.total)

                            // 转码和上传是两件事，百分比含义不同，必须分别说明
                            state.phase == UploadPhase.TRANSCODING ->
                                stringResource(R.string.upload_transcoding, state.name)

                            else -> stringResource(
                                R.string.upload_progress,
                                state.name,
                                (state.progress * 100).toInt(),
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { ratio.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${state.finished}/${state.total}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
