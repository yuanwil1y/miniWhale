package whaleui.nightsky.ltd.prompt

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 请求日志的标记摘要（只有位置，没有正文）。 */
class WhaleRequestLogTest {

    @Test
    fun `marks are reported with their offsets in order of appearance`() {
        val text = "他的消息\n\n[现在 2026-10-26 14:30 周一，距他上一条消息 3 分钟]\n" +
            "（这段是你心里的小提醒，不是他说的话，别回应也别提起：…）\n" +
            "（回之前想一下：这条适不适合说成语音？…）\n" +
            "（你最近几次是这样开头的：好耶 ｜ 晚安。这次换个开头。）"
        val summary = WhaleRequestLog.markSummary(text)
        assertTrue(summary.contains("now@"))
        assertTrue(summary.contains("reminder@"))
        assertTrue(summary.contains("voice@"))
        assertTrue(summary.contains("antirepeat@"))
        // 顺序必须是 报时 -> reminder -> voice -> antirepeat
        val order = listOf("now@", "reminder@", "voice@", "antirepeat@").map { summary.indexOf(it) }
        assertEquals(order.sorted(), order)
    }

    @Test
    fun `alarm turn marks are reported`() {
        val text = "[现在 2026-10-26 08:30 周一，他上一条消息是 1 天前]\n" +
            "（这次是你自己的闹钟响了，不是他发来了消息。…）\n" +
            "（闹钟：早上了，你自己醒了。…）\n" +
            "（回之前想一下：…）"
        val summary = WhaleRequestLog.markSummary(text)
        assertTrue(summary.contains("cron@"))
        assertTrue(summary.contains("alarmnote@"))
        assertTrue(summary.contains("voice@"))
        // 闹钟轮不该出现 REMINDER / 防复读
        assertTrue(!summary.contains("reminder@"))
        assertTrue(!summary.contains("antirepeat@"))
    }

    @Test
    fun `plain text has no marks`() {
        assertEquals("", WhaleRequestLog.markSummary("今天好累啊"))
    }

    @Test
    fun `log lines carry only numbers and marker labels, never message text`() {
        // W-057：以前 user ≤600 字、其它 ≤120 字整段打出，长正文打前 30 字
        val secret = "他说的一句很私密的话"
        val messages = listOf(
            UIMessage.system("人设文本。SOUL：$secret"),
            UIMessage.assistant("她的回复：$secret"),
            UIMessage.user("$secret\n\n[现在 2026-10-26 14:30 周一]\n（回之前想一下：…）"),
        )

        val lines = WhaleRequestLog.lines(messages, tag = "conv")

        assertEquals("[conv] request messages = 3", lines.first())
        lines.forEach { line ->
            assertFalse(line, line.contains("私密"))
            assertFalse(line, line.contains("小鲸鱼"))
            assertFalse(line, line.contains("回复"))
            assertFalse(line, line.contains("现在 2026"))
        }
        assertTrue(lines.contains("[2] role=USER len=${messages[2].parts.filterIsInstance<UIMessagePart.Text>().single().text.length} parts=1"))
        assertTrue(lines.contains("[2] marks: now@${secret.length + 2} voice@${secret.length + 27}"))
    }

    @Test
    fun `native time reminder is detectable`() {
        val summary = WhaleRequestLog.markSummary("hi\n<time_reminder>5 分钟前</time_reminder>")
        assertTrue(summary.contains("native_time@"))
    }
}