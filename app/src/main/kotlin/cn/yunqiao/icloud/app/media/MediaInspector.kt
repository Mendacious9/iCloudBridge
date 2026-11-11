package cn.yunqiao.icloud.app.media

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * 一段视频的可上传性画像。
 *
 * 只保留「决定苹果收不收」以及「转码要不要」的字段，别的都是噪声。
 */
data class VideoProfile(
    /** 视频轨道编码，MediaCodec 的 MIME，例如 video/avc、video/hevc、video/av01 */
    val videoMime: String?,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationMs: Long,
    val frameRate: Float,
    /** 音轨编码；null = 没有音轨 */
    val audioMime: String?,
    val audioChannels: Int,
    val audioSampleRate: Int,
) {
    val isVideo: Boolean get() = videoMime != null

    /** 展示用：avc1 → H.264，hevc → HEVC，av01 → AV1 */
    val videoCodecLabel: String
        get() = when (videoMime) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264"
            MediaFormat.MIMETYPE_VIDEO_HEVC -> "HEVC"
            MediaFormat.MIMETYPE_VIDEO_AV1 -> "AV1"
            MediaFormat.MIMETYPE_VIDEO_VP9 -> "VP9"
            MediaFormat.MIMETYPE_VIDEO_VP8 -> "VP8"
            MediaFormat.MIMETYPE_VIDEO_MPEG4 -> "MPEG-4"
            null -> "—"
            else -> videoMime.substringAfter('/').uppercase()
        }
}

/**
 * 判断本地文件能不能直接传进 iCloud 图库。
 *
 * 结论来自实测（2026-10，走与 App 完全相同的 photosupload 三步链路）：
 *
 * | 文件                | 结果 |
 * |---------------------|------|
 * | H.264 (avc1) mp4    | ✅ 200 |
 * | HEVC  (hvc1) mp4    | ✅ 200 |
 * | AV1   (av01) mp4    | ❌ 415 uploaded media is unsupported for transcoding |
 * | MPEG-4 Part 2       | ❌ 415 |
 * | ProRes mov          | ❌ 415 |
 *
 * 也就是说**苹果接受视频，但只接受 H.264 / HEVC**。415 不是「不支持视频」，
 * 而是「这个编码我转不动」—— 所以解法是本地转成 H.264，而不是拒绝上传。
 */
object MediaInspector {

    private val APPLE_VIDEO_MIMES = setOf(
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaFormat.MIMETYPE_VIDEO_HEVC,
    )

    fun appleAccepts(videoMime: String?): Boolean = videoMime in APPLE_VIDEO_MIMES

    /** 读取轨道信息；不是视频或读不出来时返回 null */
    fun inspect(file: File): VideoProfile? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var videoMime: String? = null
            var width = 0
            var height = 0
            var rotation = 0
            var durationUs = 0L
            var frameRate = 0f
            var videoTrack = -1
            var audioMime: String? = null
            var channels = 0
            var sampleRate = 0

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") && videoTrack < 0 -> {
                        videoTrack = i
                        videoMime = mime
                        width = format.optInt(MediaFormat.KEY_WIDTH)
                        height = format.optInt(MediaFormat.KEY_HEIGHT)
                        rotation = format.optInt(MediaFormat.KEY_ROTATION)
                        durationUs = format.optLong(MediaFormat.KEY_DURATION)
                        frameRate = format.optFloat(MediaFormat.KEY_FRAME_RATE)
                    }

                    mime.startsWith("audio/") && audioMime == null -> {
                        audioMime = mime
                        channels = format.optInt(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = format.optInt(MediaFormat.KEY_SAMPLE_RATE)
                    }
                }
            }
            if (videoMime == null) return null

            // 有些机型把旋转信息放在 90/180/270 之外的值里，这里只认合法的
            if (rotation !in listOf(0, 90, 180, 270)) rotation = 0

            VideoProfile(
                videoMime = videoMime,
                width = width,
                height = height,
                rotation = rotation,
                durationMs = durationUs / 1000,
                frameRate = if (frameRate > 0f) frameRate else 30f,
                audioMime = audioMime,
                audioChannels = channels,
                audioSampleRate = sampleRate,
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.optInt(key: String): Int =
        runCatching { getInteger(key) }.getOrDefault(0)

    private fun MediaFormat.optLong(key: String): Long =
        runCatching { getLong(key) }.getOrDefault(0L)

    private fun MediaFormat.optFloat(key: String): Float =
        runCatching { getFloat(key) }.getOrDefault(0f)
}
