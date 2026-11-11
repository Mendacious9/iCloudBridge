package cn.yunqiao.icloud.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cn.yunqiao.icloud.http.ICloudHttp
import cn.yunqiao.icloud.model.AssetSize
import cn.yunqiao.icloud.model.PhotoAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 图片与视频的本地缓存层。
 *
 * iCloud 的下载地址是带会话的私有接口，第三方图片库要么拿不到 Cookie、要么要额外配拦截器，
 * 所以这里直接复用 [ICloudHttp] 的 CookieJar 取字节，自己解码 + 缓存。
 *
 * 视频有个坑：`resVidSmall` 是小视频（mp4），不是图片。缩略图必须从视频里抽帧，
 * 除非苹果在记录里给了 `resJPEGThumbRes` 封面。
 */
object MediaCache {

    private var imageCacheDir: File? = null
    private var videoCacheDir: File? = null

    private val bitmaps: LruCache<String, Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
        object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    fun init(context: Context) {
        if (imageCacheDir == null) {
            imageCacheDir = File(context.cacheDir, "thumbs").also { it.mkdirs() }
        }
        if (videoCacheDir == null) {
            videoCacheDir = File(context.cacheDir, "videos").also { it.mkdirs() }
        }
    }

    fun cachedImage(url: String): Bitmap? = bitmaps.get(url)

    // ---------------------------------------------------------------- 图片

    /**
     * 原始字节缓存（受 1/16 堆上限约束）。
     *
     * 存在的唯一理由是「详情页要 EXIF」：查看器先显示图片，再上滑看信息，
     * 两步用的是同一份 JPEG。没有这层缓存就得把同一张图下两遍 —— 在 iCloud 上
     * 一次就是几 MB，还会让信息面板转圈等第二次下载。
     */
    private val byteCache: LruCache<String, ByteArray> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt()
        object : LruCache<String, ByteArray>(maxKb) {
            override fun sizeOf(key: String, value: ByteArray): Int = value.size / 1024 + 1
        }
    }

    /** 取图片字节；命中缓存直接返回，不重复下载 */
    suspend fun loadBytes(http: ICloudHttp, url: String): ByteArray? = withContext(Dispatchers.IO) {
        byteCache.get(url)?.let { return@withContext it }
        val bytes = downloadBytes(http, url) ?: return@withContext null
        if (bytes.isEmpty()) return@withContext null
        byteCache.put(url, bytes)
        bytes
    }

    suspend fun loadImage(http: ICloudHttp, url: String): Bitmap? = withContext(Dispatchers.IO) {
        bitmaps.get(url)?.let { return@withContext it }
        val bytes = loadBytes(http, url) ?: return@withContext null
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            .getOrNull() ?: return@withContext null
        bitmaps.put(url, bitmap)
        bitmap
    }

    // ---------------------------------------------------------------- 视频封面

    /**
     * 取视频封面：优先用苹果给的静态封面，否则下载小视频抽第一帧。
     * 抽出来的帧会进 LruCache，同一张不会重复解码。
     */
    suspend fun loadVideoPoster(http: ICloudHttp, asset: PhotoAsset): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "poster:${asset.recordName}"
            bitmaps.get(key)?.let { return@withContext it }

            asset.posterUrl?.let { poster ->
                return@withContext loadImage(http, poster)?.also { bitmaps.put(key, it) }
            }

            // 没有封面 → 拿小视频，抽第一帧
            val small = asset.versions[AssetSize.THUMB]?.downloadUrl ?: return@withContext null
            val file = ensureVideoFile(http, small, "small_${asset.recordName}", "mp4", null)
                ?: return@withContext null

            val bitmap = extractFrame(file)
            bitmap?.let { bitmaps.put(key, it) }
            bitmap
        }

    private fun extractFrame(file: File): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ---------------------------------------------------------------- 视频文件

    /** 把视频下载到缓存目录（已有就直接复用），返回本地文件 */
    suspend fun ensureVideoFile(
        http: ICloudHttp,
        url: String,
        key: String,
        extension: String,
        onProgress: ((Float) -> Unit)?,
    ): File? = withContext(Dispatchers.IO) {
        val dir = videoCacheDir ?: return@withContext null
        val file = File(dir, "${key.hashCode().toUInt()}.$extension")
        if (file.exists() && file.length() > 0) {
            onProgress?.invoke(1f)
            return@withContext file
        }

        val temp = File(dir, "${file.name}.part")
        var total = -1L
        var read = 0L
        try {
            http.stream(url) { response ->
                total = response.body?.contentLength() ?: -1L
                response.body?.byteStream()?.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                onProgress?.invoke((read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { temp.delete() }
            throw e
        }
        if (!temp.exists() || temp.length() == 0L) return@withContext null
        if (!temp.renameTo(file)) {
            runCatching { temp.delete() }
            return@withContext null
        }
        onProgress?.invoke(1f)
        file
    }

    private fun downloadBytes(http: ICloudHttp, url: String): ByteArray? {
        var bytes: ByteArray? = null
        runCatching {
            http.stream(url) { response -> bytes = response.body?.bytes() }
        }
        return bytes
    }
}

// ---------------------------------------------------------------------- 组件

@Composable
fun RemoteImage(
    url: String?,
    http: ICloudHttp?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var bitmap by remember(url) { mutableStateOf(url?.let(MediaCache::cachedImage)) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, http) {
        if (bitmap != null) return@LaunchedEffect
        if (url == null || http == null) {
            failed = true
            return@LaunchedEffect
        }
        val loaded = MediaCache.loadImage(http, url)
        bitmap = loaded
        failed = loaded == null
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )

            failed -> Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )

            else -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 视频缩略图：从视频里抽第一帧。
 * 苹果若给了 `resJPEGThumbRes` 就走普通图片链路，否则下载小视频抽帧（一次，之后走缓存）。
 */
@Composable
fun VideoThumb(
    asset: PhotoAsset,
    http: ICloudHttp?,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(asset.recordName) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(asset.recordName) { mutableStateOf(false) }

    LaunchedEffect(asset.recordName, http) {
        if (http == null) {
            failed = true
            return@LaunchedEffect
        }
        val frame = MediaCache.loadVideoPoster(http, asset)
        bitmap = frame
        failed = frame == null
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = asset.filename,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            failed -> Icon(
                imageVector = Icons.Outlined.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )

            else -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
