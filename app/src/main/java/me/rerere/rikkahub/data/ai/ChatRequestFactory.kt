package me.rerere.rikkahub.data.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.prompts.buildMemoryPrompt
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import whaleui.nightsky.ltd.ai.WhaleCompatTransformer
import whaleui.nightsky.ltd.ai.WhaleOutputTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.memoryId
import kotlin.uuid.Uuid

/**
 * 一轮对话的请求上下文，按会话固定的配置解析一次。
 */
class ChatTurnContext(
    val settings: Settings,
    val assistant: Assistant,
    val model: Model,
    val providerSetting: ProviderSetting,
    val conversationId: Uuid,
    val conversationSystemPrompt: String?,
    val workspaceCwd: String?,
    val memories: List<MemoryFile>,
    val tools: List<Tool>,
    /** 小鲸鱼：这一轮是闹钟轮时不为 null，交给输入转换（WhaleGuardTransformer）组装闹钟轮的注入 */
    val whaleAlarm: whaleui.nightsky.ltd.whale.WhaleAlarmTurn? = null,
)

/** 把会话变成发给模型的请求：系统提示词、上下文裁剪、输入输出转换都在这里 */
class ChatRequestFactory(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val chatToolFactory: ChatToolFactory,
    private val memoryRepository: MemoryRepository,
    templateTransformer: TemplateTransformer,
    /** 小鲸鱼的人设/记忆/常驻技能拼装 */
    private val whalePrompt: whaleui.nightsky.ltd.prompt.WhaleSystemPrompt,
    /** 小鲸鱼每轮注入（任务书 4.3）。放在输入转换链最前面。 */
    whaleGuard: whaleui.nightsky.ltd.ai.WhaleGuardTransformer,
    /** 小鲸鱼历史截断（决定 5）：只带最近 40–50 轮，按块滑动保前缀缓存。 */
    whaleHistory: whaleui.nightsky.ltd.ai.WhaleHistoryLimiter,
    /** W-028：DeepSeek Key 只在 SecretStore，请求前从这里现取 */
    private val providerKeys: whaleui.nightsky.ltd.whale.WhaleProviderKeys,
) {
    /** 逐条处理、和消息位置无关的输入转换：历史和本轮的助手消息都要过 */
    private val perMessageTransformers: List<InputMessageTransformer> = listOf(
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        templateTransformer,
        // 小鲸鱼：DeepSeek 兼容性修补放最后，此时消息已经过完所有原生转换（任务书 1.6）
        WhaleCompatTransformer(),
    )

    /** 历史的整条输入转换链（W-026：只用在本轮开始前的历史上，本轮的助手消息不参与） */
    private val historyTransformers: List<InputMessageTransformer> =
        historyChain(whaleHistory, whaleGuard, perMessageTransformers)

    private val outputTransformers: List<OutputMessageTransformer> = listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
        // 小鲸鱼：回复清理与分条（正则照抄 Hermes persona_guard，任务书 1.5）
        WhaleOutputTransformer(),
    )

    /** 解析本轮的助手、模型、记忆和工具 */
    suspend fun createContext(
        settings: Settings,
        conversation: Conversation,
        whaleAlarm: whaleui.nightsky.ltd.whale.WhaleAlarmTurn? = null,
    ): ChatTurnContext {
        // 模型、思考级别、搜索、工具等以会话上固定的配置为准
        val assistant = settings.getAssistantOf(conversation)
        val model = settings.getChatModelOf(conversation)
            ?: throw IllegalStateException("No chat model selected")
        // 设置里内置 DeepSeek 的 apiKey 是空的（W-028），这里在内存里换上 SecretStore 的 Key
        val provider = providerKeys.withRuntimeKey(
            model.findProvider(settings.providers) ?: error("Provider not found")
        )
        return ChatTurnContext(
            settings = settings,
            assistant = assistant,
            model = model,
            providerSetting = provider,
            conversationId = conversation.id,
            conversationSystemPrompt = conversation.customSystemPrompt,
            workspaceCwd = conversation.workspaceCwd,
            memories = if (assistant.enableMemory) memoryRepository.getFiles(assistant.memoryId) else emptyList(),
            tools = chatToolFactory.createTools(
                settings = settings,
                assistant = assistant,
                model = model,
                deepSeekKey = providerKeys.deepSeekKey(settings.providers),
            ),
            whaleAlarm = whaleAlarm,
        )
    }

    /**
     * 本轮交给 runAgent 的请求和钩子。[messages] 的最后一条是助手消息时从它继续，否则新建一条助手消息。
     * [processingStatus] 用来显示 OCR 这类输入转换的进度。
     */
    fun createAgent(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?>,
    ): Pair<AgentRequest, AgentHooks> {
        val request = AgentRequest(
            provider = usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.CHAT, turn.assistant.id, turn.conversationId),
            ),
            providerSetting = turn.providerSetting,
            messages = messages,
            tools = turn.tools,
            stream = turn.assistant.streamOutput,
            retry = turn.settings.retryPolicy(),
        )
        val hooks = ChatStepHooks(
            context = context,
            turn = turn,
            historyTransformers = historyTransformers,
            replyTransformers = perMessageTransformers,
            outputTransformers = outputTransformers,
            systemPrompt = { stepMessages -> systemPrompt(turn, stepMessages) },
            processingStatus = processingStatus,
        )
        return request to hooks
    }

    /** 系统提示词正文：会话 / 助手的提示词 + 小鲸鱼的人设、记忆、常驻技能 + 记忆 + 工具说明 */
    private suspend fun systemPrompt(turn: ChatTurnContext, messages: List<UIMessage>): String = buildString {
        val assistant = turn.assistant
        val effectiveSystemPrompt =
            if (assistant.allowConversationSystemPrompt && !turn.conversationSystemPrompt.isNullOrBlank()) {
                turn.conversationSystemPrompt
            } else {
                assistant.systemPrompt
            }
        if (effectiveSystemPrompt.isNotBlank()) {
            append(effectiveSystemPrompt)
        }

        // 小鲸鱼：SOUL + 记忆 + goutoujunshi / voice-message 常驻正文（任务书 4.2）。
        // 她的 Assistant.systemPrompt 是空的，所以这段就是整段 system 的开头。
        if (whaleui.nightsky.ltd.whale.WhaleGate.isWhaleAssistant(assistant.id)) {
            val whaleSystem = whalePrompt.build(assistant)
            if (whaleSystem.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(whaleSystem)
            }
        }

        // 记忆
        if (assistant.enableMemory) {
            appendLine()
            append(buildMemoryPrompt(turn.memories))
        }
        // 工具prompt：大多数工具的 systemPrompt 是空的，空的不留空行（提示词第 1 批）
        appendToolPrompts(turn.tools.map { it.systemPrompt(turn.model, messages) })
    }

    internal companion object {
        /** 把工具的 systemPrompt 接在后面：空的跳过（不留空行），非空的和前文隔一个空行。 */
        fun StringBuilder.appendToolPrompts(prompts: List<String>) {
            prompts.forEach { toolPrompt ->
                if (toolPrompt.isBlank()) return@forEach
                if (isNotEmpty()) {
                    appendLine()
                    appendLine()
                }
                append(toolPrompt.trim())
            }
        }

        /**
         * 历史的整条输入转换链。先截历史（决定 5），再注入（注入要落在"最后一条 user"上，所以必须在截断之后）；
         * 小鲸鱼每轮注入时消息还是原始的，注入内容不会被后面的转换再处理一遍；然后是原生的报时 / 提示词注入，
         * 最后是逐条处理的转换（[perMessage]）。
         */
        fun historyChain(
            whaleHistory: InputMessageTransformer,
            whaleGuard: InputMessageTransformer,
            perMessage: List<InputMessageTransformer>,
        ): List<InputMessageTransformer> = listOf(
            whaleHistory,
            whaleGuard,
            TimeReminderTransformer,
            PromptInjectionTransformer,
        ) + perMessage
    }
}

/**
 * 输出转换只处理这一步新生成的 part：之前步骤的内容已经处理过，再处理一遍会让正则这类改写叠加，
 * 也能让每一步开头的 <think> 都被识别出来。
 */
internal suspend fun UIMessage.transformNewParts(
    newPartsFrom: Int,
    transform: suspend (List<UIMessage>) -> List<UIMessage>,
): UIMessage {
    val transformed = transform(listOf(copy(parts = parts.drop(newPartsFrom)))).single()
    return transformed.copy(parts = parts.take(newPartsFrom) + transformed.parts)
}

internal fun Settings.retryPolicy() =
    if (networkSetting.enableAutoRetry) RetryPolicy.NETWORK else RetryPolicy.NONE
