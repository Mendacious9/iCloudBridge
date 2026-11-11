package cn.yunqiao.icloud.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.media.ExifInterface
import cn.yunqiao.icloud.model.PhotoAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 查看器「上滑」后展示的元数据。
 *
 * 全部字段都可空：EXIF 是相机写的，不是每台设备都写全 —— 截图没有焦距，
 * 微信转发过的图连拍摄时间都可能被抹掉。缺项在界面上直接不显示，
 * 不要用「未知」把面板填满。
 */
data class PhotoDetails(
    val width: Int? = null,
    val height: Int? = null,
    val takenAt: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** 反查出来的地名；国行设备没有 Google 服务时通常为 null，退回显示坐标 */
    val placeName: String? = null,
    val camera: String? = null,
    val lens: String? = null,
    val focalLength: String? = null,
    val aperture: String? = null,
    val shutter: String? = null,
    val iso: String? = null,
    val fileSize: Long? = null,
    val durationSeconds: Double? = null,
)

private val detailsCache = HashMap<String, PhotoDetails>()

/** 地名反查结果单独缓存：一次反查要几百毫秒，来回滑动不该重复查 */
private val placeCache = HashMap<String, String>()

/**
 * 解析一张照片的详情。
 *
 * @param bytes 该图的中等分辨率 JPEG（[MediaCache.loadBytes] 已缓存，不会重复下载）。
 *              用中图而不是原图：HEIC 原图在部分 ROM 上 `ExifInterface` 读不出 EXIF，
 *              而中图是苹果转码的标准 JPEG，字段最稳。
 */
suspend fun readPhotoDetails(
    context: Context,
    asset: PhotoAsset,
    bytes: ByteArray?,
): PhotoDetails = withContext(Dispatchers.IO) {
    detailsCache[asset.recordName]?.let { return@withContext it }

    // 像素尺寸：优先用记录里的 resOriginalWidth/Height（就是原图尺寸），
    // EXIF / 解码边界只作为兜底 —— 它们拿到的是中图尺寸，和「文件大小」对不上
    var width: Int? = asset.originalWidth
    var height: Int? = asset.originalHeight
    var camera: String? = null
    var lens: String? = null
    var focal: String? = null
    var aperture: String? = null
    var shutter: String? = null
    var iso: String? = null

    if (bytes != null) {
        if (width == null || height == null) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                width = opts.outWidth
                height = opts.outHeight
            }
        }

        runCatching {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            // 只有记录里没给尺寸时才退回 EXIF
            if (width == null || height == null) {
                val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                if (w > 0 && h > 0) {
                    width = w
                    height = h
                }
            }

            camera = listOfNotNull(
                exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() },
                exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
            ).distinct().joinToString(" ").takeIf { it.isNotEmpty() }

            // LensModel 不在系统 ExifInterface 的常量里，但 getAttribute 接受任意 tag 名
            lens = exif.getAttribute("LensModel")?.trim()?.takeIf { it.isNotEmpty() }

            exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                .takeIf { it > 0 }
                ?.let { focal = String.format(Locale.US, "%.1f mm", it) }

            exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
                .takeIf { it > 0 }
                ?.let { aperture = "f/" + trimNumber(it) }

            exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                .takeIf { it > 0 }
                ?.let { exposure ->
                    // 单反/相机对长曝光写 "2.5"，手机对短曝光写 "0.0083"；
                    // 统一成 1/xxx 才符合「快门速度」的阅读习惯
                    shutter = if (exposure >= 1.0) {
                        String.format(Locale.US, "%.1f s", exposure)
                    } else {
                        "1/${(1.0 / exposure).toInt()} s"
                    }
                }

            val isoValue = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)
                .takeIf { it > 0 }
                ?: exif.getAttributeInt("PhotographicSensitivity", 0).takeIf { it > 0 }
            isoValue?.let { iso = "ISO $it" }
        }
    }

    val lat = asset.latitude
    val lon = asset.longitude

    val details = PhotoDetails(
        width = width,
        height = height,
        takenAt = asset.assetDateMillis,
        latitude = lat,
        longitude = lon,
        placeName = null,
        camera = camera,
        lens = lens,
        focalLength = focal,
        aperture = aperture,
        shutter = shutter,
        iso = iso,
        fileSize = asset.bestVersion?.sizeBytes,
        durationSeconds = asset.durationSeconds,
    )
    detailsCache[asset.recordName] = details
    details
}

/** 经纬度 → 地名。失败返回 null（国行机常常没有可用的地理编码服务）。
 *
 * 必须由调用方套 `withTimeoutOrNull`：Geocoder 是同步阻塞调用，不接受协程取消，
 * 在没有 Google 服务的设备上可能卡十几秒。超时后界面先显示坐标，后台线程跑完即弃。
 *
 * 只用 `getFromLocation` 的第一个结果，且刻意只要「城市 + 区」这一级：
 * 精确到街道对相册没有意义，反而暴露隐私。
 */
suspend fun lookupPlaceName(context: Context, lat: Double, lon: Double): String? =
    withContext(Dispatchers.IO) {
        val key = String.format(Locale.US, "%.3f,%.3f", lat, lon)
        placeCache[key]?.let { return@withContext it }
        runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(lat, lon, 1)
            val address = list?.firstOrNull() ?: return@runCatching null
            val parts = listOfNotNull(
                address.locality ?: address.subAdminArea,
                address.subLocality ?: address.thoroughfare,
                address.countryName,
            ).distinct()
            parts.joinToString(" · ").takeIf { it.isNotBlank() }
        }.getOrNull()?.also { placeCache[key] = it }
    }

/** 1.0 → "1"，1.78 → "1.8"，别让光圈显示成 f/1.7800000000000002 */
private fun trimNumber(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString()
    else String.format(Locale.US, "%.1f", rounded)
}

/** 拍摄时间按本地时区展示，形如 2026年8月12日 18:20 */
fun formatTakenAt(millis: Long, pattern: String = "yyyy-MM-dd HH:mm"): String =
    runCatching {
        DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }.getOrDefault("")

/** 坐标兜底文案，保留 4 位小数（约 10 米），精度够定位又不至于泄露门牌 */
fun formatCoordinate(lat: Double, lon: Double): String =
    String.format(Locale.US, "%.4f, %.4f", lat, lon)
