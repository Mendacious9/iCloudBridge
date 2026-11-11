package cn.yunqiao.icloud.app.media

import android.graphics.Bitmap
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * 转码链路必须在设备上验证：MediaCodec 是厂商实现，纯 JVM 单测覆盖不到。
 *
 * 这里用一段 MPEG-4 Part 2 的真实素材走完整链路 ——
 * 它是实测会被 iCloud 回 415 的编码之一，正好当「必须转码」的样本。
 */
@RunWith(AndroidJUnit4::class)
class VideoTranscoderTest {

    /**
     * 注意用 `context` 而不是 `targetContext`：androidTest/assets 是打进**测试 APK** 的，
     * 只有测试进程自己的 Context 才看得到；targetContext 指向被测应用，那里没有这个文件。
     */
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun copyAsset(name: String): File {
        val out = File(ctx.cacheDir, name)
        testCtx.assets.open(name).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    @Test
    fun mpeg4SourceIsRejectedByApple() {
        val src = copyAsset("mpeg4-720p.mp4")
        val profile = MediaInspector.inspect(src)
        assertNotNull("源文件应能读出视频轨道", profile)
        assertEquals(
            "源应为 MPEG-4 Part 2",
            MediaFormat.MIMETYPE_VIDEO_MPEG4,
            profile!!.videoMime,
        )
        assertFalse(
            "MPEG-4 Part 2 不应被苹果直接接受（实测回 415）",
            MediaInspector.appleAccepts(profile.videoMime),
        )
    }

    @Test
    fun transcodeMpeg4ToH264() {
        val src = copyAsset("mpeg4-720p.mp4")
        val before = MediaInspector.inspect(src)
        assertNotNull(before)

        val dst = File(ctx.cacheDir, "out-h264.mp4")
        val progress = ArrayList<Float>()
        val after = VideoTranscoder.transcode(src, dst) { progress.add(it) }

        assertTrue("输出文件应存在且非空（${dst.length()} 字节）", dst.length() > 20_000)
        assertEquals("转码后应为 H.264", MediaFormat.MIMETYPE_VIDEO_AVC, after.videoMime)
        assertTrue("转码后应被苹果接受", MediaInspector.appleAccepts(after.videoMime))
        assertEquals("分辨率应保持不变", 1280 to 720, after.width to after.height)

        assertTrue(
            "时长应接近源（源 ${before!!.durationMs}ms，输出 ${after.durationMs}ms）",
            abs(after.durationMs - before.durationMs) < 600,
        )

        assertEquals("音轨应保留且转成 AAC", MediaFormat.MIMETYPE_AUDIO_AAC, after.audioMime)
        assertTrue("进度应推进到接近 1（实际 ${progress.lastOrNull()}）",
            (progress.lastOrNull() ?: 0f) > 0.99f)

        assertFrameIsVisible(dst)
        assertAudioTrackComplete(dst, before.durationMs)
    }

    /** 输出不能是黑屏：解一帧出来看颜色种类 */
    private fun assertFrameIsVisible(file: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.getFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
            assertNotNull("应能从输出里解出一帧画面", frame)
            val colors = HashSet<Int>()
            var y = 0
            while (y < frame!!.height) {
                var x = 0
                while (x < frame.width) {
                    colors.add(frame.getPixel(x, y))
                    if (colors.size > 16) return
                    x += 24
                }
                y += 24
            }
            fail("输出画面只有 ${colors.size} 种颜色，疑似黑屏或空帧")
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 音轨不能只剩个空壳。
     *
     * 音频是先落临时文件、再按时间戳穿插回 muxer 的，这条路径出错时最典型的
     * 表现就是「轨道在、帧数远少于应有数量」。这里按 AAC 帧长核对一遍：
     * 44.1kHz 下一帧 1024 个采样，1 秒约 43 帧。
     */
    private fun assertAudioTrackComplete(file: File, expectedDurationMs: Long) {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var aIdx = -1
            var sampleRate = 44_100
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    aIdx = i
                    sampleRate = runCatching {
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }.getOrDefault(44_100)
                    break
                }
            }
            assertTrue("输出应含音频轨", aIdx >= 0)

            // 用 readSampleData 判 EOS，而不是读 sampleTime ——
            // 刚 setDataSource 完没 seek 时 sampleTime 不可靠，个别实现直接给 -1
            extractor.selectTrack(aIdx)
            extractor.seekTo(0, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val buf = java.nio.ByteBuffer.allocate(64 * 1024)
            var frames = 0
            while (extractor.readSampleData(buf, 0) >= 0) {
                frames++
                if (!extractor.advance()) break
            }

            val expected = (expectedDurationMs / 1000.0 * sampleRate / 1024.0).toInt()
            val ratio = frames.toDouble() / expected
            assertTrue(
                "音轨帧数异常：实际 $frames 帧，预期约 $expected 帧（${sampleRate}Hz）",
                ratio in 0.75..1.25,
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    @Test
    fun appleAcceptPolicy() {
        assertTrue(MediaInspector.appleAccepts(MediaFormat.MIMETYPE_VIDEO_AVC))
        assertTrue(MediaInspector.appleAccepts(MediaFormat.MIMETYPE_VIDEO_HEVC))
        assertFalse(MediaInspector.appleAccepts(MediaFormat.MIMETYPE_VIDEO_AV1))
        assertFalse(MediaInspector.appleAccepts(MediaFormat.MIMETYPE_VIDEO_MPEG4))
        assertFalse(MediaInspector.appleAccepts(MediaFormat.MIMETYPE_VIDEO_VP9))
        assertFalse(MediaInspector.appleAccepts(null))
    }
}
