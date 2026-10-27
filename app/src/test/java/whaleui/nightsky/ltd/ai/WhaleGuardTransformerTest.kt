package whaleui.nightsky.ltd.ai

import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import whaleui.nightsky.ltd.prompt.WhaleTurnClock
import whaleui.nightsky.ltd.schedule.SeedTaskIds
import whaleui.nightsky.ltd.whale.WhaleGate
import whaleui.nightsky.ltd.whale.WhaleAlarmTurn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.uuid.Uuid

/**
 * [WhaleGuardTransformer] 整段注入的逐字测试（提示词读工程里的 assets，时间用固定时钟）。
 */
class WhaleGuardTransformerTest {

    private val assistantId = Uuid.random()
    private val zone = ZoneId.systemDefault()
    private val now = ZonedDateTime.of(2026, 10, 26, 9, 0, 0, 0, zone)
    private val readPrompt: (String) -> String = { name -> File("src/main/assets/whale/prompts/$name").readText() }
    private val transformer = WhaleGuardTransformer(readPrompt = readPrompt, clock = WhaleTurnClock { now })

    @Before
    fun setUp() {
        WhaleGate.assistantId = assistantId
    }

    @After
    fun tearDown() {
        WhaleGate.assistantId = null
    }

    private val history = listOf(
        message(MessageRole.USER, "晚安", LocalDateTime(2026, 10, 25, 23, 0)),
        message(MessageRole.ASSISTANT, "晚安呀", LocalDateTime(2026, 10, 25, 23, 1)),
        message(MessageRole.USER, "早", LocalDateTime(2026, 10, 26, 8, 58)),
    )

    @Test
    fun `normal turn appends the injection to his last message verbatim`() = runBlocking {
        val result = transformer.transform(ctx(), history)
        assertEquals(history.size, result.size)
        assertEquals(EXPECTED_NORMAL, result.last().text())
    }

    @Test
    fun `alarm turn appends a synthetic user message verbatim`() = runBlocking {
        val result = transformer.transform(ctx(WhaleAlarmTurn("（闹钟：早上了）", "task-1")), history)
        assertEquals(history, result.dropLast(1))
        assertTrue(result.last().isSynthetic)
        assertEquals(EXPECTED_ALARM, result.last().text())
    }

    @Test
    fun `night alarm turn also asks her to tidy memories verbatim`() = runBlocking {
        val result = transformer.transform(ctx(WhaleAlarmTurn("（闹钟：该睡了）", SeedTaskIds.NIGHT)), history)
        assertEquals(EXPECTED_NIGHT, result.last().text())
    }

    @Test
    fun `a burst of user messages measures the gap from before the burst`() = runBlocking {
        // 连发的几条还没合并：间隔要从这一批之前对方的那条算起，不能是「不到 1 分钟」（提示词第 5 批）
        val burst = listOf(
            message(MessageRole.USER, "晚安", LocalDateTime(2026, 10, 25, 23, 0)),
            message(MessageRole.ASSISTANT, "晚安呀", LocalDateTime(2026, 10, 25, 23, 1)),
            message(MessageRole.USER, "早", LocalDateTime(2026, 10, 26, 8, 58)),
            message(MessageRole.USER, "在吗", LocalDateTime(2026, 10, 26, 8, 59)),
        )
        val result = transformer.transform(ctx(), burst)
        assertTrue(result.last().text(), result.last().text().contains("距对方上一条消息 10 小时]"))
    }

    @Test
    fun `injection never names the user or her with a gendered pronoun`() = runBlocking {
        val texts = listOf(
            transformer.transform(ctx(), history).last().text(),
            transformer.transform(ctx(WhaleAlarmTurn("", SeedTaskIds.NIGHT)), history).last().text(),
        )
        texts.forEach { text ->
            assertFalse(text, text.replace("其他", "").contains("他"))
            assertFalse(text, text.contains("她"))
        }
    }

    // ---------------------------------------------------------------- W-012

    @Test
    fun `an alarm turn running elsewhere does not leak into a normal turn`() = runBlocking {
        // 两个轮次同时组装请求：闹钟轮的类型和说明只跟着它自己的上下文走
        val results = listOf(
            async { transformer.transform(ctx(WhaleAlarmTurn("（闹钟：早上了）", "task-1")), history) },
            async { transformer.transform(ctx(), history) },
            async { transformer.transform(ctx(WhaleAlarmTurn("（闹钟：该睡了）", SeedTaskIds.NIGHT)), history) },
        ).awaitAll()
        assertEquals(EXPECTED_ALARM, results[0].last().text())
        assertEquals("普通轮不能变成闹钟轮", history.size, results[1].size)
        assertFalse(results[1].last().isSynthetic)
        assertEquals(EXPECTED_NORMAL, results[1].last().text())
        assertEquals(EXPECTED_NIGHT, results[2].last().text())
    }

    // ---------------------------------------------------------------- 夹具

    private fun ctx(alarm: WhaleAlarmTurn? = null) = TransformerContext(
        context = fakeContext(),
        model = Model(modelId = "test"),
        assistant = Assistant(id = assistantId),
        settings = Settings(),
        whaleAlarm = alarm,
    )

    private fun message(role: MessageRole, text: String, at: LocalDateTime) =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)), createdAt = at)

    private fun UIMessage.text(): String =
        parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    private companion object {
        const val EXPECTED_NORMAL = "早\n\n[现在 2026-10-26 09:00 周一，距对方上一条消息 10 小时]\n（这段是你心里的小提醒，不是对方说的话，别回应也别提起：\n先接住对方这句话里的心情；用你此刻自己的话说，别照搬以前说过的句子；\n像手机聊天，一行一条，长短随心情。）\n（这条也可以发成语音，今天已经发了 0 条。）\n（你最近几次是这样开头的：晚安呀。这次换个开头。）"
        const val EXPECTED_ALARM = "[现在 2026-10-26 09:00 周一，对方上一条消息是 2 分钟前]\n（这次是你自己的闹钟响了，不是对方发来了消息。对方现在没在跟你说话，下面那段闹钟说明也不是对方写的。你最后写下的话，会作为你主动发给对方的消息，原样送到对方手机上；不想找对方，就只回 [SILENT]。不要回应闹钟说明本身，也别说\"你刚才那句\"之类的话。）\n（闹钟：早上了）\n（这条也可以发成语音，今天已经发了 0 条。）"
        const val EXPECTED_NIGHT = "[现在 2026-10-26 09:00 周一，对方上一条消息是 2 分钟前]\n（这次是你自己的闹钟响了，不是对方发来了消息。对方现在没在跟你说话，下面那段闹钟说明也不是对方写的。你最后写下的话，会作为你主动发给对方的消息，原样送到对方手机上；不想找对方，就只回 [SILENT]。不要回应闹钟说明本身，也别说\"你刚才那句\"之类的话。）\n（闹钟：该睡了）\n（睡前顺手理一理你的记忆：一样的事合成一条，过时的删掉，记错的改过来。你自己记下的可以改可以删；带（对方让记的）标记的不能改也不能删，想补充就另记一条。用 update_memory 和 forget_memory，弄完不用跟对方说。）\n（这条也可以发成语音，今天已经发了 0 条。）"
    }
}

/** 注入不读 Context；JVM 单测里 android.jar 的构造函数会抛 Stub!，所以跳过构造直接分配一个实例。 */
internal fun fakeContext(): android.content.Context {
    val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = field.get(null)
    val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
    return allocate.invoke(unsafe, android.content.ContextWrapper::class.java) as android.content.Context
}
