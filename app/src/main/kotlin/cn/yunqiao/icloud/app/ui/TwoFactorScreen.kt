package cn.yunqiao.icloud.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.yunqiao.icloud.app.AppViewModel
import cn.yunqiao.icloud.app.R
import cn.yunqiao.icloud.app.Stage

private enum class Channel { SMS, DEVICE }

/**
 * 双重认证页。
 *
 * 两条通道对应苹果的两个接口：
 * - 短信：PUT /verify/phone 触发下发，PUT /verify/phone/securitycode 校验；
 * - 受信设备：PUT /verify/trusteddevice/securitycode 会同时触发设备弹窗与校验。
 *
 * 没有 iPhone 的用户只能走短信，所以短信是默认通道。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TwoFactorScreen(vm: AppViewModel, stage: Stage.TwoFactor) {
    var channel by remember { mutableStateOf(Channel.SMS) }
    var phoneId by remember(stage.phones) { mutableStateOf(stage.phones.firstOrNull()?.id ?: -1) }
    var code by remember { mutableStateOf("") }
    val context = LocalContext.current

    AdaptiveFormScreen(verticalPadding = 32.dp) {
        Spacer(Modifier.height(24.dp))
        Icon(
            imageVector = Icons.Outlined.VerifiedUser,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.tf_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tf_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        // 同样用 FlowRow：日语「テキストメッセージ」+「信頼できるデバイス」两张 chip
        // 在 360dp 宽的手机上正好压线，Row 会溢出到屏幕外
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = channel == Channel.SMS,
                onClick = { channel = Channel.SMS },
                label = { Text(stringResource(R.string.tf_sms)) },
                leadingIcon = {
                    Icon(Icons.Outlined.Sms, null, Modifier.size(18.dp))
                },
            )
            FilterChip(
                selected = channel == Channel.DEVICE,
                onClick = { channel = Channel.DEVICE },
                label = { Text(stringResource(R.string.tf_device)) },
            )
        }

        Spacer(Modifier.height(16.dp))

        when (channel) {
            Channel.SMS -> {
                if (stage.phones.isEmpty()) {
                    if (stage.busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                stringResource(R.string.tf_reading_phones),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        Text(
                            text = stage.phoneError?.resolve(context) ?: stringResource(R.string.tf_phone_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { vm.retryTrustedPhones() }) {
                            Text(stringResource(R.string.tf_refetch_phones))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.tf_phone_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    stage.phones.forEach { phone ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = phoneId == phone.id,
                                onClick = { phoneId = phone.id },
                            )
                            Text(
                                text = phone.number,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (phoneId == phone.id) {
                                OutlinedButton(
                                    onClick = { vm.sendSms(phone.id) },
                                    enabled = !stage.busy,
                                ) {
                                    Text(
                                        text = if (stage.smsSent) {
                                            stringResource(R.string.tf_resend)
                                        } else {
                                            stringResource(R.string.tf_send_code)
                                        },
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    if (!stage.smsSent) {
                        Text(
                            stringResource(R.string.tf_send_first),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Channel.DEVICE -> {
                Text(
                    stringResource(R.string.tf_device_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.pushToDevice() }, enabled = !stage.busy) {
                    Text(stringResource(R.string.tf_device_send))
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { input -> code = input.filter { it.isDigit() }.take(6) },
            label = { Text(stringResource(R.string.tf_code_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                if (channel == Channel.SMS) vm.verifySms(phoneId, code)
                else vm.verifyDeviceCode(code)
            },
            enabled = !stage.busy && code.length == 6 &&
                (channel == Channel.DEVICE || phoneId > 0),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            if (stage.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.tf_verify))
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { vm.cancelTwoFactor() }) {
            Text(stringResource(R.string.tf_back))
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.tf_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
