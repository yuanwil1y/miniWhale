package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.data.skills.SkillManager

/** 一轮生成要挂的全部工具。都不需要审批（needsApproval 用默认的 false），界面也没有审批入口。 */
class ChatToolFactory(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val skillManager: SkillManager,
    /** 小鲸鱼的全部工具 */
    private val whaleToolset: whaleui.nightsky.ltd.tools.WhaleToolset,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        /** 她的搜索工具用的 DeepSeek Key（与对话同一个，来自 SecretStore，W-028） */
        deepSeekKey: String?,
    ): List<Tool> {
        return buildList {
            if (assistant.enableMemory) {
                addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
            }
            // S9b：原生外部搜索（:search 的 20 种服务）已删；她的搜索在 whaleToolset 里
            // R2：原生本地工具（JS、时间、剪贴板、提问、屏幕使用时间、日历、图表）已删。
            // 她的助手 localTools 每次启动都被 WhaleBootstrap 置空，界面里也没有开关，这些工具从来不会挂上。
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, assistant.id))
            }
            // 小鲸鱼：日记 / 记忆 / 搜索 / 热榜天气诗词 / 语音 / 作图，仅当她自己是这个助手时挂上。
            if (whaleui.nightsky.ltd.whale.WhaleGate.isWhaleAssistant(assistant.id)) {
                addAll(whaleToolset.create(deepSeekKey))
            }
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                    )
                )
            }
            // S10：MCP 已删（她的助手从没挂过 MCP 服务器）
        }
    }
}
