package cn.yunqiao.icloud.app.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import android.view.TextureView
import android.view.Surface
import android.media.MediaPlayer
import android.graphics.SurfaceTexture
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.yunqiao.icloud.app.PhotoSaveState
import cn.yunqiao.icloud.app.R
import cn.yunqiao.icloud.http.ICloudHttp
import cn.yunqiao.icloud.model.AssetSize
import cn.yunqiao.icloud.model.PhotoAsset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** 缩放下限固定为 1（不缩到比「适应屏幕」更小），上限 5 倍够看清文字细节 */
private const val MinZoom = 1f
private const val MaxZoom = 5f
private const val DoubleTapZoom = 2.5f

/**
 * 全屏查看器。
 *
 * 照片：双指缩放 / 单手平移 / 双击放大 / **上滑看详情** / **左右划切上一张、下一张**；
 * 顶部固定两个动作——「信息」与「保存原图到相册」。
 *
 * 为什么上滑没有实现成「拖动整页关闭」：这个面板是往上看信息，用户
 * 在放大状态下的竖直拖动应该是平移图片，两种语义会打架。所以只有
 * **缩放到 1 倍时**单指上滑才当作「开面板」，放大后一律让给平移。
 *
 * 左右切图同理只认 1 倍状态：放大后横向拖动必须是平移图片，否则想看画面
 * 右边的内容会被直接翻页。放大状态下先双击缩回，再划就是切图。
 *
 * 视频不走手势：系统播放器的控制条要占用横向/纵向拖动，再加一层手势
 * 只会把进度条拖坏。视频的详情改由顶部「信息」按钮打开，左右切图也
 * 只在照片之间生效（从视频退出后重新点开一张即可继续划）。
 */
@Composable
fun AssetViewer(
    asset: PhotoAsset,
    http: ICloudHttp?,
    saveState: PhotoSaveState?,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    hasPrev: Boolean = false,
    hasNext: Boolean = false,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    // 相邻两张的中图地址：提前下到字节缓存里，划过去时不用等网络
    prefetchUrls: List<String> = emptyList(),
) {
    val context = LocalContext.current

    var bitmap by remember(asset.recordName) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(asset.recordName) { mutableStateOf(false) }
    var details by remember(asset.recordName) { mutableStateOf<PhotoDetails?>(null) }
    var placeName by remember(asset.recordName) { mutableStateOf<String?>(null) }
    var showInfo by remember(asset.recordName) { mutableStateOf(false) }
    // 视频的像素尺寸和时长都只能等播放器就绪后才知道。
    // 苹果对一部分老视频不给 `duration`（列表里只有播放图标、没有时间），
    // 播放器解码出来的是权威值，顺带回填到信息面板。
    var videoSize by remember(asset.recordName) { mutableStateOf(IntSize.Zero) }
    var videoDuration by remember(asset.recordName) { mutableStateOf<Double?>(null) }
    // 播放控制条：自己画，不用系统的 MediaController —— 后者在 Compose 的
    // AndroidView 里定位会错乱（按钮跑到屏幕边缘外），还会把触摸整块吞掉，
    // 导致左右划切图完全失灵。
    var videoControls by remember(asset.recordName) { mutableStateOf(true) }

    // 实况照片的动态片段是否正在播。
    // 状态提到这一层，是因为入口有两个：顶部 LIVE 按钮（可靠）和长按（顺手，
    // 但会被三星的「智能选择/AI 选择」抢走，所以不能只靠它）。
    var liveMotion by remember(asset.recordName) { mutableStateOf(false) }

    // 露出 3.5 秒后自动收起，和系统播放器的习惯一致
    LaunchedEffect(asset.recordName, videoControls) {
        if (videoControls) {
            delay(3_500)
            videoControls = false
        }
    }

    val mediumUrl = asset.versions[AssetSize.MEDIUM]?.downloadUrl
        ?: asset.bestVersion?.downloadUrl

    LaunchedEffect(asset.recordName, http, mediumUrl) {
        if (asset.isVideo) {
            details = readPhotoDetails(context, asset, null)
            return@LaunchedEffect
        }
        if (http == null || mediumUrl == null) {
            failed = true
            return@LaunchedEffect
        }
        // 字节只下一次：既用来解码大图，也用来读 EXIF
        val bytes = runCatching { MediaCache.loadBytes(http, mediumUrl) }.getOrNull()
        if (bytes == null) {
            failed = true
            return@LaunchedEffect
        }
        bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        failed = bitmap == null
        details = readPhotoDetails(context, asset, bytes)

    }

    // 地名反查单独跑：Geocoder 在无 Google 服务的设备上可能卡很久，
    // 超时就先显示坐标，不能让整个面板陪着等
    LaunchedEffect(asset.recordName) {
        val lat = asset.latitude ?: return@LaunchedEffect
        val lon = asset.longitude ?: return@LaunchedEffect
        placeName = withTimeoutOrNull(3_000) { lookupPlaceName(context, lat, lon) }
    }

    // 预取相邻张：左右划是「立刻要有下一张」的操作，等它慢慢转圈会很卡顿。
    // 串行下载（先下一张再上一张），不抢当前这张的带宽；命中缓存直接返回。
    LaunchedEffect(prefetchUrls, http) {
        if (http == null) return@LaunchedEffect
        prefetchUrls.forEach { url ->
            runCatching { MediaCache.loadBytes(http, url) }
        }
    }

    val saving = saveState?.recordName == asset.recordName

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (asset.isVideo) {
                VideoPlayer(
                    asset = asset,
                    http = http,
                    hasPrev = hasPrev,
                    hasNext = hasNext,
                    showControls = videoControls,
                    onToggleControls = { videoControls = !videoControls },
                    onNavigate = { dir -> if (dir < 0) onPrev() else onNext() },
                    onVideoSize = { w, h -> videoSize = IntSize(w, h) },
                    onDuration = { seconds -> videoDuration = seconds },
                )
            } else {
                PhotoPage(
                    asset = asset,
                    bitmap = bitmap,
                    failed = failed,
                    http = http,
                    motionPlaying = liveMotion,
                    onMotionChange = { liveMotion = it },
                    hasPrev = hasPrev,
                    hasNext = hasNext,
                    onSwipeUp = { showInfo = true },
                    onTap = { showInfo = false },
                    // -1 = 上一张（往右划），+1 = 下一张（往左划）
                    onNavigate = { dir -> if (dir < 0) onPrev() else onNext() },
                )
            }

            // ---------------------------------------------------------- 顶部工具条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.viewer_close),
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.weight(1f))
                // 实况照片：iOS 在顶栏也有这么一个 LIVE 开关，点一下播一遍动态。
                // 长按虽然也能触发，但三星会把长按当成「智能选择」，所以必须有这个按钮
                if (!asset.isVideo && asset.liveVideoUrl != null) {
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (liveMotion) Color.White else Color.White.copy(alpha = 0.18f),
                            )
                            .clickable { liveMotion = !liveMotion }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.photo_live),
                            color = if (liveMotion) Color.Black else Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                IconButton(onClick = { showInfo = !showInfo }) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.viewer_info),
                        tint = if (showInfo) Color.White else Color.White.copy(alpha = 0.9f),
                    )
                }
                DownloadAction(
                    state = if (saving) saveState else null,
                    onDownload = onDownload,
                )
            }

            // 照片才提示「上滑」；视频的入口是右上角的 i
            if (!asset.isVideo && !showInfo) {
                Text(
                    text = stringResource(R.string.viewer_swipe_hint),
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp),
                )
            }

            // ---------------------------------------------------------- 详情面板
            AnimatedVisibility(
                visible = showInfo,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                InfoSheet(
                    asset = asset,
                    details = details,
                    placeName = placeName,
                    videoSize = videoSize,
                    videoDuration = videoDuration,
                    onClose = { showInfo = false },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- 工具条

/**
 * 下载按钮的三态：可下载 / 下载中（带百分比）/ 已完成。
 *
 * 已完成的勾不做自动消失——用户点完就想看到「确实存好了」，
 * 2 秒后悄悄变回去反而让人怀疑。
 */
@Composable
private fun DownloadAction(state: PhotoSaveState?, onDownload: () -> Unit) {
    IconButton(onClick = onDownload, enabled = state == null || state.done) {
        when {
            state != null && !state.done -> Box(
                Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
                Text(
                    text = "${(state.progress * 100).toInt()}",
                    color = Color.White,
                    fontSize = 8.sp,
                )
            }

            state?.done == true -> Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.viewer_saved),
                tint = Color.White,
            )

            else -> Icon(
                imageVector = Icons.Default.Download,
                contentDescription = stringResource(R.string.viewer_download),
                tint = Color.White,
            )
        }
    }
}

// ---------------------------------------------------------------------- 照片

@Composable
private fun PhotoPage(
    asset: PhotoAsset,
    bitmap: Bitmap?,
    failed: Boolean,
    http: ICloudHttp?,
    motionPlaying: Boolean,
    onMotionChange: (Boolean) -> Unit,
    hasPrev: Boolean,
    hasNext: Boolean,
    onSwipeUp: () -> Unit,
    onTap: () -> Unit,
    onNavigate: (Int) -> Unit,
) {
    val assetKey = asset.recordName
    // 手势回调运行在 AwaitPointerEventScope（受限挂起作用域）里，不能调用
    // Animatable 的 suspend API，所以这里用普通状态 + 外部协程做回弹动画
    // 三个状态都按 assetKey 重置：换一张图必须回到「1 倍、居中、没被拖动」
    val scale = remember(assetKey) { mutableFloatStateOf(MinZoom) }
    val offset = remember(assetKey) { mutableStateOf(Offset.Zero) }
    // 左右翻页的跟手位移单独一个分量，不和缩放的平移混用，
    // 否则翻页动画会把放大后的偏移一起带歪
    val dragX = remember(assetKey) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    // 实况照片：按住 400ms 才当长按（跟系统 ViewConfiguration 一致），
    // 松手立刻收回静态画面 —— 和 iOS 里按住看动态的手感一致。
    // 放大状态下不给播：动态片段是按「适配屏幕」画的，和放大后的照片对不上。
    val liveUrl = asset.liveVideoUrl
    var pressing by remember(assetKey) { mutableStateOf(false) }
    LaunchedEffect(pressing, liveUrl, assetKey) {
        if (liveUrl == null) return@LaunchedEffect
        if (!pressing) {
            // 松手立刻收回动态、回到静态照片（iOS 的手感）
            onMotionChange(false)
            return@LaunchedEffect
        }
        delay(400)
        // 放大状态下不播：动态片段是按「适配屏幕」画的，和放大后的画面对不上
        if (pressing && scale.floatValue <= MinZoom + 0.01f) onMotionChange(true)
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    // graphicsLayer 的 lambda 在绘制阶段才读状态：缩放/平移只重绘图层，
                    // 不触发重组，手指再快也不掉帧（这也是缩略图墙那次的同一个结论）
                    .graphicsLayer {
                        scaleX = scale.floatValue
                        scaleY = scale.floatValue
                        translationX = offset.value.x + dragX.floatValue
                        translationY = offset.value.y
                        transformOrigin = TransformOrigin.Center
                    }
                    .zoomable(
                        scale = scale,
                        offset = offset,
                        dragX = dragX,
                        contentAspect = bmp.width.toFloat() / bmp.height,
                        hasPrev = hasPrev,
                        hasNext = hasNext,
                        onSwipeUp = onSwipeUp,
                        onTap = onTap,
                        onPressChange = { pressing = it },
                        onDoubleTap = { from, to, fromOffset, toOffset ->
                            scope.launch {
                                animate(from, to) { value, _ -> scale.floatValue = value }
                                animate(
                                    typeConverter = Offset.VectorConverter,
                                    initialValue = fromOffset,
                                    targetValue = toOffset,
                                ) { value, _ -> offset.value = value }
                            }
                        },
                        // 划够了：先把图推出屏幕，再换数据，视觉上就是「翻过去」
                        onDragOut = { dir, from, viewportWidth ->
                            scope.launch {
                                animate(
                                    initialValue = from,
                                    targetValue = if (dir > 0) -viewportWidth else viewportWidth,
                                    animationSpec = tween(160),
                                ) { value, _ -> dragX.floatValue = value }
                                onNavigate(dir)
                            }
                        },
                        // 没划够 / 到头了：弹回中间
                        onDragSettle = { from ->
                            scope.launch {
                                animate(
                                    initialValue = from,
                                    targetValue = 0f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                ) { value, _ -> dragX.floatValue = value }
                            }
                        },
                    ),
            )

            failed -> Text(
                text = stringResource(R.string.viewer_load_failed),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> CircularProgressIndicator(color = Color.White)
        }

        // 实况照片的动态片段：盖在静态图上播一遍。播完留在最后一帧，
        // 等手指抬起时由上面的状态把这一层撤掉，画面自然回到静态。
        if (motionPlaying && liveUrl != null) {
            LiveMotion(
                assetKey = assetKey,
                url = liveUrl,
                http = http,
                onFinished = { onMotionChange(false) },
            )
        }
    }
}

/**
 * 实况照片那段 2~3 秒的动态影片。
 *
 * 只在长按期间存在：组合进树 → 下载 → 播放一遍 → 停在最后一帧；
 * 松手时整个 composable 被移除，`DisposableEffect` 负责把解码器放掉，
 * 不然声音会留在后台继续响（视频页那边的同一个坑）。
 */
@Composable
private fun LiveMotion(
    assetKey: String,
    url: String,
    http: ICloudHttp?,
    onFinished: () -> Unit,
) {
    var file by remember(assetKey) { mutableStateOf<File?>(null) }
    var aspect by remember(assetKey) { mutableFloatStateOf(0f) }
    var view by remember(assetKey) { mutableStateOf<TextureVideoView?>(null) }

    LaunchedEffect(assetKey, url, http) {
        if (http == null) return@LaunchedEffect
        file = MediaCache.ensureVideoFile(
            http = http,
            url = url,
            key = "live_$assetKey",
            extension = "mp4",
            onProgress = null,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            view?.release()
            view = null
        }
    }

    val local = file ?: return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                TextureVideoView(context).apply {
                    view = this
                    onPrepared = { w, h, _ ->
                        if (w > 0 && h > 0) aspect = w.toFloat() / h
                    }
                    // 播完一遍就回到静态照片；长按中途松手则由按住状态那边收回
                    onCompletion = { onFinished() }
                    setSource(local.absolutePath)
                }
            },
            // 按视频比例适配屏幕（和照片的 ContentScale.Fit 一致），
            // 直接 fillMaxSize 会把画面拉变形
            modifier = Modifier.aspectRatio(
                ratio = if (aspect > 0f) aspect else 1f,
                matchHeightConstraintsFirst = true,
            ),
        )
    }
}

/**
 * 图片的缩放/平移/双击/上滑/左右翻页手势。
 *
 * 几个关键取舍：
 * - 手势运行在 `AwaitPointerEventScope`（受限挂起作用域）里，**不能**调 Animatable 的
 *   suspend API，所以缩放/位移用普通 State 同步写，需要过渡的动作用外部 scope 跑 `animate`；
 * - 每帧的变换都做边界夹紧，依据是**图片适配后的实际尺寸**而不是容器尺寸，
 *   否则竖图放大后能拖出一大片黑边；
 * - 双指按下后禁用单指逻辑（`multi` 标记），避免一根手指先抬起时画面突然跳一下；
 * - 只有缩放到 1 倍时单指上滑才触发「看信息」、横向划才翻页；放大后拖动一律是平移图片，
 *   同一个动作不能有两个意思；
 * - 横向/竖向用「先超过 touchSlop 的那一轴」锁方向，避免斜着划时既翻页又弹面板。
 */
private fun Modifier.zoomable(
    scale: MutableFloatState,
    offset: MutableState<Offset>,
    dragX: MutableFloatState,
    contentAspect: Float,
    hasPrev: Boolean,
    hasNext: Boolean,
    onSwipeUp: () -> Unit,
    onTap: () -> Unit,
    onDoubleTap: (Float, Float, Offset, Offset) -> Unit,
    onDragOut: (dir: Int, from: Float, viewportWidth: Float) -> Unit,
    onDragSettle: (from: Float) -> Unit,
    // 按下/抬起。实况照片靠它计时：按住不放 400ms 以上才开始播动态片段。
    // 这里只报「手指在不在屏幕上」，计时和播放交给上层 composable 的协程，
    // 因为受限挂起作用域里起不了 delay。
    onPressChange: (Boolean) -> Unit = {},
): Modifier = pointerInput(contentAspect, hasPrev, hasNext) {
    var lastTapAt = 0L

    fun contentSize(viewport: IntSize): Size {
        val vw = viewport.width.toFloat()
        val vh = viewport.height.toFloat()
        if (contentAspect <= 0f) return Size(vw, vh)
        val fitW = min(vw, vh * contentAspect)
        return Size(fitW, fitW / contentAspect)
    }

    fun clamp(value: Offset, zoom: Float, viewport: IntSize): Offset {
        if (zoom <= MinZoom) return Offset.Zero
        val content = contentSize(viewport)
        val maxX = ((content.width * zoom - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((content.height * zoom - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(
            value.x.coerceIn(-maxX, maxX),
            value.y.coerceIn(-maxY, maxY),
        )
    }

    awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            onPressChange(true)
            try {
            var multi = false
            // 手指真实位移，用来判方向；dragX 那个状态存的是加过阻尼的视觉位移
            var fingerX = 0f
            var fingerY = 0f
            var horizontal = false
            var moved = false

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break

                if (pressed.size >= 2) {
                    multi = true
                    // 第二根手指一落下就放弃翻页意图，并把跟手位移归零，
                    // 否则接着做缩放会带着一段偏移，看着像图片被撕开
                    horizontal = false
                    if (dragX.floatValue != 0f) dragX.floatValue = 0f
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (zoom != 1f || pan != Offset.Zero) {
                        val next = (scale.floatValue * zoom).coerceIn(MinZoom, MaxZoom)
                        scale.floatValue = next
                        offset.value = clamp(offset.value + pan, next, size)
                        event.changes.forEach { it.consume() }
                    }
                } else if (!multi) {
                    val change = pressed.first()
                    val delta = change.positionChange()
                    fingerX += delta.x
                    fingerY += delta.y
                    if (abs(delta.x) > 0.5f || abs(delta.y) > 0.5f) moved = true
                    if (scale.floatValue > MinZoom + 0.01f) {
                        // 放大状态：单指就是平移
                        offset.value = clamp(offset.value + delta, scale.floatValue, size)
                        change.consume()
                    } else {
                        // 1 倍状态：先锁轴，再决定是翻页还是留给上滑
                        if (!horizontal &&
                            abs(fingerX) > viewConfiguration.touchSlop &&
                            abs(fingerX) > abs(fingerY) * 1.2f
                        ) {
                            horizontal = true
                        }
                        if (horizontal) {
                            // 到头了只给一点点橡皮筋位移，让人知道没有下一张，
                            // 而不是跟着手指滑出一大片黑
                            val available = if (fingerX > 0) hasPrev else hasNext
                            val damping = if (available) 0.55f else 0.12f
                            dragX.floatValue = fingerX * damping
                            change.consume()
                        }
                    }
                }
            }

            if (!multi) {
                if (!moved) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapAt < 300) {
                        lastTapAt = 0
                        val target = if (scale.floatValue > DoubleTapZoom * 0.6f) MinZoom else DoubleTapZoom
                        val startScale = scale.floatValue
                        val startOffset = offset.value
                        val endOffset = if (target == MinZoom) Offset.Zero
                        else clamp(startOffset, target, size)
                        // 受限作用域里起不了协程，交给宿主 scope
                        onDoubleTap(startScale, target, startOffset, endOffset)
                    } else {
                        lastTapAt = now
                        onTap()
                    }
                } else if (horizontal) {
                    // 往左拖（dragX < 0）= 看下一张
                    val dir = if (dragX.floatValue < 0f) 1 else -1
                    val available = if (dir > 0) hasNext else hasPrev
                    val passed = abs(dragX.floatValue) > size.width * 0.2f
                    if (available && passed) {
                        onDragOut(dir, dragX.floatValue, size.width.toFloat())
                    } else {
                        onDragSettle(dragX.floatValue)
                    }
                } else if (
                    scale.value <= MinZoom + 0.01f &&
                    fingerY < -80f &&
                    abs(fingerY) > abs(fingerX) * 1.2f
                ) {
                    onSwipeUp()
                }
            }

            // 缩回 1 倍时把位移归零，否则图片会停在偏心位置，看着像卡住了。
            // 这里直接清空即可：真正用到动画的是双击与「缩放到底」两种情形，
            // 手指刚离开时残留的偏移本来就接近 0，没必要再跑一段动画。
            if (scale.floatValue <= MinZoom + 0.01f) {
                scale.floatValue = MinZoom
                offset.value = Offset.Zero
            }
            } finally {
                // 手指离开（含手势被取消）一定要复位，否则实况照片会一直以为还按着
                onPressChange(false)
            }
        }
}

// ---------------------------------------------------------------------- 视频翻页

/**
 * 视频页的手势层。
 *
 * 必须是**盖在视频上面的一层透明 Box**，而不是挂在 VideoView 的父容器上：
 * AndroidView 作为一个整体会先把触摸吃掉，挂在父层的手势根本收不到事件
 * （这也是之前「明明启用了却划不动」的原因）。
 *
 * 视频本身不需要触摸：播放/暂停在自绘控制条上，点画面只是显隐控制条，
 * 所以这一层把触摸全接过来没有任何副作用。
 */
@Composable
private fun BoxScope.VideoGestureLayer(
    assetKey: String,
    hasPrev: Boolean,
    hasNext: Boolean,
    onTap: () -> Unit,
    onNavigate: (Int) -> Unit,
) {
    // dragX 只用来累计「够不够翻页」，不上屏：视频页不做跟手位移。
    // 一是播放器对象在位移过程中容易被重建，二是跟手位移对视频没有信息量，
    // 划够距离直接换页更干脆。
    val dragX = remember(assetKey) { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .matchParentSize()
            .pagerSwipe(
                dragX = dragX,
                hasPrev = hasPrev,
                hasNext = hasNext,
                onTap = onTap,
                onDragOut = { dir, _, _ -> onNavigate(dir) },
                onDragSettle = { dragX.floatValue = 0f },
            ),
    )
}

/**
 * 纯横向翻页手势（给视频用，照片的翻页合并在 `zoomable` 里）。
 *
 * 只认横向：一旦锁了横向轴就吃掉事件，别的方向一点不碰。
 * `change.isConsumed` 为真说明子组件（自己画的控制条、进度条）已经接手这次触摸，
 * 这一轮立刻放弃，不去抢——这正是要求控制条用 Compose 自绘的原因：
 * 系统的 MediaController 在 Compose 里既定位错乱又吞掉整块触摸，根本分不清。
 */
private fun Modifier.pagerSwipe(
    dragX: MutableFloatState,
    hasPrev: Boolean,
    hasNext: Boolean,
    onTap: () -> Unit,
    onDragOut: (dir: Int, from: Float, viewportWidth: Float) -> Unit,
    onDragSettle: (from: Float) -> Unit,
): Modifier = pointerInput(hasPrev, hasNext) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var fingerX = 0f
        var fingerY = 0f
        var horizontal = false
        var blocked = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            val change = pressed.first()
            if (change.isConsumed) {
                // 控制条 / 进度条接手了这次触摸，别插一脚
                blocked = true
                break
            }
            val delta = change.positionChange()
            fingerX += delta.x
            fingerY += delta.y
            if (!horizontal &&
                abs(fingerX) > viewConfiguration.touchSlop &&
                abs(fingerX) > abs(fingerY) * 1.2f
            ) {
                horizontal = true
            }
            if (horizontal) {
                val available = if (fingerX > 0) hasPrev else hasNext
                dragX.floatValue = fingerX * (if (available) 0.55f else 0.12f)
                change.consume()
            }
        }

        if (blocked) return@awaitEachGesture

        when {
            horizontal -> {
                val dir = if (dragX.floatValue < 0f) 1 else -1
                val available = if (dir > 0) hasNext else hasPrev
                val passed = abs(dragX.floatValue) > size.width * 0.2f
                if (available && passed) {
                    onDragOut(dir, dragX.floatValue, size.width.toFloat())
                } else {
                    onDragSettle(dragX.floatValue)
                }
            }

            // 没锁横向也没被拦走 = 点了一下，交给播放器显隐控制条
            abs(fingerX) < viewConfiguration.touchSlop &&
                abs(fingerY) < viewConfiguration.touchSlop -> {
                    onTap()
                }

            else -> if (dragX.floatValue != 0f) onDragSettle(dragX.floatValue)
        }
    }
}

// ---------------------------------------------------------------------- 详情面板

@Composable
private fun InfoSheet(
    asset: PhotoAsset,
    details: PhotoDetails?,
    placeName: String?,
    videoSize: IntSize,
    videoDuration: Double?,
    onClose: () -> Unit,
) {
    // 逐个取字符串资源再拼行：buildList 的 lambda 里直接调 stringResource
    // 不是所有 Compose 版本都放行，写成平铺最稳
    val labelTakenAt = stringResource(R.string.info_taken_at)
    val labelPlace = stringResource(R.string.info_place)
    val labelResolution = stringResource(R.string.info_resolution)
    val labelDuration = stringResource(R.string.info_duration)
    val labelFileSize = stringResource(R.string.info_file_size)
    val labelCamera = stringResource(R.string.info_camera)
    val labelLens = stringResource(R.string.info_lens)
    val labelFocal = stringResource(R.string.info_focal)
    val labelAperture = stringResource(R.string.info_aperture)
    val labelShutter = stringResource(R.string.info_shutter)
    val labelIso = stringResource(R.string.info_iso)

    val rows = mutableListOf<Pair<String, String>>()
    details?.takenAt?.takeIf { it > 0 }?.let {
        rows += labelTakenAt to formatTakenAt(it)
    }
    val lat = details?.latitude
    val lon = details?.longitude
    if (lat != null && lon != null) {
        rows += labelPlace to (placeName ?: formatCoordinate(lat, lon))
    }
    val width = details?.width ?: videoSize.width.takeIf { it > 0 }
    val height = details?.height ?: videoSize.height.takeIf { it > 0 }
    if (width != null && height != null) {
        rows += labelResolution to "$width × $height"
    }
    // 先用记录里的值，没有就用播放器解码出来的
    (details?.durationSeconds ?: videoDuration)?.takeIf { it > 0 }?.let {
        rows += labelDuration to formatDuration(it)
    }
    details?.fileSize?.takeIf { it > 0 }?.let {
        rows += labelFileSize to formatBytes(it)
    }
    details?.camera?.let { rows += labelCamera to it }
    details?.lens?.let { rows += labelLens to it }
    details?.focalLength?.let { rows += labelFocal to it }
    details?.aperture?.let { rows += labelAperture to it }
    details?.shutter?.let { rows += labelShutter to it }
    details?.iso?.let { rows += labelIso to it }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color(0xFF1C1C1E),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            )
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = asset.filename,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.viewer_close),
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            if (rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.info_reading),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rows.forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                text = label,
                                color = Color.White.copy(alpha = 0.55f),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(84.dp),
                            )
                            Text(
                                text = value,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- 视频

@Composable
private fun VideoPlayer(
    asset: PhotoAsset,
    http: ICloudHttp?,
    hasPrev: Boolean,
    hasNext: Boolean,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    onNavigate: (Int) -> Unit,
    onVideoSize: (Int, Int) -> Unit,
    onDuration: (Double) -> Unit,
) {
    var file by remember(asset.recordName) { mutableStateOf<File?>(null) }
    var downloadProgress by remember(asset.recordName) { mutableStateOf(0f) }
    var message by remember(asset.recordName) { mutableStateOf<String?>(null) }
    var videoRef by remember(asset.recordName) { mutableStateOf<TextureVideoView?>(null) }
    var playing by remember(asset.recordName) { mutableStateOf(true) }
    var positionMs by remember(asset.recordName) { mutableLongStateOf(0L) }
    var durationMs by remember(asset.recordName) { mutableLongStateOf(0L) }
    // 视频宽高比：让播放器按视频本身的比例居中显示（上下留黑边），
    // 而不是把画面拉满整屏 —— 竖屏视频铺满会裁掉两侧内容。
    var videoAspect by remember(asset.recordName) { mutableFloatStateOf(0f) }

    LaunchedEffect(asset.recordName, http) {
        if (http == null) {
            message = "会话已失效"
            return@LaunchedEffect
        }
        try {
            // 中等分辨率体积小、起播快；没有就退回原始文件
            val url = asset.versions[AssetSize.MEDIUM]?.downloadUrl
                ?: asset.bestVersion?.downloadUrl
            if (url == null) {
                message = "这个视频没有可播放的版本"
                return@LaunchedEffect
            }
            file = MediaCache.ensureVideoFile(
                http = http,
                url = url,
                key = "play_${asset.recordName}",
                extension = "mp4",
                onProgress = { downloadProgress = it },
            )
            if (file == null) message = "视频下载失败，请检查网络"
        } catch (e: Exception) {
            message = e.message ?: "视频加载失败"
        }
    }

    // VideoView 没有「进度变化」回调，播放中只能定时读一次；250ms 足够让滑块平滑
    LaunchedEffect(asset.recordName, file) {
        while (true) {
            val view = videoRef
            if (view != null) {
                val nowPlaying = view.isPlayingNow
                if (nowPlaying != playing) playing = nowPlaying
                if (nowPlaying) {
                    positionMs = view.position.toLong()
                    if (durationMs <= 0L) durationMs = view.duration.toLong()
                }
            }
            delay(250)
        }
    }

    val local = file
    Box(Modifier.fillMaxSize()) {
        when {
            // 换视频时必须整块重建：AndroidView 的 factory 只在首次组合时跑，
            // 沿用旧节点会继续播上一条；key 一变就销毁旧节点，DisposableEffect
            // 负责把解码器停掉，不然声音会跟着画面一起留在后台
            local != null -> key(asset.recordName, local.absolutePath) {
                DisposableEffect(Unit) {
                    onDispose {
                        videoRef?.let { view -> runCatching { view.release() } }
                        videoRef = null
                    }
                }
                AndroidView(
                    factory = { context ->
                        TextureVideoView(context).apply {
                            videoRef = this
                            onPrepared = { width, height, durMs ->
                                durationMs = durMs.toLong()
                                if (width > 0 && height > 0) {
                                    videoAspect = width.toFloat() / height
                                }
                                onVideoSize(width, height)
                                if (durMs > 0) onDuration(durMs / 1000.0)
                                playing = true
                            }
                            onError = { message = "系统播放器无法解码这个视频" }
                            onCompletion = {
                                playing = false
                                // 播完把进度钉在末尾，滑块不会停在半路
                                if (durationMs > 0L) {
                                    positionMs = durationMs
                                    // 停在最后一帧：直接留在 EOS 上，部分机型会把
                                    // Surface 清成纯黑（看起来像播放器崩了）。
                                    // 往回退 100ms 强制解出一帧，画面就留住。
                                    seekTo((durationMs - 100L).toInt().coerceAtLeast(0))
                                }
                            }
                            setSource(local.absolutePath)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .then(
                            if (videoAspect > 0f) {
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(videoAspect)
                            } else {
                                Modifier.fillMaxSize()
                            },
                        ),
                )
            }

            message != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = message.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.size(48.dp),
                        color = Color.White,
                    )
                    Text(
                        text = if (downloadProgress > 0f) {
                            "正在准备视频 ${(downloadProgress * 100).toInt()}%"
                        } else {
                            "正在准备视频…"
                        },
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }

        // 手势层在视频之上、控制条之下：视频自己收不到触摸正合适，
        // 点画面显隐控制条、左右划换上下一个都在这一层完成
        VideoGestureLayer(
            assetKey = asset.recordName,
            hasPrev = hasPrev,
            hasNext = hasNext,
            onTap = onToggleControls,
            onNavigate = onNavigate,
        )

        // 自己画的播放控制条：定位准确、手势也不会和翻页打架
        if (local != null && showControls) {
            VideoControls(
                playing = playing,
                positionMs = positionMs,
                durationMs = durationMs,
                onTogglePlay = {
                    videoRef?.let { view ->
                        if (view.isPlayingNow) {
                            view.pause()
                            playing = false
                        } else {
                            // 已经播到结尾：MediaPlayer.start() 会立刻再次收尾，
                            // 看起来像「点了没反应」。先回到 0 再播
                            val dur = view.duration
                            if (dur > 0 && view.position >= dur - 300) {
                                view.seekTo(0)
                                positionMs = 0L
                            }
                            view.play()
                            playing = true
                        }
                    }
                },
                onSeek = { ms ->
                    // 不许拖到正好等于总时长：那样直接落到 EOS，画面会变黑，
                    // 而且用户其实是想「看结尾」而不是「播完」
                    val max = if (durationMs > 0L) durationMs - 100L else ms
                    val target = ms.coerceIn(0L, max.coerceAtLeast(0L))
                    positionMs = target
                    videoRef?.seekTo(target.toInt())
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * 播放控制条：播放/暂停 + 当前时间 + 进度 + 总时长。
 *
 * 为什么不用系统的 MediaController：它在 Compose 的 AndroidView 里拿不到正确的
 * 锚点位置，控件会跑到屏幕边缘外面，并且把整块触摸吃掉——左右划切图和点画面
 * 都跟着失灵。自己画一个既是 Compose 组件，也能靠 `isConsumed` 和翻页手势分清楚。
 */
@Composable
private fun VideoControls(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            // 自己把点击吃掉：不然点在控制条的空白处会穿透到下面的翻页手势层，
            // 变成「点一下 → 控制条消失」，看着像按钮坏了
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            // 不用 navigationBarsPadding：全屏 Dialog 里它算出来的内边距会把
            // 整个控制条挤到屏幕最底下、内容全被压缩掉。固定留一条底部空白更稳。
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onTogglePlay, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
            )
        }
        Text(
            text = formatDuration(positionMs / 1000.0),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
        Slider(
            value = if (durationMs > 0L) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            },
            onValueChange = { fraction -> onSeek((fraction * durationMs).toLong()) },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        Text(
            text = formatDuration(durationMs / 1000.0),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * 用 TextureView + MediaPlayer 代替 VideoView。
 *
 * VideoView 内部是 SurfaceView，画面画在独立的硬件图层上（相当于在窗口里挖了个洞），
 * 同层 Compose 绘制的播放控制条永远被它盖住，`screencap` 也只能拍到一片黑，
 * 连验收都没法做。TextureView 是普通 View，作为纹理参与正常合成，
 * 控制条叠在上面看得见、点得到，截屏也正常。
 */
private class TextureVideoView(context: Context) :
    TextureView(context), TextureView.SurfaceTextureListener {

    private var player: MediaPlayer? = null
    private var pendingPath: String? = null

    var onPrepared: ((width: Int, height: Int, durationMs: Int) -> Unit)? = null
    var onError: (() -> Unit)? = null
    // 播完必须显式通知：MediaPlayer 走到 EOS 后 isPlaying 变 false，但轮询有时
    // 抓不到这一帧（短视频尤其明显），按钮就会一直停在「暂停」图标上
    var onCompletion: (() -> Unit)? = null

    init {
        surfaceTextureListener = this
    }

    fun setSource(path: String) {
        pendingPath = path
        // SurfaceTexture 可能还没就绪，等 onSurfaceTextureAvailable 再真正开播
        if (isAvailable) openPlayer()
    }

    fun play() {
        runCatching { player?.start() }
    }

    fun pause() {
        runCatching { player?.pause() }
    }

    fun seekTo(ms: Int) {
        runCatching { player?.seekTo(ms) }
    }

    fun release() = releasePlayer()

    val position: Int get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    val duration: Int get() = runCatching { player?.duration ?: 0 }.getOrDefault(0)
    val isPlayingNow: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    private fun openPlayer() {
        val path = pendingPath ?: return
        val surface = surfaceTexture ?: return
        releasePlayer()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(path)
            mp.setSurface(Surface(surface))
            mp.setOnPreparedListener { p ->
                onPrepared?.invoke(p.videoWidth, p.videoHeight, p.duration)
                runCatching { p.start() }
            }
            mp.setOnErrorListener { _, _, _ ->
                onError?.invoke()
                true
            }
            mp.setOnCompletionListener { onCompletion?.invoke() }
            mp.prepareAsync()
        } catch (e: Exception) {
            onError?.invoke()
        }
    }

    private fun releasePlayer() {
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        openPlayer()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
