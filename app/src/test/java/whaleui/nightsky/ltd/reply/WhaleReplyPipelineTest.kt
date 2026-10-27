package whaleui.nightsky.ltd.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WhaleReplyPipeline] 的单元测试。
 *
 * Hermes `persona_guard/__init__.py` 的每条正则（EMOJI / ACTION_LINE / HELP_LINE）、
 * 「／」换行、静默的各种位置、中英混排、超长单行、只有空白，都要有覆盖。
 */
class WhaleReplyPipelineTest {

    // ---------- EMOJI ----------

    @Test
    fun `emoji is removed from a line`() {
        assertEquals(listOf("好耶"), WhaleReplyPipeline.split("好耶😄"))
        assertEquals(listOf("晚上好"), WhaleReplyPipeline.split("晚上好🌙"))
        assertEquals(listOf("晚安"), WhaleReplyPipeline.split("✨晚安✨"))
        assertEquals(listOf("ab"), WhaleReplyPipeline.split("a😂b"))
        assertEquals(listOf("好的"), WhaleReplyPipeline.split("✔️好的"))
        assertEquals(listOf("family"), WhaleReplyPipeline.split("family 👨‍👩‍👧"))
        assertEquals(listOf("ok"), WhaleReplyPipeline.split("👍🏻 ok"))
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("😄😄😄"))
        // 表情删掉后整行变空，等于空行，不留气泡
        assertEquals("", WhaleReplyPipeline.clean("❤️"))
    }

    // ---------- ACTION_LINE ----------

    @Test
    fun `standalone action line is removed`() {
        assertEquals(
            listOf("困了(笑)", "晚安"),
            WhaleReplyPipeline.split("困了(笑)\n（困）\n晚安")
        )
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("（困）"))
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("  (揉眼睛)  "))
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("( )"))
        // 行内夹带动作/括号，不是「单独一行」，不删
        assertEquals(listOf("（困）晚安"), WhaleReplyPipeline.split("（困）晚安"))
        assertEquals(listOf("(a)b(c)"), WhaleReplyPipeline.split("(a)b(c)"))
        assertEquals(listOf("（(困)）"), WhaleReplyPipeline.split("（(困)）"))
    }

    @Test
    fun `action line only matches one to twelve characters`() {
        // {1,12}：12 个字删，13 个字不删
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("（一二三四五六七八九十一二）"))
        val thirteen = "（一二三四五六七八九十一二三）"
        assertEquals(listOf(thirteen), WhaleReplyPipeline.split(thirteen))
    }

    // ---------- HELP_LINE ----------

    @Test
    fun `help guidance lines are removed`() {
        assertEquals(
            listOf("我可以帮你写代码", "今天天气不错"),
            WhaleReplyPipeline.split("我可以帮你写代码\n输入 /help 查看帮助\n今天天气不错")
        )
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("/help"))
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("有什么我可以帮你的吗"))
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("有什么可以帮你"))
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("希望这对你有所帮助"))
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("这是斜杠命令"))
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("输入 /reset 可以重置"))
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("输入/reset"))
        // 逐字照抄 Hermes 的后果：那里写的是「对你有」不是「对您有」，所以这句不匹配
        assertEquals(listOf("希望以上对您有所帮助"), WhaleReplyPipeline.split("希望以上对您有所帮助"))
        // 普通句子不受影响
        assertEquals(listOf("今天吃什么"), WhaleReplyPipeline.split("今天吃什么"))
    }

    // ---------- 「／」换行 ----------

    @Test
    fun `fullwidth slash becomes a line break`() {
        assertEquals(listOf("好耶", "太棒了"), WhaleReplyPipeline.split("好耶／太棒了"))
        assertEquals("今天\n明天", WhaleReplyPipeline.clean("今天 ／ 明天"))
        assertEquals(listOf("哈哈", "笑死"), WhaleReplyPipeline.split("哈哈／／笑死"))
        // 中英混排也照切
        assertEquals(listOf("yes", "no"), WhaleReplyPipeline.split("yes ／ no"))
    }

    // ---------- 静默 ----------

    @Test
    fun `silent marker at the first line is recognized`() {
        assertTrue(WhaleReplyPipeline.isSilent("[SILENT]"))
        assertTrue(WhaleReplyPipeline.isSilent("  [SILENT]  "))
        assertTrue(WhaleReplyPipeline.isSilent("[SILENT] 我今天不想说话"))
        assertTrue(WhaleReplyPipeline.isSilent("[SILENT]\n你好"))
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("[SILENT]"))
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("[SILENT] 我今天不想说话"))
    }

    @Test
    fun `silent marker at the last line is recognized`() {
        assertTrue(WhaleReplyPipeline.isSilent("晚安\n[SILENT]"))
        assertTrue(WhaleReplyPipeline.isSilent("你好\n\n[SILENT]\n\n"))
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("晚安\n[SILENT]"))
    }

    @Test
    fun `silent marker in the middle is not silent`() {
        assertFalse(WhaleReplyPipeline.isSilent("早安\n[SILENT]\n晚安"))
        assertEquals(listOf("早安", "[SILENT]", "晚安"), WhaleReplyPipeline.split("早安\n[SILENT]\n晚安"))
    }

    @Test
    fun `ordinary text is not silent`() {
        assertFalse(WhaleReplyPipeline.isSilent("你好"))
        assertFalse(WhaleReplyPipeline.isSilent(""))
        assertFalse(WhaleReplyPipeline.isSilent("   \n\t  "))
    }

    // ---------- 中英混排、超长单行、空白 ----------

    @Test
    fun `mixed chinese and english`() {
        assertEquals(
            listOf("Hello 你好", "今天 fine，你呢？"),
            WhaleReplyPipeline.split("Hello 你好😊\n今天 fine，你呢？")
        )
        assertEquals(listOf("ok 好的"), WhaleReplyPipeline.split("ok 好的"))
    }

    @Test
    fun `very long single line stays one bubble`() {
        val longLine = "啊".repeat(5000)
        val result = WhaleReplyPipeline.split(longLine + "😄")
        assertEquals(1, result.size)
        assertEquals(longLine, result[0])
    }

    @Test
    fun `blank only text cleans to empty`() {
        assertEquals("", WhaleReplyPipeline.clean("   \n\t\n  "))
        assertEquals("", WhaleReplyPipeline.clean(""))
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("   \n\t\n  "))
    }

    @Test
    fun `blank lines are collapsed and dropped`() {
        assertEquals("好耶\n太棒了", WhaleReplyPipeline.clean("好耶\n\n\n\n太棒了"))
        assertEquals(listOf("好耶", "太棒了"), WhaleReplyPipeline.split("好耶\n\n\n\n太棒了"))
    }

    // ---------- 全流程 / 流式显示 ----------

    @Test
    fun `whole pipeline cleans a realistic reply`() {
        val raw = "（困）\n好耶😄／太棒了\n输入 /help 看命令\n\n\n晚安✨  "
        assertEquals("好耶\n太棒了\n晚安", WhaleReplyPipeline.clean(raw))
        assertEquals(listOf("好耶", "太棒了", "晚安"), WhaleReplyPipeline.split(raw))
    }

    @Test
    fun `clean for display applies the same cleanup without splitting`() {
        assertEquals(
            WhaleReplyPipeline.clean("好耶😄／太棒了"),
            WhaleReplyPipeline.cleanForDisplay("好耶😄／太棒了")
        )
        assertEquals("好耶\n太棒了", WhaleReplyPipeline.cleanForDisplay("好耶😄／太棒了"))
        assertEquals("", WhaleReplyPipeline.cleanForDisplay("（困）"))
    }

    @Test
    fun `clean for display hides the unfinished trailing line`() {
        // 没闭合的括号动作 / 只追出一半的 [SILENT]：最后一行连同前面的换行一起藏掉
        assertEquals("你好", WhaleReplyPipeline.cleanForDisplay("你好\n（还在"))
        assertEquals("你好", WhaleReplyPipeline.cleanForDisplay("你好\n[SI"))
        assertEquals("你好", WhaleReplyPipeline.cleanForDisplay("你好\n["))
        assertEquals("你好", WhaleReplyPipeline.cleanForDisplay("你好\n("))
        assertEquals("", WhaleReplyPipeline.cleanForDisplay("（还在"))
        assertEquals("", WhaleReplyPipeline.cleanForDisplay("[SI"))
        // 已经写完整的括号动作行本来就该被 ACTION_LINE 删掉
        assertEquals("你好", WhaleReplyPipeline.cleanForDisplay("你好\n(笑)"))
        assertEquals("你好", WhaleReplyPipeline.cleanForDisplay("你好\n[SILENT]"))
        // 最后一行正常就不动
        assertEquals("你好\n晚安", WhaleReplyPipeline.cleanForDisplay("你好\n晚安"))
        // 没写完的行不在最后一行就不动
        assertEquals("（还在\n你好", WhaleReplyPipeline.cleanForDisplay("（还在\n你好"))
    }

    @Test
    fun `debug fake reply cases from the task book`() {
        // 三行 -> 3 个气泡
        assertEquals(
            listOf("消息1", "消息2", "消息3"),
            WhaleReplyPipeline.split("消息1\n消息2\n消息3")
        )
        // [SILENT] -> 无气泡
        assertEquals(emptyList<String>(), WhaleReplyPipeline.split("[SILENT]"))
        // 整行括号动作被去掉 -> 2 行
        assertEquals(listOf("消息1(笑)", "消息2"), WhaleReplyPipeline.split("消息1(笑)\n（动作）\n消息2"))
        // 去掉 emoji
        assertEquals(listOf("消息"), WhaleReplyPipeline.split("消息😄"))
    }
}
