package whaleui.nightsky.ltd.ui.chat

import kotlin.random.Random
import whaleui.nightsky.ltd.domain.chat.ChatMessage

/**
 * 「一条一条发」的节奏参数（任务书第三点五节）。
 *
 * 目的：她一次回四五条时，不要几个气泡同时蹦出来，要像真人在手机上一条一条打字发送。
 * **只影响界面显示节奏**，落库 / 切片 / 请求全都不变。
 *
 * 所有可调参数集中在这里，以后想调手感只改这一个文件。
 */
object MessagePacing {

    /** 第一条：就绪后立即显示（毫秒）。 */
    const val FIRST_DELAY_MS: Long = 0L

    /** 后续每条的基础延迟。 */
    const val BASE_DELAY_MS: Long = 500L

    /** 每个字增加的延迟。 */
    const val PER_CHAR_MS: Long = 70L

    /** 单条最短等待。 */
    const val MIN_DELAY_MS: Long = 700L

    /** 单条最长等待。 */
    const val MAX_DELAY_MS: Long = 2_800L

    /** 随机抖动比例（±15%）。 */
    const val JITTER_RATIO: Double = 0.15

    /** 语音条：等待「正在录音」的时长 = 秒数 × 该系数。 */
    const val VOICE_PER_SECOND_MS: Long = 300L
    const val VOICE_MIN_DELAY_MS: Long = 800L
    const val VOICE_MAX_DELAY_MS: Long = 2_500L

    /** 图片：等待「正在发送图片」的时长。 */
    const val IMAGE_DELAY_MS: Long = 800L

    /** 表情：固定 900ms（round5 3.3，不按字数算）。 */
    const val STICKER_DELAY_MS: Long = 900L

    /** 气泡区的输入指示文案（顶栏沿用「正在输入中」）。 */
    const val TYPING_TEXT: String = "正在输入…"
    const val RECORDING_TEXT: String = "正在录音…"
    const val SENDING_IMAGE_TEXT: String = "正在发送图片…"
    const val SENDING_STICKER_TEXT: String = "正在发表情…"

    /** 文字气泡的等待时长：`clamp(500 + 字数×70, 700, 2800)`。 */
    fun delayForText(text: String?): Long {
        val chars = text.orEmpty().trim().length
        val raw = BASE_DELAY_MS + chars * PER_CHAR_MS
        return raw.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
    }

    /** 语音条：`clamp(秒数 × 300, 800, 2500)`。 */
    fun delayForVoice(seconds: Int): Long {
        val raw = seconds.coerceAtLeast(0) * VOICE_PER_SECOND_MS
        return raw.coerceIn(VOICE_MIN_DELAY_MS, VOICE_MAX_DELAY_MS)
    }

    /** 图片固定 800ms。 */
    fun delayForImage(): Long = IMAGE_DELAY_MS

    /** 表情固定 900ms（round5：不按字数算）。 */
    fun delayForSticker(): Long = STICKER_DELAY_MS

    /** 给等待时长加 ±15% 抖动（下限不低于 1ms，避免 0 延迟导致「同时出现」）。 */
    fun withJitter(ms: Long, random: Random = Random.Default): Long {
        if (ms <= 0L) return 0L
        val delta = (ms * JITTER_RATIO).toLong()
        if (delta <= 0L) return ms
        val offset = random.nextLong(-delta, delta + 1)
        return (ms + offset).coerceAtLeast(1L)
    }

    /** 一条待显示气泡的等待时长（第一条永远 0）。 */
    fun delayFor(message: ChatMessage, isFirstInTurn: Boolean, random: Random = Random.Default): Long {
        if (isFirstInTurn) return FIRST_DELAY_MS
        val base = when {
            message.sticker != null -> delayForSticker()
            message.voiceUrl != null || message.voiceSeconds != null -> delayForVoice(message.voiceSeconds ?: 0)
            message.imageUris.isNotEmpty() -> delayForImage()
            else -> delayForText(message.text)
        }
        return withJitter(base, random)
    }

    /** 这条气泡在等待时，界面上该显示什么状态文案。 */
    fun statusFor(message: ChatMessage): String = when {
        message.sticker != null -> SENDING_STICKER_TEXT
        message.voiceUrl != null || message.voiceSeconds != null -> RECORDING_TEXT
        message.imageUris.isNotEmpty() -> SENDING_IMAGE_TEXT
        else -> TYPING_TEXT
    }
}
