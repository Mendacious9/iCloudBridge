package cn.yunqiao.icloud.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.yunqiao.icloud.app.AppLanguage
import cn.yunqiao.icloud.app.AppViewModel
import cn.yunqiao.icloud.app.R
import cn.yunqiao.icloud.app.RegionChoice
import cn.yunqiao.icloud.app.findFragmentActivity

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoginScreen(vm: AppViewModel) {
    // 初值来自上次登录：「记住账号」勾选过就自动填好，省得每次再敲一遍
    var appleId by remember { mutableStateOf(vm.savedAppleId) }
    var password by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(vm.lastRegion) }
    var rememberAccount by remember { mutableStateOf(vm.rememberAccount) }
    var languageMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 宽度上限、刘海避让、键盘避让、滚动都在 AdaptiveFormScreen 里，
    // 这里只关心内容，不再自己拼 insets —— 否则换一台机器就要重调一遍
    AdaptiveFormScreen {
        // 登录前也得能换语言：否则语言选错了就成了死结（设置页在登录之后）
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { languageMenu = true }) {
                    Icon(
                        Icons.Outlined.Translate,
                        contentDescription = stringResource(R.string.settings_language),
                    )
                }
                DropdownMenu(
                    expanded = languageMenu,
                    onDismissRequest = { languageMenu = false },
                ) {
                    LanguageMenuItem(
                        label = stringResource(R.string.settings_language_system),
                        selected = vm.language == AppLanguage.SYSTEM,
                    ) {
                        languageMenu = false
                        vm.chooseLanguage(AppLanguage.SYSTEM)
                        context.findFragmentActivity()?.recreate()
                    }
                    AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.forEach { language ->
                        LanguageMenuItem(
                            label = language.label(),
                            selected = vm.language == language,
                        ) {
                            languageMenu = false
                            vm.chooseLanguage(language)
                            context.findFragmentActivity()?.recreate()
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Icon(
            imageVector = Icons.Outlined.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.app_name),
            // 走主题字阶而不是写死 28.sp：这样系统字体放大时行高、字距会一起放大，不会挤在一起
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        Text(stringResource(R.string.region_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        // 用 FlowRow 而不是 Row：韩语/日语的「その他の国と地域」在 320dp 宽的机器上
        // 一张 chip 就快占满一行，Row 会把第二张直接截掉
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RegionChoice.entries.forEach { choice ->
                FilterChip(
                    selected = region == choice,
                    onClick = { region = choice },
                    label = { Text(choice.label()) },
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = appleId,
            onValueChange = { appleId = it },
            label = { Text(stringResource(R.string.apple_id)) },
            placeholder = { Text(stringResource(R.string.apple_id_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = rememberAccount,
                    role = Role.Checkbox,
                    onValueChange = { rememberAccount = it },
                )
                .padding(vertical = 4.dp),
        ) {
            Checkbox(checked = rememberAccount, onCheckedChange = null)
            Spacer(Modifier.size(10.dp))
            Text(
                text = stringResource(R.string.remember_account),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                // 先收起键盘：输入法还在拼写（未上屏）时直接读状态会读到半截内容
                vm.login(appleId, password, region, rememberAccount)
            },
            enabled = !vm.busy,
            // heightIn 而不是 height：字体放大到 1.5 倍时按钮跟着长高，文字不会被裁掉
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            if (vm.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.login))
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        ) {
            Text(
                text = stringResource(R.string.login_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RegionChoice.label(): String = when (this) {
    RegionChoice.CHINA -> stringResource(R.string.region_china)
    RegionChoice.GLOBAL -> stringResource(R.string.region_global)
}

@Composable
private fun AppLanguage.label(): String = when (this) {
    AppLanguage.SYSTEM -> stringResource(R.string.settings_language_system)
    AppLanguage.EN -> stringResource(R.string.lang_en)
    AppLanguage.ZH_CN -> stringResource(R.string.lang_zh_cn)
    AppLanguage.ZH_TW -> stringResource(R.string.lang_zh_tw)
    AppLanguage.JA -> stringResource(R.string.lang_ja)
    AppLanguage.KO -> stringResource(R.string.lang_ko)
}

@Composable
private fun LanguageMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = if (selected) {
            { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }
        } else {
            null
        },
    )
}
