package cn.yunqiao.icloud.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.yunqiao.icloud.app.AppViewModel
import cn.yunqiao.icloud.app.DriveTransfer
import cn.yunqiao.icloud.app.DriveView
import cn.yunqiao.icloud.app.R
import cn.yunqiao.icloud.model.AccountInfo
import cn.yunqiao.icloud.model.AssetSize
import cn.yunqiao.icloud.model.DriveEntry
import cn.yunqiao.icloud.model.PhotoAsset
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Tab { PHOTOS, DRIVE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, account: AccountInfo, onOpenSettings: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.PHOTOS) }
    var menuOpen by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<PhotoAsset?>(null) }
    // 600dp 是 Material 的「紧凑 / 中等」分界：手机全在紧凑档，折叠屏展开和平板进宽档
    val wide = currentWindowWidth() != WindowWidth.COMPACT

    // 系统的照片选择器：不需要任何存储权限，用户只把选中的几项授权给本应用
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(100),
    ) { uris -> vm.uploadPhotos(uris) }

    // 云盘用通用的文件选择器（照片之外的文档也能传）
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.uploadToDrive(it) } }

    val snackbarHost = remember { SnackbarHostState() }
    val zoomHint = stringResource(R.string.photos_zoom_hint)
    val hasPhotos = vm.photos.isNotEmpty()
    // 双指缩放没有任何可见入口，第一次进照片页时提示一次，之后不再打扰
    LaunchedEffect(tab, hasPhotos) {
        if (tab == Tab.PHOTOS && hasPhotos && !vm.zoomHintShown) {
            vm.markZoomHintShown()
            snackbarHost.showSnackbar(
                message = zoomHint,
                duration = SnackbarDuration.Short,
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            // 不用 M3 的 TopAppBar：它的高度是写死的 56/64dp，而这里标题是两行
            // （应用名 + 品牌行）。系统字体调到 1.3~2.0 倍时第二行会被裁掉一半，
            // 换成自带 safeDrawing 的 Row + heightIn(min) 就能随字体一起长高。
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                            ),
                        )
                        .heightIn(min = 56.dp)
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "iCloudBridge",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "by Mendacious",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 账号原来占着标题下那行，现在那行让给品牌；
                    // 账号改贴在 ⋮ 左边，灰色小字、过长就省略，信息不丢也不抢视线。
                    Text(
                        text = account.fullName ?: account.appleId,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 132.dp)
                            .padding(end = 2.dp),
                    )
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.cd_more),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_refresh)) },
                                onClick = {
                                    menuOpen = false
                                    if (tab == Tab.PHOTOS) vm.refreshPhotos() else vm.refreshDrive()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_settings)) },
                                onClick = {
                                    menuOpen = false
                                    onOpenSettings()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_signout)) },
                                onClick = {
                                    menuOpen = false
                                    vm.signOut()
                                },
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            // 平板/折叠屏展开时把底部导航换成左侧 NavigationRail：
            // 800dp 宽下两个底部标签会被摊到屏幕两端，左边空、右边也空，
            // 看着像没做完；侧栏在 Material 3 里就是这个尺寸档的标准做法。
            if (!wide) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.PHOTOS,
                        onClick = { tab = Tab.PHOTOS },
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_photos)) },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.DRIVE,
                        onClick = { tab = Tab.DRIVE },
                        icon = { Icon(Icons.Default.Cloud, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_drive)) },
                    )
                }
            }
        },
    ) { padding ->
        Row(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (wide) {
                NavigationRail {
                    Spacer(Modifier.height(12.dp))
                    NavigationRailItem(
                        selected = tab == Tab.PHOTOS,
                        onClick = { tab = Tab.PHOTOS },
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_photos)) },
                    )
                    NavigationRailItem(
                        selected = tab == Tab.DRIVE,
                        onClick = { tab = Tab.DRIVE },
                        icon = { Icon(Icons.Default.Cloud, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_drive)) },
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    // 照片与云盘的上传是两条完全不同的通道，入口也各自独立
                    Tab.PHOTOS -> PhotosPane(
                        vm = vm,
                        onUpload = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        onOpen = { viewing = it },
                    )

                    Tab.DRIVE -> DrivePane(vm = vm, onUpload = { filePicker.launch(arrayOf("*/*")) })
                }
            }
        }
    }

    viewing?.let { asset ->
        // 前后张按网格当前的顺序算，和用户看到的一致（分页加载只会往尾部追加，
        // 已打开这一张的邻居不会因为续页而错位）
        val photos = vm.photos
        val index = photos.indexOfFirst { it.recordName == asset.recordName }
        val prev = if (index > 0) photos[index - 1] else null
        val next = if (index >= 0 && index < photos.lastIndex) photos[index + 1] else null

        fun previewUrl(of: PhotoAsset?): String? {
            // 视频的中图地址是 mp4，预取下来就是整段视频，跳过
            if (of == null || of.isVideo) return null
            return of.versions[AssetSize.MEDIUM]?.downloadUrl ?: of.bestVersion?.downloadUrl
        }

        AssetViewer(
            asset = asset,
            http = vm.client?.http,
            saveState = vm.photoSave,
            onDownload = { vm.saveOriginalToGallery(asset) },
            onDismiss = { viewing = null },
            hasPrev = prev != null,
            hasNext = next != null,
            onPrev = { prev?.let { viewing = it } },
            onNext = {
                val n = next
                if (n != null) {
                    viewing = n
                } else if (!vm.photosEnd) {
                    // 划到最后一张时顺手把下一页捞回来，下次划就有内容了
                    vm.loadMorePhotos()
                }
            },
            prefetchUrls = listOfNotNull(previewUrl(next), previewUrl(prev)),
        )
    }
}

// ---------------------------------------------------------------------- 照片

/** 相册网格的间距与内边距：iOS「照片」是几乎无缝的方块墙，2dp 已经足够区分 */
private val PhotoGridGap = 2.dp

/**
 * 双指缩放时缩略图边长的可调范围。
 *
 * 下限 56dp 保证一屏最多 6 列时每张仍能被手指点中；上限 240dp 约等于「一屏两张」，
 * 再大就失去浏览的意义、只剩逐张翻。
 */
private const val MinPhotoCell = 56f
private const val MaxPhotoCell = 240f

@Composable
private fun PhotosPane(vm: AppViewModel, onUpload: () -> Unit, onOpen: (PhotoAsset) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = vm.selectedAlbum?.name ?: stringResource(R.string.all_photos),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUpload, enabled = vm.upload == null) {
                Icon(
                    Icons.Outlined.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.drive_upload))
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = vm.selectedAlbum == null,
                    onClick = { vm.selectAlbum(null) },
                    label = { Text(stringResource(R.string.all_photos)) },
                )
            }
            items(vm.albums, key = { it.recordName }) { album ->
                FilterChip(
                    selected = vm.selectedAlbum?.recordName == album.recordName,
                    onClick = { vm.selectAlbum(album) },
                    label = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        if (vm.photos.isEmpty() && vm.photosLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (vm.photos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (vm.photosEnd) stringResource(R.string.photos_empty) else stringResource(R.string.photos_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            ZoomablePhotoGrid(vm = vm, onOpen = onOpen)
        }
    }
}

/**
 * 可双指缩放密度的照片墙。
 *
 * 手感设计（这是整个改动里最需要解释的部分）：
 *
 * 1. **手势只吃两根手指**。检查 `pressed.size >= 2` 才消费事件，单指仍然交给
 *    LazyVerticalGrid 自己滚动——否则整个相册就不能上下滑了，这是最常见的翻车点。
 * 2. **在 Initial pass 消费**。Compose 的事件传递是 Initial（根→叶）→ Main（叶→根），
 *    网格的滚动处理器在子节点、Main pass 先收到事件；等我们（父节点）在 Main 里
 *    consume，滚动已经启动，缩放和滚动会同时发生。Initial pass 抢在前面消费，
 *    滚动处理器看到 consumed 就不再接管。
 * 3. **两条值：排版值 + 视觉值**。这是「顺滑」的关键。
 *    - [layoutCell] 是交给 `GridCells.Adaptive` 的排版边长，只在**列数跨档**时才变，
 *      一次完整手势里通常只变 3~5 次；
 *    - [visualCell] 是手指决定的视觉边长，每帧连续变化。
 *    两者之差由 `graphicsLayer` 的缩放吸收（scale = visual / layout），
 *    所以画面每帧都跟手，而网格不必每帧重新测量。
 *    以前的写法每帧都改 `GridCells.Adaptive(minSize)`，等于每帧把可见的几十个格子
 *    全部重新测量、重新布局一遍，这才是「一顿一顿」的根源。
 * 4. **缩放放在 draw 阶段**。`graphicsLayer { }` 的 lambda 内的 State 读取只发生在绘制时，
 *    改值只让图层重绘，不触发重组也不触发测量，60/120Hz 都能跑满。
 * 5. **抬手才吸附**。松手后用弹簧把 [visualCell] 吸到 [layoutCell]（正好整除屏宽的那一档），
 *    缝隙才均匀；吸附过程只动图层缩放，依旧不重排。
 */
@Composable
private fun ZoomablePhotoGrid(vm: AppViewModel, onOpen: (PhotoAsset) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // 缩放时内容会超出网格区域，不裁的话会盖到标题栏和标签栏上
            .clipToBounds(),
    ) {
        val widthDp = maxWidth.value
        val gap = PhotoGridGap.value
        val initialCell = remember(widthDp) {
            // 从上次的缩放结果恢复；没捏过就按屏宽给个起点
            vm.photoCellDp.takeIf { it > 0f } ?: if (widthDp >= 600f) 132f else 104f
        }
        // 这里刻意不用 `by` 委托：委托出来是 Float，而手势回调里需要写入，
        // 只能拿到可变状态对象本身。
        val layoutCell = remember(widthDp) { mutableFloatStateOf(initialCell) }
        val visualCell = remember(widthDp) { mutableFloatStateOf(initialCell) }
        val scope = rememberCoroutineScope()
        var settleJob by remember { mutableStateOf<Job?>(null) }

        val settleToGrid = rememberUpdatedState(newValue = {
            settleJob?.cancel()
            settleJob = scope.launch {
                val target = layoutCell.floatValue
                animate(
                    initialValue = visualCell.floatValue,
                    targetValue = target,
                    animationSpec = spring(
                        dampingRatio = 0.9f,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ) { value, _ -> visualCell.floatValue = value }
                // 收尾对齐，避免浮点误差留下 0.999 的残影
                visualCell.floatValue = target
                vm.savePhotoCellDp(target)
            }
        })

        LazyVerticalGrid(
            // 自适应列数而不是写死 3 列：360dp 的三星/华为、411dp 的小米/OPPO/vivo
            // 都能按同一个物理密度排；宽屏自动多列。
            columns = GridCells.Adaptive(minSize = layoutCell.floatValue.dp),
            contentPadding = PaddingValues(PhotoGridGap),
            horizontalArrangement = Arrangement.spacedBy(PhotoGridGap),
            verticalArrangement = Arrangement.spacedBy(PhotoGridGap),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 只在这里读 State：写入时只标记图层重绘，不重组、不重新测量
                    val base = layoutCell.floatValue
                    val scale = if (base > 0f) visualCell.floatValue / base else 1f
                    scaleX = scale
                    scaleY = scale
                    // 顶部对齐，缩放时第一行留在原处，看起来像在网格上「拉近」
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .pinchToResize(
                    visualCell = visualCell,
                    layoutCell = layoutCell,
                    widthDp = widthDp,
                    gap = gap,
                    onStart = { settleJob?.cancel() },
                    onRelease = { settleToGrid.value() },
                ),
        ) {
            gridItems(vm.photos, key = { it.recordName }) { asset ->
                PhotoCell(asset = asset, http = vm.client?.http) { onOpen(asset) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                // 滚到底自动续页；列表里放一个哨兵项是最省事的做法
                LaunchedEffect(vm.photos.size) { vm.loadMorePhotos() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        vm.photosLoading -> CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )

                        vm.photosEnd -> Text(
                            text = stringResource(R.string.photos_end_count, vm.photos.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 把任意边长吸附到「正好整除当前宽度」的那一档。
 *
 * 不做这步的话，`Adaptive` 会把剩余像素摊到最后一列，缝隙宽度不一致，
 * 缩放手一抖就会出现一排宽一排窄。这里先由边长反推列数，再按列数反算精确边长，
 * 得到的就是网格实际会用的那个值。
 *
 * **这里的返回值不能再夹到 [MinPhotoCell]/[MaxPhotoCell]**：
 * 夹了就破坏「返回值 → 列数 → 实际列宽」这条闭环。例如 411dp 屏上 desired=56 时
 * 反算出的精确值是 52.3，夹成 56 之后 `Adaptive(56)` 只排 6 列、而 52.3 能排 7 列，
 * 图层的残余缩放就会算错，表现为「跨档瞬间跳一下」。范围限制只作用在手指的那一侧。
 */
private fun snapPhotoCell(widthDp: Float, cell: Float, gap: Float, padding: Float): Float {
    val available = widthDp - padding * 2
    if (available <= 0f) return cell
    val columns = ((available + gap) / (cell + gap)).roundToInt().coerceIn(1, 12)
    return (available - gap * (columns - 1)) / columns
}

/**
 * 双指开合改 [visualCell]（视觉边长）与 [layoutCell]（排版边长），松手时回调 [onRelease]。
 *
 * 只处理两根及以上手指：单指完全放行，滚动、点击都不受影响。
 * 起始边长在双指按下那一帧锁定，之后完全按「当前间距 / 起始间距」的比例算，
 * 而不是逐帧累乘——逐帧累乘会把每帧的取整误差累积起来，手指停住时画面还在慢慢漂。
 */
private fun Modifier.pinchToResize(
    visualCell: MutableFloatState,
    layoutCell: MutableFloatState,
    widthDp: Float,
    gap: Float,
    onStart: () -> Unit,
    onRelease: () -> Unit,
): Modifier = this.pointerInput(widthDp, gap) {
    awaitEachGesture {
        var startDistance = 0f
        var startVisual = 0f
        var zooming = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size < 2) {
                if (zooming) {
                    zooming = false
                    onRelease()
                }
                startDistance = 0f
                if (pressed.isEmpty()) break
                continue
            }
            val distance = (pressed[0].position - pressed[1].position).getDistance()
            if (!zooming) {
                zooming = true
                startDistance = distance
                startVisual = visualCell.floatValue
                onStart()
            } else if (startDistance > 0f && distance > 0f) {
                // 双指分开 = 放大（缩略图变大、列数变少）；双指合拢 = 缩小（列数变多）。
                val desired = (startVisual * (distance / startDistance))
                    .coerceIn(MinPhotoCell, MaxPhotoCell)
                visualCell.floatValue = desired
                // 排版值只在列数跨档时才会真正变化，其余帧写入的是同一个数，
                // Snapshot 状态按结构相等判断，不会触发重组。
                layoutCell.floatValue = snapPhotoCell(widthDp, desired, gap, gap * 2)
            }
            pressed.forEach { it.consume() }
        }
    }
}

@Composable
private fun PhotoCell(
    asset: PhotoAsset,
    http: cn.yunqiao.icloud.http.ICloudHttp?,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .clickable(onClick = onClick),
    ) {
        if (asset.isVideo) {
            // 视频的 THUMB 是 mp4 小视频，不能当图片解码，必须从里面抽帧
            VideoThumb(asset = asset, http = http, modifier = Modifier.fillMaxSize())
        } else {
            RemoteImage(
                url = asset.versions[AssetSize.THUMB]?.downloadUrl,
                http = http,
                contentDescription = asset.filename,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 实况照片：跟视频的角标分居两侧，避免一张图上两个标记打架
        if (!asset.isVideo && asset.liveVideoUrl != null) {
            Text(
                text = stringResource(R.string.photo_live),
                color = Color.White,
                fontSize = 7.sp,
                lineHeight = 9.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }

        if (asset.isVideo) {
            // 角标做成胶囊并压到 8sp：改成「时:分:秒」后字符串变长，
            // 6 列小格子（约 60dp）里再放独立图标会被挤出格子。
            // 现在用「小三角 + 时间」合成一个整体，最窄也能完整显示。
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.cd_video),
                    tint = Color.White,
                    modifier = Modifier.size(9.dp),
                )
                asset.durationSeconds?.let { seconds ->
                    Text(
                        text = formatDuration(seconds),
                        color = Color.White,
                        fontSize = 8.sp,
                        lineHeight = 10.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- 云盘

@Composable
private fun DrivePane(vm: AppViewModel, onUpload: () -> Unit) {
    // 排序放在这里而不是渲染时逐项排：条目一变就重算一次，滚动时不做无用功
    val entries = remember(vm.driveEntries) { vm.sortedDrive() }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    Column(Modifier.fillMaxSize()) {
        DriveToolbar(vm = vm, onUpload = onUpload)

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        when {
            vm.driveLoading && vm.driveEntries.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            vm.driveEntries.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.drive_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> AnimatedContent(
                targetState = vm.driveView,
                transitionSpec = {
                    fadeIn(tween(160)) togetherWith fadeOut(tween(110))
                },
                label = "driveView",
            ) { view ->
                when (view) {
                    DriveView.LIST -> DriveList(entries, listState, vm)
                    DriveView.GRID -> DriveGrid(entries, gridState, vm)
                }
            }
        }
    }
}

@Composable
private fun DriveToolbar(vm: AppViewModel, onUpload: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vm.driveStack.isNotEmpty()) {
            IconButton(onClick = { vm.driveUp() }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.cd_previous),
                )
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }

        val path = buildString {
            append(stringResource(R.string.drive_root))
            vm.driveStack.forEach { append(" / ").append(it.name) }
        }
        Text(
            text = path,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // 视图切换：图标本身表示「点一下会变成什么」，和 iOS 文件右上角的行为一致
        IconButton(
            onClick = {
                vm.updateDriveView(
                    if (vm.driveView == DriveView.LIST) DriveView.GRID else DriveView.LIST,
                )
            },
            modifier = Modifier.size(40.dp),
        ) {
            AnimatedContent(
                targetState = vm.driveView,
                transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) },
                label = "driveViewIcon",
            ) { view ->
                Icon(
                    imageVector = if (view == DriveView.LIST) {
                        Icons.Outlined.GridView
                    } else {
                        Icons.AutoMirrored.Outlined.ViewList
                    },
                    contentDescription = stringResource(
                        if (view == DriveView.LIST) R.string.cd_view_grid else R.string.cd_view_list,
                    ),
                )
            }
        }

        IconButton(onClick = onUpload, enabled = vm.upload == null, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Outlined.UploadFile,
                contentDescription = stringResource(R.string.drive_upload),
            )
        }
    }
}

@Composable
private fun DriveList(entries: List<DriveEntry>, state: LazyListState, vm: AppViewModel) {
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.drivewsid ?: it.name }) { entry ->
            DriveRow(
                entry = entry,
                transfer = vm.driveTransfer?.takeIf { it.name == entry.name },
                onOpen = { vm.openDriveEntry(entry) },
                onSave = { vm.saveDriveFile(entry) },
            )
        }
    }
}

@Composable
private fun DriveGrid(entries: List<DriveEntry>, state: LazyGridState, vm: AppViewModel) {
    LazyVerticalGrid(
        // 150dp 的最小宽度：手机上固定 2 列（iOS 图标视图也是 2 列），
        // 673dp 的折叠屏变 4 列，800dp 平板变 5 列，不会出现一排巨图
        columns = GridCells.Adaptive(minSize = 150.dp),
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItems(entries, key = { it.drivewsid ?: it.name }) { entry ->
            DriveTile(
                entry = entry,
                transfer = vm.driveTransfer?.takeIf { it.name == entry.name },
                onOpen = { vm.openDriveEntry(entry) },
                onSave = { vm.saveDriveFile(entry) },
            )
        }
    }
}

@Composable
private fun DriveRow(
    entry: DriveEntry,
    transfer: DriveTransfer?,
    onOpen: () -> Unit,
    onSave: () -> Unit,
) {
    val busy = transfer != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 点整行 = 打开（文件夹则是进入），右侧按钮才是「存到下载目录」
            .clickable(enabled = !busy, onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标区固定 40dp：文件夹是蓝色实心文件夹，文件是带扩展名色块，
        // 扫一眼就能分清「这是目录还是文档」，不用读文件名
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            DriveEntryIcon(entry = entry, size = 30.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = driveMeta(entry)
            val status = when {
                transfer == null -> null
                transfer.opening -> stringResource(R.string.drive_opening)
                else -> stringResource(R.string.drive_saving)
            }
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (transfer != null) {
            // 有总字节数就画确定进度，没有就退回转圈——两者都比「假装在做事」强
            if (transfer.progress > 0f) {
                CircularProgressIndicator(
                    progress = { transfer.progress },
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.width(8.dp))
        } else if (entry.isBrowsable) {
            // 文件夹的箭头是 iOS 列表最直观的「还能往下钻」提示
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            IconButton(onClick = onSave) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = stringResource(R.string.drive_download),
                )
            }
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(start = 70.dp),
    )
}

@Composable
private fun DriveTile(
    entry: DriveEntry,
    transfer: DriveTransfer?,
    onOpen: () -> Unit,
    onSave: () -> Unit,
) {
    val busy = transfer != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onOpen)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            // 图标跟着格子走：手机 2 列的格子约 160dp、折叠屏约 190dp、平板近 240dp，
            // 写死 66dp 会在宽屏上显得缩在角落。0.42 是 iOS「文件」图标视图观感上的比例。
            DriveEntryIcon(
                entry = entry,
                size = (maxWidth * 0.42f).coerceIn(52.dp, 88.dp),
            )

            if (!entry.isBrowsable && transfer == null) {
                // 网格里没有行尾位置放下载按钮，做成右下角的小圆钮：
                // 点整块 = 打开，点这个角标 = 存到下载目录，两种意图分开
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(30.dp),
                ) {
                    IconButton(onClick = onSave, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = stringResource(R.string.drive_download),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            if (transfer != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (transfer.progress > 0f) {
                        CircularProgressIndicator(
                            progress = { transfer.progress },
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val meta = driveMeta(entry)
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 列表与网格共用的一行元信息：文件夹说项数，文件说大小，末尾都跟日期 */
@Composable
private fun driveMeta(entry: DriveEntry): String = buildList {
    if (entry.isBrowsable) {
        entry.childCount?.let { add(stringResource(R.string.drive_item_count, it)) }
    } else {
        formatBytes(entry.sizeBytes).takeIf { it.isNotEmpty() }?.let { add(it) }
    }
    formatIsoDate(entry.modifiedAt).takeIf { it.isNotEmpty() }?.let { add(it) }
}.joinToString(" · ")
