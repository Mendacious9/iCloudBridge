package cn.yunqiao.icloud.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.yunqiao.icloud.model.DriveEntry

/**
 * 云盘条目的图标。
 *
 * iOS「文件」的列表里，每一行的左侧一眼就能看出「这是文件夹还是什么类型的文件」，
 * 用户扫一列结果的时候不用读文件名。这里用同一套逻辑：
 * 文件夹是蓝色实心文件夹，文件是带扩展名色块的圆角方块。
 * 颜色取自 iOS 系统色的近似值 —— 红 PDF、蓝文档、绿表格、橙演示、紫图片、粉视频。
 */
@Composable
internal fun DriveEntryIcon(entry: DriveEntry, size: Dp, modifier: Modifier = Modifier) {
    if (entry.isBrowsable) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = FolderBlue,
            modifier = modifier.size(size),
        )
    } else {
        FileTypeChip(fileName = entry.name, size = size, modifier = modifier)
    }
}

private val FolderBlue = Color(0xFF0A84FF)

@Immutable
internal data class FileBadge(val label: String, val tint: Color)

/** 扩展名 → 色块文字与颜色；未知类型统一走灰蓝，不会出现「空白图标」 */
internal fun fileBadgeFor(fileName: String): FileBadge {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val label = ext.uppercase().let { if (it.length > 4) it.take(4) else it }
        .ifEmpty { "FILE" }
    val tint = when (ext) {
        "pdf" -> Color(0xFFFF3B30)
        "doc", "docx", "pages", "rtf", "txt", "md", "odt" -> Color(0xFF0A84FF)
        "xls", "xlsx", "csv", "numbers", "ods" -> Color(0xFF34C759)
        "ppt", "pptx", "key", "odp" -> Color(0xFFFF9500)
        "zip", "rar", "7z", "tar", "gz", "bz2" -> Color(0xFF8E8E93)
        "jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "tif", "tiff", "bmp" ->
            Color(0xFFAF52DE)
        "mp4", "mov", "m4v", "avi", "mkv", "webm" -> Color(0xFFFF375F)
        "mp3", "m4a", "wav", "aac", "flac", "aiff", "caf" -> Color(0xFFFF9F0A)
        else -> Color(0xFF5AC8FA)
    }
    return FileBadge(label, tint)
}

@Composable
internal fun FileTypeChip(
    fileName: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val badge = fileBadgeFor(fileName)
    // 字号跟着图标尺寸走：同一个组件既用在 40dp 的列表行，也用在 64dp 的网格项，
    // 写死 sp 会让大图标上的字小得看不清、小图标上的字撑破方块
    val fontSize = with(LocalDensity.current) { (size.toPx() * 0.23f).toSp() }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f))
            .background(badge.tint),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = badge.label,
            color = Color.White,
            fontSize = fontSize,
            lineHeight = fontSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier,
        )
    }
}
