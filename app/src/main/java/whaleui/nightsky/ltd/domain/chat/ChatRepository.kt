package whaleui.nightsky.ltd.domain.chat

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 聊天数据源。以后换成 DeepSeek 实现时只替换这一层，页面和 ViewModel 不用动。
 */
interface ChatRepository {
    /** 按对话 id 加载首屏消息；没有这条对话时返回空列表（空状态）。 */
    suspend fun loadInitial(conversationId: Long): List<ChatMessage>

    /** 按对话 id 加载更早的一页；没有更多时返回空列表。 */
    suspend fun loadOlder(conversationId: Long): List<ChatMessage>

    /**
     * 发出消息，并推送发送状态、对方正在输入、对方陆续回复等事件。
     * 这里返回 Flow 而不是自己持有协程，生命周期由 ViewModel 管。
     */
    fun send(conversationId: Long, outgoing: List<ChatMessage>): Flow<ChatEvent>

    /**
     * 删除界面上的这条消息并落库（[messageId] 是界面用的 Long id，见 [MessageIds]）。
     * 默认空实现：假数据源/预览没有持久化层；真数据源会真正删掉并写库。
     */
    suspend fun deleteMessage(conversationId: Long, messageId: Long) {}

    /**
     * 一次发多张图 / 多个文件时，库里是一条消息（[messageId]），界面上每个附件一个气泡。
     * 删掉其中一个气泡 = 从这条消息里删掉 [bubble] 带的那些部件（图片、文件、文字）；删到没有内容了就删整条（F5）。
     */
    suspend fun deleteMessagePart(conversationId: Long, messageId: Long, bubble: ChatMessage) {}
}

/**
 * 64 个桶的说话包络。真机上应由音频解码层按 PCM 的 RMS 生成；
 * debug 的假语音命令也用它，UI 仍然只消费 [ChatMessage.waveform]。
 */
fun speechWaveform(count: Int, seed: Long = 7L): List<Float> {
    val r = Random(seed)
    val syllables = 8
    return List(count) { index ->
        val t = index.toFloat() / count
        val syllable = sin(t * syllables * PI).toFloat()
        val envelope = (0.35f + 0.65f * kotlin.math.abs(syllable)).coerceIn(0f, 1f)
        val breath = 0.88f + r.nextFloat() * 0.12f
        (0.10f + 0.78f * envelope * breath).coerceIn(0.06f, 0.96f)
    }
}

/** Repository 向 ViewModel 推送的事件。 */
sealed interface ChatEvent {
    /**
     * 新气泡。[afterId] 是它在库里前一条可见消息的界面 id：界面按它插到正确位置
     * （她回到一半他又发了消息时，她这一轮的气泡要排在他新消息前面）；为 null 或找不到时放到最后。
     */
    data class Added(val message: ChatMessage, val afterId: Long? = null) : ChatEvent

    /** 同一条消息的内容变了（真实模型流式生成时，一个气泡的文字会不断增长）。 */
    data class Updated(val message: ChatMessage) : ChatEvent

    /**
     * 这些消息不该再显示了。
     *
     * 真实模型这一轮**先流进同一个节点**，`finishGeneration` 再按行把它切成多个节点，
     * 原来那个「合并节点」就消失了。如果只追加新气泡、不删旧的，界面上就会出现
     * 「一条合并气泡 + 每行各一条」，重启后（只读库）又恢复正常 —— 这个事件就是修这个的。
     */
    data class Removed(val messageIds: List<Long>) : ChatEvent

    data class StatusChanged(val messageId: Long, val status: MessageStatus) : ChatEvent

    /**
     * 他发出的消息入库了：界面乐观插入的气泡 [localIds] 对应库里的消息 [messageId]（见 [MessageIds]）。
     * 一次发送带多张图时界面是多个气泡、库里是一条消息，所以是多对一。
     */
    data class IdAssigned(val localIds: List<Long>, val messageId: Long) : ChatEvent
    data class Typing(val typing: Boolean) : ChatEvent
    data class ConversationRead(val upToMessageId: Long) : ChatEvent
}
