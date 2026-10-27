package whaleui.nightsky.ltd.data

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import whaleui.nightsky.ltd.whale.WhaleConversationProvider

/**
 * 导入聊天记录：把导出包 `chat/` 目录下 md 里的消息合并进唯一那个固定对话。
 *
 * 只增不减、可重复导入（角色 + 文字 + 时间相同的跳过）。写库后清掉 ChatService
 * 内存里的旧会话，界面再 `reload()` 就能看到导入的历史。
 */
class ChatBundleImporter(
    private val conversations: WhaleConversationProvider,
    private val conversationRepository: ConversationRepository,
    private val chatService: ChatService,
) {

    sealed interface Result {
        data class Success(
            /** 实际解析出消息的文件数。 */
            val files: Int,
            /** 解析出的消息总数。 */
            val messages: Int,
            val added: Int,
            val skipped: Int,
        ) : Result

        /** [message] 是直接给用户看的中文原因。 */
        data class Failure(val message: String) : Result
    }

    suspend fun import(files: List<BundleText>): Result = withContext(Dispatchers.IO) {
        val parsed = files.associateWith { ChatMarkdownParser.parse(it.content) }
        val incoming = parsed.values.flatten().sortedBy { it.at }
        if (incoming.isEmpty()) {
            return@withContext Result.Failure(
                "没有解析到聊天记录（需要 ## 2026-10-25 和 - [HH:mm] 她/我：内容）"
            )
        }

        val conversation = conversations.fixedConversation()
        if (chatService.isGenerating(conversation.id)) {
            return@withContext Result.Failure("她正在回复，等这一轮说完再导入")
        }
        // 重新读一遍最新的会话：导入期间可能刚触发过一轮主动消息，不能拿旧快照覆盖。
        val latest = conversationRepository.getConversationById(conversation.id) ?: conversation
        val merge = ChatConversationMerge.merge(latest.messageNodes, incoming)
        if (merge.added > 0) {
            try {
                conversationRepository.updateConversation(
                    latest.copy(
                        messageNodes = merge.nodes,
                        updateAt = Instant.now(),
                    )
                )
                // 丢掉内存里的旧会话，下一次读取直接走数据库（新节点就生效了）。
                chatService.cleanup()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext Result.Failure("写聊天记录失败：${e.message ?: e::class.simpleName}")
            }
        }

        Result.Success(
            files = parsed.values.count { it.isNotEmpty() },
            messages = incoming.size,
            added = merge.added,
            skipped = merge.skipped,
        )
    }
}
