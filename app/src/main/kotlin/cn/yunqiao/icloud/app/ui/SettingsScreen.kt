package cn.yunqiao.icloud.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import cn.yunqiao.icloud.app.AppLanguage
import cn.yunqiao.icloud.app.AppViewModel
import cn.yunqiao.icloud.app.BiometricLock
import cn.yunqiao.icloud.app.BuildConfig
import cn.yunqiao.icloud.app.LocaleSupport
import cn.yunqiao.icloud.app.R
import cn.yunqiao.icloud.account.StorageUsage
import cn.yunqiao.icloud.app.findFragmentActivity
import cn.yunqiao.icloud.model.AccountInfo

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    account: AccountInfo,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    // 平板/折叠屏上把内容收窄并居中：整行拉满 1200dp 时，标签在左、数值在右，
    // 两者隔得太远，读一行要横着扫半个屏幕。手机上这个值为 0，等于不改动。
    val windowWidth = LocalConfiguration.current.screenWidthDp.dp
    val sideInset = ((windowWidth - ListMaxWidth) / 2).coerceAtLeast(0.dp)

    LaunchedEffect(Unit) { vm.loadStorage() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.cd_previous),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = sideInset)
                .verticalScroll(rememberScrollState()),
        ) {
            // ---------------------------------------------------------- 语言
            SectionTitle(stringResource(R.string.settings_language))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LanguageChip(
                    label = stringResource(R.string.settings_language_system),
                    selected = vm.language == AppLanguage.SYSTEM,
                ) { pick ->
                    if (pick) {
                        vm.chooseLanguage(AppLanguage.SYSTEM)
                        (context.findFragmentActivity())?.recreate()
                    }
                }
                AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.forEach { language ->
                    LanguageChip(
                        label = language.label(),
                        selected = vm.language == language,
                    ) { pick ->
                        if (pick) {
                            vm.chooseLanguage(language)
                            (context.findFragmentActivity())?.recreate()
                        }
                    }
                }
            }
            Separator()

            // ---------------------------------------------------------- 安全
            SectionTitle(stringResource(R.string.settings_security))
            val available = BiometricLock.isAvailable(context)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_biometric),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (!available) {
                            stringResource(R.string.settings_biometric_unavailable)
                        } else if (vm.biometricEnabled) {
                            stringResource(R.string.settings_biometric_on)
                        } else {
                            stringResource(R.string.settings_biometric_off)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (available) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                Switch(
                    checked = vm.biometricEnabled && available,
                    enabled = available,
                    onCheckedChange = { vm.updateBiometric(it) },
                )
            }

            Separator()

            // ---------------------------------------------------------- 账户
            SectionTitle(stringResource(R.string.settings_account))
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = account.fullName ?: account.appleId,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = account.appleId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))

            StorageSection(vm)

            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = {
                    val home = vm.client?.region?.home ?: "https://www.icloud.com"
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(home)))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text(stringResource(R.string.storage_manage))
            }
            Text(
                text = stringResource(R.string.account_manage_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Separator()

            // ---------------------------------------------------------- 关于
            SectionTitle(stringResource(R.string.settings_about))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAbout)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "iCloudBridge",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(18.dp),
                )
            }

            Separator()

            // ---------------------------------------------------------- 退出
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.signOut() }
                    .padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_signout),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun Separator() {
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun LanguageChip(label: String, selected: Boolean, onSelect: (Boolean) -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = { onSelect(!selected) },
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }
        } else {
            null
        },
    )
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
private fun StorageSection(vm: AppViewModel) {
    val storage = vm.storage
    Column(Modifier.padding(horizontal = 16.dp)) {
        when {
            vm.storageLoading && storage == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.storage_loading),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            vm.storageFailed && storage == null -> Text(
                text = stringResource(R.string.storage_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            storage != null -> StorageBody(storage)
        }
    }
}

@Composable
private fun StorageBody(storage: StorageUsage) {
    Text(
        text = stringResource(R.string.account_storage),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(
            R.string.storage_used_of_total,
            formatBytes(storage.usedBytes).ifEmpty { "0 B" },
            formatBytes(storage.totalBytes).ifEmpty { "0 B" },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    // 分段条：每段颜色沿用苹果网页端的分类色，一眼能和官网对上。
    // 末尾单独画一段「未使用」，而不是靠容器底色露出——
    // 底色和页面背景太接近，用户会以为条没画满 / 数据不全。
    val unusedColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        storage.itemsBySize().forEach { item ->
            val weight = if (storage.totalBytes > 0) {
                (item.bytes.toFloat() / storage.totalBytes).coerceAtLeast(0.0001f)
            } else {
                0f
            }
            if (weight > 0f) {
                Box(
                    Modifier
                        .weight(weight)
                        .fillMaxSize()
                        .background(parseColor(item.colorHex)),
                )
            }
        }
        val freeWeight = if (storage.totalBytes > 0) {
            storage.freeBytes.toFloat() / storage.totalBytes
        } else {
            0f
        }
        if (freeWeight > 0f) {
            Box(
                Modifier
                    .weight(freeWeight)
                    .fillMaxSize()
                    .background(unusedColor),
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    storage.itemsBySize().forEach { item ->
        LegendRow(
            color = parseColor(item.colorHex),
            label = storageLabel(item.mediaKey, item.label),
            value = formatBytes(item.bytes).ifEmpty { "0 B" },
        )
    }
    // 未使用放在最后一行，和条形图右端那段灰对应
    LegendRow(
        color = unusedColor,
        label = stringResource(R.string.storage_unused),
        value = formatBytes(storage.freeBytes).ifEmpty { "0 B" },
    )
    Spacer(Modifier.height(4.dp))
}

/** 图例的一行：色块 + 名称 + 数值 */
@Composable
private fun LegendRow(color: Color, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 苹果给的是 6 位 hex（如 `ffcc00`），解不出来就用主题色兜底 */
private fun parseColor(hex: String?): Color = try {
    if (hex.isNullOrBlank()) Color.Gray else Color(android.graphics.Color.parseColor("#$hex"))
} catch (_: IllegalArgumentException) {
    Color.Gray
}

@Composable
private fun storageLabel(mediaKey: String, fallback: String): String = when (mediaKey) {
    "photos" -> stringResource(R.string.storage_key_photos)
    "backup" -> stringResource(R.string.storage_key_backup)
    "docs" -> stringResource(R.string.storage_key_docs)
    "mail" -> stringResource(R.string.storage_key_mail)
    "messages" -> stringResource(R.string.storage_key_messages)
    else -> fallback
}
