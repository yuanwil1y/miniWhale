package whaleui.nightsky.ltd.ui.advanced

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import me.rerere.rikkahub.service.ChatService
import whaleui.nightsky.ltd.minimax.WhaleMiniMaxConfig
import whaleui.nightsky.ltd.minimax.WhaleMiniMaxPrefs
import whaleui.nightsky.ltd.whale.WhaleConversationProvider
import whaleui.nightsky.ltd.whale.WhaleGate
import whaleui.nightsky.ltd.whale.WhaleRuntimeStore
import kotlin.uuid.Uuid

// ---------------------------------------------------------------------------
// 高级设置 ViewModel 依赖的几个小接口。
//
// 生产实现都很薄，只是把已有的数据层（WhaleMiniMaxPrefs / ProviderManager / ChatService / Logging）
// 接进来；单测里换成内存假实现，ViewModel 本身只认这些接口和 SettingsStore / SecretStore。
// ---------------------------------------------------------------------------

/** MiniMax 的三项非敏感配置（接口地址 / 语音模型 / 作图模型）。Key 不在这里，在 SecretStore。 */
data class MiniMaxConfigValues(
    val baseUrl: String,
    val speechModel: String,
    val imageModel: String,
)

interface MiniMaxConfigStore {
    fun current(): MiniMaxConfigValues

    /** 写盘并立即应用到运行时（MiniMax 客户端每次调用都现读，不用重启）。 */
    fun save(values: MiniMaxConfigValues)
}

/** 生产实现：[WhaleMiniMaxConfig] 是运行时值，[WhaleMiniMaxPrefs] 负责落盘。 */
object WhaleMiniMaxConfigStore : MiniMaxConfigStore {
    override fun current(): MiniMaxConfigValues = MiniMaxConfigValues(
        baseUrl = WhaleMiniMaxConfig.baseUrl,
        speechModel = WhaleMiniMaxConfig.speechModel,
        imageModel = WhaleMiniMaxConfig.imageModel,
    )

    override fun save(values: MiniMaxConfigValues) {
        WhaleMiniMaxPrefs.save(values.baseUrl, values.speechModel, values.imageModel)
    }
}

/** 向供应商拉取在线模型 id 列表（会联网，要 Key）。 */
fun interface RemoteModelLister {
    suspend fun list(provider: ProviderSetting): List<String>
}

class ProviderRemoteModelLister(
    private val providerManager: ProviderManager,
    private val providerKeys: whaleui.nightsky.ltd.whale.WhaleProviderKeys,
) : RemoteModelLister {
    override suspend fun list(provider: ProviderSetting): List<String> {
        // W-028：设置里的 apiKey 是空的，Key 在请求前从 SecretStore 现取
        val withKey = providerKeys.withRuntimeKey(provider)
        return providerManager.getProviderByType(withKey).listModels(withKey).map { it.modelId }
    }
}

/**
 * 她（小鲸鱼助手）和她那一个固定对话。
 *
 * 为什么改模型要同时动对话：对话开始后，模型和思考强度会**固定在对话上**（`ConversationConfig`），
 * 只改助手的话，正在用的这个对话不会跟着变。
 */
interface WhaleChatTarget {
    /** 小鲸鱼助手的 id；初始化还没跑完时为 null。 */
    fun assistantId(): Uuid?

    /** 把模型 / 思考强度同步到固定对话（对话还没开始时只是写回助手，效果相同）。 */
    suspend fun applyToConversation(chatModelId: Uuid, reasoningLevel: ReasoningLevel)
}

class ChatServiceWhaleChatTarget(
    private val runtimeStore: WhaleRuntimeStore,
    private val conversations: WhaleConversationProvider,
    /** ChatService 很重，等真的要改模型时再取。 */
    private val chatService: () -> ChatService,
) : WhaleChatTarget {
    override fun assistantId(): Uuid? = WhaleGate.assistantId ?: runtimeStore.whaleAssistantId

    override suspend fun applyToConversation(chatModelId: Uuid, reasoningLevel: ReasoningLevel) {
        val conversationId = conversations.fixedConversation().id
        chatService().updateChatAssistant(conversationId) {
            it.copy(chatModelId = chatModelId, reasoningLevel = reasoningLevel)
        }
    }
}

/** 进程内的最近日志（`Logging`，最多 100 条，只在内存里）。 */
interface AppLogSource {
    fun recent(): List<LogEntry>
    fun clear()
    fun isRequestLoggingEnabled(): Boolean
    fun setRequestLoggingEnabled(enabled: Boolean)
}

object CommonAppLogSource : AppLogSource {
    override fun recent(): List<LogEntry> = Logging.getRecentLogs()
    override fun clear() = Logging.clear()
    override fun isRequestLoggingEnabled(): Boolean = Logging.isRequestLoggingEnabled()
    override fun setRequestLoggingEnabled(enabled: Boolean) = Logging.setRequestLoggingEnabled(enabled)
}
