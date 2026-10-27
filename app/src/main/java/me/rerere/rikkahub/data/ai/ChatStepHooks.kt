package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import whaleui.nightsky.ltd.prompt.WhaleTurnClock
import whaleui.nightsky.ltd.reply.WhaleMergeSlices

private const val TAG = "ChatRequestFactory"

/**
 * 一轮对话交给 agent 的钩子：每一步请求怎么组装、每一步的输出怎么转换。
 *
 * 一轮里每一步（第 1…n 步）发出的请求结构都一样（W-026）：
 * 系统提示词 → 历史（最后是本轮触发的消息：普通轮是他最后的 user 消息，带每轮注入；闹钟轮是合成的闹钟消息）
 * → 本轮新开的助手消息（之前步骤的工具调用和结果，按顺序成对）。
 *
 * - 历史先合并切片，再过 [historyTransformers]（截历史、每轮注入、逐条转换……）。这一段只取决于本轮开始前的历史
 *   和本轮的报时时间，所以从第 2 步起和第 1 步逐字一样：注入不会因为最后一条变成助手消息而消失，
 *   闹钟说明也不会排到工具结果后面；
 * - 本轮的助手消息不和历史里她的上一条合并（那是另一条回复），只过 [replyTransformers]（逐条处理的转换），接在最后。
 *
 * 抽成单独的类是为了单测能用真实的 AgentLoop 和转换链跑整轮（`ToolStepRequestTest`）。
 */
internal class ChatStepHooks(
    private val context: Context,
    private val turn: ChatTurnContext,
    /** 只用在历史上的整条输入转换链 */
    private val historyTransformers: List<InputMessageTransformer>,
    /** 用在本轮助手消息上的转换：只有逐条处理、和消息位置无关的那些 */
    private val replyTransformers: List<InputMessageTransformer>,
    private val outputTransformers: List<OutputMessageTransformer>,
    /** 系统提示词正文；每一步现拼（工具可能刚改过记忆），参数是这一步的全部消息 */
    private val systemPrompt: suspend (List<UIMessage>) -> String,
    private val clock: WhaleTurnClock = WhaleTurnClock.System,
    private val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
) : AgentHooks {

    // 本轮的报时时间：第 1 步请求时取，之后每一步都用它
    private var turnNow: ZonedDateTime? = null

    override suspend fun prepareStep(history: List<UIMessage>, reply: UIMessage?): StepRequest {
        runCatching { Log.i(TAG, "prepareStep: ${turn.model.id}") }
        val assistant = turn.assistant
        val now = turnNow ?: clock.now().also { turnNow = it }
        val system = systemPrompt(if (reply == null) history else history + reply)
        val head = buildList {
            if (system.isNotBlank()) {
                add(UIMessage.system(prompt = system).copy(isSynthetic = true))
            }
            // 小鲸鱼：拆成多个气泡后，请求侧要把相邻 assistant 消息合并回一条，模型看到的和没拆一样。
            // 只合并历史：本轮的助手消息是另一条回复，不能并进她的上一条（W-026）
            addAll(WhaleMergeSlices.mergeAdjacentAssistant(history.limitContext(assistant.contextMessageLimit)))
        }.applyChain(historyTransformers, now)
        // 本轮的助手消息（工具调用和结果、思考）接在最后，只做逐条转换
        val tail = reply?.let { listOf(it).applyChain(replyTransformers, now) }.orEmpty()
        val requestMessages = head + tail
        val params = TextGenerationParams(
            model = turn.model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = turn.tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(turn.model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(turn.model.customBodies)
            },
            sessionId = turn.conversationId.toString(),
        )
        // 仅 debug：把最终发给模型的消息脱敏打到 logcat（任务书 4.4），用来核对注入位置
        if (me.rerere.rikkahub.BuildConfig.DEBUG &&
            whaleui.nightsky.ltd.whale.WhaleGate.isWhaleAssistant(assistant.id)
        ) {
            whaleui.nightsky.ltd.prompt.WhaleRequestLog.log(requestMessages, tag = turn.conversationId.toString())
        }
        return StepRequest(requestMessages, params)
    }

    private suspend fun List<UIMessage>.applyChain(
        transformers: List<InputMessageTransformer>,
        now: ZonedDateTime? = null,
    ) = transforms(
        transformers = transformers,
        context = context,
        model = turn.model,
        assistant = turn.assistant,
        settings = turn.settings,
        processingStatus = processingStatus,
        workspaceCwd = turn.workspaceCwd,
        whaleAlarm = turn.whaleAlarm,
        turnNow = now,
    )

    override suspend fun previewStep(message: UIMessage, newPartsFrom: Int) =
        message.transformNewParts(newPartsFrom) {
            it.visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
        }

    override suspend fun finishStep(message: UIMessage, newPartsFrom: Int) =
        message.transformNewParts(newPartsFrom) {
            it.transforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                .visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                .onGenerationFinish(outputTransformers, context, turn.model, turn.assistant, turn.settings)
        }
}
