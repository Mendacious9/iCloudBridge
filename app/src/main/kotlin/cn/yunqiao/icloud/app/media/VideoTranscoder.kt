package cn.yunqiao.icloud.app.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * 把任意能解的编码转成苹果收得下的 H.264 + AAC。
 *
 * 为什么必须自己做：iCloud 的上传接口只保证接受 H.264 / HEVC，遇到 AV1、
 * MPEG-4 Part 2、ProRes 会直接回 `415 uploaded media is unsupported for transcoding`，
 * 而且**没有任何 FFmpeg 依赖**可以直接塞进 APK 而不膨胀几十 MB。
 * 安卓自带 MediaCodec 各机型都有 H.264 编码器，用它做「解码 → 编码」最省事。
 *
 * 实现要点（都是踩过的坑）：
 * - 视频走 **Surface 直通**：解码器输出接到编码器输入 Surface，像素不出 GPU，
 *   比 ByteBuffer 路径快一个数量级，也不用处理各家的颜色格式差异；
 * - 长边限制在 1920，等比缩放并**强制偶数**——H.264 的 4:2:0 要求宽高必须是偶数，
 *   差 1 像素编码器就直接 configure 失败；
 * - 音频先转完**落临时文件**再写进 muxer：MediaMuxer 必须在 start() 前知道所有轨道，
 *   而视频编码的输出格式要等第一帧才拿得到，两边没法同时在线；
 * - 音频不需要转码时（源就是 AAC）直接搬压缩帧，省一次解码+编码；
 * - 音轨处理失败**不拖垮整条视频**：宁可出一个没声音的视频，也比整段传不上去强。
 */
object VideoTranscoder {

    class TranscodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val AUDIO_BITRATE = 128_000

    private fun log(msg: String) = println("[ICloudTranscode] $msg")

    /**
     * @param maxLongSide 长边上限（像素），默认 1080p
     * @param onProgress 0f..1f
     * @return 实际产出文件的画像（供 UI 显示「已转成 H.264」）
     */
    fun transcode(
        src: File,
        dst: File,
        maxLongSide: Int = 1920,
        onProgress: (Float) -> Unit = {},
    ): VideoProfile {
        val profile = MediaInspector.inspect(src)
            ?: throw TranscodeException("这个文件里没有视频轨道")

        if (dst.exists()) dst.delete()
        val tmpAudio = File(dst.absolutePath + ".audio.tmp")

        var muxer: MediaMuxer? = null
        var videoEncoder: MediaCodec? = null
        var videoDecoder: MediaCodec? = null
        var surface: android.view.Surface? = null

        try {
            val (outW, outH) = targetSize(profile, maxLongSide)

            // ---------- ① 音频：先转完落到临时文件 ----------
            val audio = runCatching { spoolAudio(src, tmpAudio) { p -> onProgress(p * 0.12f) } }
                .onFailure { log("音轨处理失败，将输出无音轨视频：${it.message}") }
                .getOrNull()

            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (profile.rotation != 0) {
                runCatching { muxer.setOrientationHint(profile.rotation) }
            }
            val audioTrack = audio?.let { muxer.addTrack(it.format) } ?: -1
            if (audio != null) {
                log("音频 spool 就绪：${audio.pts.size} 帧，${tmpAudio.length()} 字节，track=$audioTrack")
            } else {
                log("没有音轨可写")
            }
            // 音频帧按时间戳与视频交错写入：MediaMuxer 虽然不强制，但「先整段视频
            // 再整段音频」的 mp4 在部分播放器里会先放完画面才出声。
            val audioWriter = audio?.let { AudioMuxer(it, audioTrack) }

            // ---------- ② 视频：解码 → Surface → 编码 ----------
            val extractor = MediaExtractor()
            extractor.setDataSource(src.absolutePath)
            val vTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    .orEmpty().startsWith("video/")
            } ?: throw TranscodeException("找不到视频轨道")
            extractor.selectTrack(vTrack)
            val srcFormat = extractor.getTrackFormat(vTrack)

            val decoderMime = srcFormat.getString(MediaFormat.KEY_MIME)
                ?: throw TranscodeException("视频轨道缺少编码类型")
            videoDecoder = MediaCodec.createDecoderByType(decoderMime)

            val encFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH,
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(outW, outH))
                setInteger(MediaFormat.KEY_FRAME_RATE, profile.frameRate.coerceIn(1f, 60f).roundToInt())
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = videoEncoder.createInputSurface()

            videoDecoder.configure(srcFormat, surface, null, 0)
            videoDecoder.start()
            videoEncoder.start()

            var videoTrackIndex = -1
            var muxerStarted = false
            var inputDone = false
            var decoderDone = false
            var encoderDone = false
            val info = MediaCodec.BufferInfo()
            val durationUs = profile.durationMs * 1000L
            var frames = 0L
            var guard = 0

            while (!encoderDone) {
                // —— 喂解码器 ——
                if (!inputDone) {
                    val inIdx = videoDecoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buffer = videoDecoder.getInputBuffer(inIdx)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            videoDecoder.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            videoDecoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // —— 收解码器输出（Surface 模式下只要 release(true) 就会把帧推给编码器）——
                if (!decoderDone) {
                    var status = videoDecoder.dequeueOutputBuffer(info, 0)
                    while (status >= 0) {
                        // 关键：Surface 直通模式下 decoded buffer 的 size 恒为 0，
                        // 不能拿 info.size 判断要不要渲染 —— 那会导致一帧都进不了编码器。
                        // EOS 那帧也照常 render，无害（此时已无实际像素可送）。
                        videoDecoder.releaseOutputBuffer(status, true)
                        frames++
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderDone = true
                            // 解码结束要显式告诉编码器「没有更多帧了」，否则尾巴永远不出
                            runCatching { videoEncoder.signalEndOfInputStream() }
                            break
                        }
                        status = videoDecoder.dequeueOutputBuffer(info, 0)
                    }
                }

                // —— 收编码器输出并写 muxer ——
                var status = videoEncoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                while (status != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    when {
                        status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                videoTrackIndex = muxer.addTrack(videoEncoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }

                        status >= 0 -> {
                            val buffer = videoEncoder.getOutputBuffer(status)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                info.size = 0
                            }
                            if (buffer != null && info.size > 0 && muxerStarted) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(videoTrackIndex, buffer, info)
                                audioWriter?.writeUpTo(muxer, info.presentationTimeUs)
                            }
                            videoEncoder.releaseOutputBuffer(status, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoderDone = true
                                break
                            }
                        }
                    }
                    if (encoderDone) break
                    status = videoEncoder.dequeueOutputBuffer(info, 0)
                }

                // 进度：按「已推给编码器的帧数 / 预期总帧数」估算
                if (durationUs > 0 && !encoderDone) {
                    val totalFrames = (profile.durationMs / 1000f * profile.frameRate).coerceAtLeast(1f)
                    onProgress(0.12f + 0.78f * (frames / totalFrames).coerceIn(0f, 1f))
                }

                // 死循环保护：编码器卡住时不能把 UI 冻在这里
                if (++guard > 2_000_000) throw TranscodeException("视频转码超时")
            }

            extractor.release()

            // ---------- ③ 把剩下的音频补齐 ----------
            if (muxerStarted) {
                runCatching { audioWriter?.drain(muxer) }
            }
            onProgress(1f)

            // 必须先显式收尾再读产物：mp4 的 moov box（索引）是 stop() 时才写盘的，
            // 在 finally 里 stop 意味着这里读到的永远是个没有索引的残文件。
            if (!muxerStarted) throw TranscodeException("没有编码出任何视频帧")
            val finished = muxer ?: throw TranscodeException("封装器未创建")
            runCatching { finished.stop() }
                .getOrElse { throw TranscodeException("封装 mp4 失败：${it.message}", it) }
            runCatching { finished.release() }
            muxer = null

            return MediaInspector.inspect(dst)
                ?: throw TranscodeException("转码产物无法读取")
        } catch (e: TranscodeException) {
            throw e
        } catch (e: Throwable) {
            throw TranscodeException("视频转码失败：${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            // 顺序很重要：先停编码再停解码，否则个别机型会拿不到最后几帧
            runCatching { videoEncoder?.stop() }
            runCatching { videoEncoder?.release() }
            runCatching { videoDecoder?.stop() }
            runCatching { videoDecoder?.release() }
            runCatching { surface?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { tmpAudio.delete() }
        }
    }

    // ------------------------------------------------------------------ 音频

    private class AudioSpool(
        val format: MediaFormat,
        val file: File,
    ) {
        /** 与落盘顺序一一对应的时间戳与长度，用于按时间交错写入 muxer */
        val pts = ArrayList<Long>(1024)
        val sizes = ArrayList<Int>(1024)
    }

    /**
     * 把音轨处理成 AAC 并落盘。
     *
     * 源本来就是 AAC 时直接搬压缩帧（无损、零 CPU）；否则解码成 PCM 再用
     * AAC-LC 重新编码。返回 null 表示没有音轨。
     */
    private fun spoolAudio(src: File, tmp: File, onProgress: (Float) -> Unit): AudioSpool? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var out: DataOutputStream? = null
        try {
            extractor.setDataSource(src.absolutePath)
            val aTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    .orEmpty().startsWith("audio/")
            } ?: return null
            extractor.selectTrack(aTrack)
            val srcFormat = extractor.getTrackFormat(aTrack)
            val mime = srcFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            val durationUs = runCatching { srcFormat.getLong(MediaFormat.KEY_DURATION) }
                .getOrDefault(0L)

            out = DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16))
            val ptsList = ArrayList<Long>(1024)
            val sizeList = ArrayList<Int>(1024)

            // —— 情况 A：源本身就是 AAC，直接搬 ——
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                val buf = java.nio.ByteBuffer.allocateDirect(1 shl 20)
                while (true) {
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) break
                    writeSpool(out, extractor.sampleTime, buf, size)
                    ptsList.add(extractor.sampleTime)
                    sizeList.add(size)
                    extractor.advance()
                    if (durationUs > 0) {
                        onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                }
                return AudioSpool(srcFormat, tmp).also {
                    it.pts.addAll(ptsList)
                    it.sizes.addAll(sizeList)
                }
            }

            // —— 情况 B：解成 PCM 再用 AAC-LC 编 ——
            val channels = runCatching { srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrDefault(2).coerceIn(1, 2)
            val sampleRate = runCatching { srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                .getOrDefault(44_100)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(srcFormat, null, null, 0)
            decoder.start()

            val encFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels,
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            var outFormat: MediaFormat? = null
            var inputDone = false
            var encDone = false
            val info = MediaCodec.BufferInfo()
            var guard = 0

            while (!encDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buffer = decoder.getInputBuffer(inIdx)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var decStatus = decoder.dequeueOutputBuffer(info, 0)
                while (decStatus >= 0) {
                    val pcm = decoder.getOutputBuffer(decStatus)
                    if (info.size > 0 && pcm != null) {
                        // PCM 直接喂给编码器：先推进输入缓冲，再把数据拷过去
                        val encIn = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (encIn >= 0) {
                            val encBuf = encoder.getInputBuffer(encIn)
                            if (encBuf != null) {
                                pcm.position(info.offset)
                                pcm.limit(info.offset + info.size)
                                encBuf.clear()
                                encBuf.put(pcm)
                                encoder.queueInputBuffer(encIn, 0, info.size, info.presentationTimeUs, 0)
                            }
                        }
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(decStatus, false)
                    if (eos) {
                        // 给编码器补一个空帧告诉它结束了
                        val encIn = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (encIn >= 0) {
                            encoder.queueInputBuffer(encIn, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                        break
                    }
                    if (durationUs > 0) {
                        onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                    decStatus = decoder.dequeueOutputBuffer(info, 0)
                }

                var encStatus = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                while (encStatus != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    when {
                        encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                            outFormat = encoder.outputFormat

                        encStatus >= 0 -> {
                            val outBuf = encoder.getOutputBuffer(encStatus)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                info.size = 0
                            }
                            if (outBuf != null && info.size > 0) {
                                writeSpool(out, info.presentationTimeUs, outBuf, info.size, info.offset)
                                ptsList.add(info.presentationTimeUs)
                                sizeList.add(info.size)
                            }
                            encoder.releaseOutputBuffer(encStatus, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encDone = true
                                break
                            }
                        }
                    }
                    if (encDone) break
                    encStatus = encoder.dequeueOutputBuffer(info, 0)
                }

                if (++guard > 2_000_000) throw TranscodeException("音轨转码超时")
            }

            val fmt = outFormat ?: encFormat
            return AudioSpool(fmt, tmp).also {
                it.pts.addAll(ptsList)
                it.sizes.addAll(sizeList)
            }
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { out?.flush() }
            runCatching { out?.close() }
            runCatching { extractor.release() }
        }
    }

    /** 落盘格式：[pts:8][size:4][data:size]，顺序即写入顺序 */
    private fun writeSpool(
        out: DataOutputStream,
        pts: Long,
        buffer: java.nio.ByteBuffer,
        size: Int,
        offset: Int = 0,
    ) {
        out.writeLong(pts)
        out.writeInt(size)
        val dup = buffer.duplicate()
        dup.position(offset)
        dup.limit(offset + size)
        val bytes = ByteArray(size)
        dup.get(bytes)
        out.write(bytes)
    }

    /**
     * 按时间戳把落盘的音频帧逐个喂给 muxer。
     *
     * 由视频编码循环驱动：每写完一帧视频就调用一次 [writeUpTo]，把「时间戳已经
     * 追上来」的音频帧写出去，于是 mp4 里音视频是交错的。
     */
    private class AudioMuxer(private val spool: AudioSpool, private val track: Int) {
        private val input = java.io.DataInputStream(
            java.io.BufferedInputStream(java.io.FileInputStream(spool.file), 1 shl 16),
        )
        private var cursor = 0
        var written = 0
            private set
        private val info = MediaCodec.BufferInfo()

        fun writeUpTo(muxer: MediaMuxer, ptsUs: Long) {
            if (track < 0) return
            while (cursor < spool.pts.size && spool.pts[cursor] <= ptsUs) {
                val pts = spool.pts[cursor]
                val size = spool.sizes[cursor]
                if (size <= 0 || size > (1 shl 22)) {
                    // 长度异常时没法跳过对应字节，硬跳会让后面所有帧整体错位，
                    // 宁可丢剩下的音轨也不产出结构损坏的 mp4
                    cursor = spool.pts.size
                    break
                }
                val bytes = ByteArray(size)
                if (runCatching { input.readFully(bytes) }.isFailure) {
                    cursor = spool.pts.size
                    break
                }
                info.set(0, size, pts, 0)
                runCatching { muxer.writeSampleData(track, java.nio.ByteBuffer.wrap(bytes), info) }
                    .onFailure { log("写入音频帧失败（pts=$pts size=$size）：${it.message}") }
                written++
                cursor++
            }
        }

        fun drain(muxer: MediaMuxer) {
            writeUpTo(muxer, Long.MAX_VALUE)
            runCatching { input.close() }
            log("音频共写入 $written / ${spool.pts.size} 帧")
        }
    }

    // ------------------------------------------------------------------ 参数

    /** 等比缩到长边不超过 [maxLongSide]，并保证宽高都是偶数 */
    private fun targetSize(profile: VideoProfile, maxLongSide: Int): Pair<Int, Int> {
        var w = profile.width
        var h = profile.height
        if (w <= 0 || h <= 0) {
            w = 1280
            h = 720
        }
        val long = maxOf(w, h)
        if (long > maxLongSide) {
            val k = maxLongSide.toFloat() / long
            w = (w * k).roundToInt()
            h = (h * k).roundToInt()
        }
        // H.264 4:2:0 要求偶数，奇数会 configure 失败
        if (w % 2 != 0) w--
        if (h % 2 != 0) h--
        return w.coerceAtLeast(2) to h.coerceAtLeast(2)
    }

    private fun bitrateFor(w: Int, h: Int): Int {
        val pixels = w.toLong() * h
        return when {
            pixels >= 1920L * 1080 -> 8_000_000
            pixels >= 1280L * 720 -> 5_000_000
            pixels >= 854L * 480 -> 3_000_000
            else -> 1_500_000
        }
    }
}
