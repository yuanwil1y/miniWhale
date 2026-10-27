package whaleui.nightsky.ltd.voice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 语音条波形：把解码后的 PCM 降采样成若干根柱子（0f..1f）。
 *
 * [fromPcm] 和 [flat] 是纯函数，JVM 单测覆盖；
 * [fromMp3] 需要真实的 `MediaExtractor` / `MediaCodec` 解码器（真机 / 模拟器），**不做单测**，
 * 所以它内部把任何异常都吃掉，退回 [flat] 的平直波形——界面上宁可显示一条平线，也不能因为波形崩掉。
 */
object Waveform {

    /** 默认柱数。任务书 7.2 要求"若干柱"，这里固定 64 根，界面正好铺满一条语音条。 */
    const val DEFAULT_BUCKETS: Int = 64

    /** 最矮的柱子：太低会在界面上看不见，所以给一个下限。 */
    private const val MIN_BAR: Float = 0.06f

    /** 最高的柱子：不顶到 1f，留一点上下边距。 */
    private const val MAX_BAR: Float = 0.96f

    /** 最多解码 10 分钟（32kHz），防止超长音频把内存吃穿；超出部分直接截断。 */
    private const val MAX_PCM_SAMPLES: Int = 32_000 * 600

    /** 解码时 dequeue 的超时（微秒），避免死循环。 */
    private const val DECODE_TIMEOUT_US: Long = 10_000L

    /**
     * 失败 / 静音时的兜底波形：固定 [buckets] 根、高度都是 [MIN_BAR]。
     * [buckets] <= 0 时返回空列表。
     */
    fun flat(buckets: Int = DEFAULT_BUCKETS): List<Float> {
        if (buckets <= 0) return emptyList()
        return List(buckets) { MIN_BAR }
    }

    /**
     * 把 16-bit PCM 按桶取 RMS，再归一化到 [MIN_BAR]..[MAX_BAR]。
     *
     * - 空输入 / 全静音 → [flat]；
     * - 非静音时最响的那根柱子一定是 [MAX_BAR]，其余按响度等比缩放（但不会低于 [MIN_BAR]）。
     */
    fun fromPcm(pcm: ShortArray, buckets: Int = DEFAULT_BUCKETS): List<Float> {
        if (buckets <= 0) return emptyList()
        if (pcm.isEmpty()) return flat(buckets)

        val rms = FloatArray(buckets)
        for (bucket in 0 until buckets) {
            val start = (bucket.toLong() * pcm.size / buckets).toInt()
            val end = ((bucket + 1).toLong() * pcm.size / buckets).toInt().coerceAtMost(pcm.size)
            if (end <= start) continue
            var sum = 0.0
            for (index in start until end) {
                val value = pcm[index] / 32768.0
                sum += value * value
            }
            rms[bucket] = sqrt(sum / (end - start)).toFloat()
        }

        val peak = rms.max()
        if (peak <= 0f) return flat(buckets)
        return rms.map { value ->
            (MIN_BAR + (value / peak) * (MAX_BAR - MIN_BAR)).coerceIn(MIN_BAR, MAX_BAR)
        }
    }

    /**
     * 解码一个 mp3 文件并生成波形。
     *
     * 注意：**这个方法不做 JVM 单测**，因为它依赖 Android 的 [MediaExtractor] / [MediaCodec]（需要真机解码器）。
     * 任何异常（文件不存在、没有音轨、解码失败……）都返回 [flat]，绝不抛出。
     */
    fun fromMp3(file: File, buckets: Int = DEFAULT_BUCKETS): List<Float> = try {
        val pcm = decodePcm(file)
        if (pcm == null || pcm.isEmpty()) flat(buckets) else fromPcm(pcm, buckets)
    } catch (e: Exception) {
        flat(buckets)
    }

    // ------------------------------------------------------------ 解码实现

    /** @return 解码出的 16-bit PCM；文件没有音频轨或解码失败时返回 null。 */
    private fun decodePcm(file: File): ShortArray? {
        if (!file.isFile || file.length() <= 0L) return null

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    audioFormat = format
                    break
                }
            }
            if (audioTrack < 0 || audioFormat == null) return null
            extractor.selectTrack(audioTrack)

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(audioFormat, null, null, 0)
                codec.start()
                return readPcm(codec, extractor)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /** 标准 MediaCodec 解码循环：喂压缩数据，收集 16-bit little-endian PCM 输出。 */
    private fun readPcm(codec: MediaCodec, extractor: MediaExtractor): ShortArray {
        var pcm = ShortArray(64 * 1024)
        var size = 0
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(DECODE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer == null) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DECODE_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                outputIndex >= 0 -> {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val samples = outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                            while (samples.hasRemaining() && size < MAX_PCM_SAMPLES) {
                                if (size == pcm.size) pcm = pcm.copyOf(pcm.size * 2)
                                pcm[size] = samples.get()
                                size++
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    if (size >= MAX_PCM_SAMPLES) outputDone = true
                }
                // INFO_TRY_AGAIN_LATER：继续等下一轮
                else -> Unit
            }
        }
        return pcm.copyOf(size)
    }
}
