package whaleui.nightsky.ltd.ai

import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import whaleui.nightsky.ltd.whale.WhaleGate
import kotlin.uuid.Uuid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** 决定 5 / 任务书 4.2：只带最近 40–50 轮，按块滑动，切完第一条是 user。 */
class WhaleHistoryLimiterTest {

    private val assistantId = Uuid.random()

    @After
    fun tearDown() {
        WhaleGate.assistantId = null
        WhaleHistoryConfig.reset()
    }

    private fun ctx(): TransformerContext {
        WhaleGate.assistantId = assistantId
        return TransformerContext(
            context = null as android.content.Context,
            model = me.rerere.ai.provider.Model(modelId = "test"),
            assistant = Assistant(id = assistantId),
            settings = Settings(),
            processingStatus = MutableStateFlow(null),
        )
    }

    private fun user(text: String) = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))
    private fun assistant(text: String) = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    /** system + turns 轮（每轮 user + 两条 her 的气泡，模拟多气泡）。 */
    private fun conversation(turns: Int): List<UIMessage> = buildList {
        add(assistant("system prompt"))
        repeat(turns) { index ->
            add(user("u$index"))
            add(assistant("a${index}-1"))
            add(assistant("a${index}-2"))
        }
    }

    private fun run(messages: List<UIMessage>, ignored: TransformerContext? = null): List<UIMessage> =
        WhaleHistoryLimiter().limit(messages)

    @Test
    fun `fifty turns are kept untouched`() {
        val messages = conversation(50)
        val result = run(messages)
        assertEquals(messages.size, result.size)
    }

    @Test
    fun `fifty one turns are cut down to forty`() {
        val result = run(conversation(51))
        // 1 条 system + 40 轮 × 3 条
        assertEquals(1 + 40 * 3, result.size)
        assertEquals(MessageRole.ASSISTANT, result.first().role) // system
        assertEquals(MessageRole.USER, result[1].role)          // 第一条历史必须是 user
    }

    @Test
    fun `kept history starts at a turn boundary`() {
        val result = run(conversation(60))
        val firstUser = result.drop(1).first().text()
        // 60 轮保留最后 40 轮：从 u20 开始
        assertEquals("u20", firstUser)
    }

    @Test
    fun `tool calls stay inside their turn and are never split`() {
        val toolPart = UIMessagePart.Tool(toolCallId = "c1", toolName = "weather", input = "{}", output = emptyList())
        val messages = buildList {
            add(assistant("system"))
            repeat(51) { index ->
                add(user("u$index"))
                if (index == 50) {
                    add(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("我想想"), toolPart)))
                } else {
                    add(assistant("a$index"))
                }
            }
        }
        val result = run(messages)
        // 最后一个 assistant 里的 tool 部件仍然在（没有被切开或丢掉）
        val lastAssistant = result.last()
        assertTrue(lastAssistant.parts.any { it is UIMessagePart.Tool })
    }

    @Test
    fun `alarm style history ending with assistant is fine`() {
        val messages = buildList {
            add(assistant("system"))
            repeat(51) { index ->
                add(user("u$index"))
                add(assistant("a$index"))
            }
            add(assistant("她主动发的一条"))
        }
        val result = run(messages)
        assertEquals(1 + 40 * 2 + 1, result.size)
        assertEquals(MessageRole.USER, result[1].role)
    }

    @Test
    fun `non whale assistants keep full history`() {
        // 门禁在 transform 里（WhaleGate.isWhaleAssistant），纯函数本身对所有会话都按同样规则截断，
        // 这里验证「不是小鲸鱼时 transform 直接返回原列表」由另一个用例覆盖（见 WhaleHistoryGateTest）。
        val messages = conversation(60)
        assertEquals(messages.size, WhaleHistoryLimiter().limit(messages, keepTurns = 1_000, maxTurns = 1_000).size)
    }

    private fun UIMessage.text(): String =
        (parts.firstOrNull() as? UIMessagePart.Text)?.text.orEmpty()
    @Test
    fun `block sliding keeps the prefix stable until history grows past max again`() {
        // keep=4 / max=6：7 轮 -> 截到 4 轮
        val truncated = WhaleHistoryLimiter().limit(conversation(7), keepTurns = 4, maxTurns = 6)
        val keptTurn = truncated.drop(1).first().text()
        assertEquals("u3", keptTurn) // 保留最后 4 轮：u3..u6

        // 再长一轮：总轮数 5 <= 6，不再截断 —— 前缀（u3 开头）保持不变，前缀缓存才能持续命中
        val next = truncated + listOf(user("u7"), assistant("a7-1"), assistant("a7-2"))
        val stillKept = WhaleHistoryLimiter().limit(next, keepTurns = 4, maxTurns = 6)
        assertEquals(next.size, stillKept.size)
        assertEquals("u3", stillKept.drop(1).first().text())
    }

    @Test
    fun `log line reports turns and message counts without any content`() {
        val line = historyLogLine(keptTurns = 4, fromTurn = 3, totalTurns = 6, beforeMessages = 19, afterMessages = 13)
        assertEquals("history: kept 4 turns (from turn 3 of 6), messages 19 -> 13", line)
        // 只有数字和固定词：不能带任何消息正文（这里用断言钉住格式）
        assertTrue(line.matches(Regex("""history: kept \d+ turns \(from turn \d+ of \d+\), messages \d+ -> \d+""")))
    }

    @Test
    fun `default thresholds are forty and fifty`() {
        WhaleHistoryConfig.reset()
        assertEquals(40, WhaleHistoryConfig.keepTurns)
        assertEquals(50, WhaleHistoryConfig.maxTurns)
    }

    // ---------------------------------------------------------- 连发合并（round5 3.7）

    @Test
    fun `consecutive user messages count as one turn`() {
        // 第一轮是 3 条连续 user（他连发），第二轮 1 条；总共只有 2 轮
        val messages = listOf(
            assistant("system prompt"),
            user("u0-1"), user("u0-2"), user("u0-3"),
            assistant("a0"),
            user("u1"),
            assistant("a1"),
        )
        // maxTurns = 2：两轮不超，不该截断（如果每条 user 都算一轮，就会被当成 4 轮砍掉）
        val kept = WhaleHistoryLimiter().limit(messages, keepTurns = 2, maxTurns = 2)
        assertEquals(messages.size, kept.size)
    }

    @Test
    fun `cutting at a turn boundary keeps the whole consecutive user group`() {
        val messages = listOf(
            assistant("system prompt"),
            user("old-1"), user("old-2"), user("old-3"),
            assistant("a0"),
            user("new-1"), user("new-2"),
            assistant("a1"),
        )
        val kept = WhaleHistoryLimiter().limit(messages, keepTurns = 1, maxTurns = 1)
        // 保留 system + 最后一轮：两条连续 user + 她的回复，前面那一轮整段丢掉
        assertEquals(4, kept.size)
        assertEquals(MessageRole.ASSISTANT, kept[0].role) // system 永远保留
        assertEquals("new-1", kept[1].text())
        assertEquals("new-2", kept[2].text())
        assertEquals("a1", kept[3].text())
    }
}