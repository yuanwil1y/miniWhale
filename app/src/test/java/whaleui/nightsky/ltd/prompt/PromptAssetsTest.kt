package whaleui.nightsky.ltd.prompt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词审阅（2026-10-27，第 1–10 批）定下的、对所有内置提示词都成立的规则。
 *
 * 只检查我们自己写的：`assets/whale/prompts/` 和常驻 / 启用的技能 SKILL.md（不含上游原版资料和 skill-creator）。
 */
class PromptAssetsTest {

    private val promptDir = File("src/main/assets/whale/prompts")
    private val skills = listOf("goutoujunshi", "voice-message", "sticker", "my-day", "drawing")
    private fun skill(name: String) = File("src/main/assets/builtin_skills/$name/SKILL.md").readText()

    private val allTexts: Map<String, String> by lazy {
        promptDir.listFiles()!!.filter { it.name.endsWith(".txt") }.associate { it.name to it.readText() } +
            skills.associateWith { skill(it) }
    }

    /** 「其他」「为他人」不是在说人。 */
    private fun String.withoutNonPronouns() = replace("其他", "").replace("为他人", "").replace("他人", "")

    @Test
    fun `no gendered pronoun for her or the user`() {
        allTexts.forEach { (name, text) ->
            val cleaned = text.withoutNonPronouns()
            assertFalse("$name 里还有「他」", cleaned.contains("他"))
            assertFalse("$name 里还有「她」", cleaned.contains("她"))
            assertFalse("$name 里有性别预设", cleaned.contains("女生"))
        }
    }

    @Test
    fun `no owner specific content or personality prescriptions`() {
        val banned = listOf("个人主页", "嘴硬", "软一点", "委屈、嘴上埋怨", "才、才不是", "小鲸鱼", "语速可以自己调", "8 点半", "23 点半")
        allTexts.forEach { (name, text) ->
            banned.forEach { word -> assertFalse("$name 里还有「$word」", text.contains(word)) }
        }
    }

    @Test
    fun `voice and stickers are neither pushed nor suppressed`() {
        val voice = skill("voice-message")
        val sticker = skill("sticker")
        listOf("多发", "顺手就发", "一天几条就够", "随便发").forEach {
            assertFalse("voice-message: $it", voice.contains(it))
            assertFalse("voice_line: $it", allTexts.getValue("voice_line.txt").contains(it))
        }
        listOf("别刷屏", "别连着好几次都发").forEach {
            assertFalse("sticker: $it", sticker.contains(it))
            assertFalse("sticker_line: $it", allTexts.getValue("sticker_line.txt").contains(it))
        }
        // 额度快没时的规则还在；一次最多一张、别老用同一张还在
        assertTrue(voice.contains("快没了就先不发"))
        assertTrue(sticker.contains("一次最多发一张"))
        assertTrue(sticker.contains("别老用同一张"))
    }

    @Test
    fun `voice skill teaches all nineteen MiniMax tags`() {
        val voice = skill("voice-message")
        listOf(
            "laughs", "chuckle", "coughs", "clear-throat", "groans", "breath", "pant", "inhale", "exhale", "gasps",
            "sniffs", "sighs", "snorts", "burps", "lip-smacking", "humming", "hissing", "emm", "sneezes",
        ).forEach { assertTrue(it, voice.contains("($it)")) }
    }

    @Test
    fun `drawing is not promoted`() {
        val drawing = skill("drawing")
        assertFalse(drawing.contains("别太频繁"))
        assertFalse(drawing.contains("不稀罕"))
        assertFalse(skill("my-day").contains("drawing"))
    }

    @Test
    fun `structure fixes`() {
        assertFalse("gap_units.txt 没人读，应删掉", File(promptDir, "gap_units.txt").exists())
        assertTrue("skill_section_head 要以换行结尾", allTexts.getValue("skill_section_head.txt").endsWith("\n"))
        assertTrue(allTexts.getValue("cron_frame.txt").contains("下面那段闹钟说明"))
        assertTrue(allTexts.getValue("memory_tidy_night.txt").contains("带（对方让记的）标记的不能改也不能删，想补充就另记一条"))
        assertFalse(allTexts.getValue("sticker_line.txt").contains("?"))
        assertFalse(allTexts.getValue("sticker_line.txt").contains(","))
        val gou = skill("goutoujunshi")
        assertTrue(gou.contains("path 填下面列的相对路径"))
        assertTrue(gou.contains("说到对方喜欢的人"))
        assertTrue("恋爱前提保留", gou.contains("你在追对方") || gou.contains("你怎么追对方"))
        assertTrue(skill("my-day").contains("date"))
    }

    @Test
    fun `goutoujunshi frontmatter says it is always carried`() {
        val frontmatter = skill("goutoujunshi").split("---")[1]
        assertTrue(frontmatter, frontmatter.contains("description: 你一直带着它，要翻 references 里的资料时用 use_skill 打开。"))
        assertEquals(1, Regex("^name: goutoujunshi$", RegexOption.MULTILINE).findAll(frontmatter).count())
    }
}
