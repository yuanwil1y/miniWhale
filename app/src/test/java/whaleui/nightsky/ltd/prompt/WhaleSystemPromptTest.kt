package whaleui.nightsky.ltd.prompt

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.domain.settings.PersonaDocument
import whaleui.nightsky.ltd.domain.settings.PersonaImportResult
import whaleui.nightsky.ltd.domain.settings.PersonaKind
import whaleui.nightsky.ltd.domain.settings.PersonaStore
import whaleui.nightsky.ltd.domain.memory.Memory
import whaleui.nightsky.ltd.domain.memory.MemoryCategory
import whaleui.nightsky.ltd.domain.memory.MemoryOrigin
import whaleui.nightsky.ltd.domain.memory.MemoryRepository

/**
 * 系统提示词拼装顺序与截断（任务书 4.2 / 4.5）。
 *
 * 用假的 assets reader / PersonaStore / MemoryRepository，纯 JVM 跑，不需要 Android Context。
 */
class WhaleSystemPromptTest {

    private val head =
        "「{name}」技能你一直带在身上，下面就是它的内容，不用再打开它的 SKILL.md。需要细节的时候，再用 use_skill 打开它目录里的其他文件。"

    private fun assets(skills: Map<String, String>): (String) -> String = { path ->
        when {
            path == "whale/prompts/skill_section_head.txt" -> head
            path.startsWith("builtin_skills/") && path.endsWith("/SKILL.md") -> {
                val name = path.removePrefix("builtin_skills/").removeSuffix("/SKILL.md")
                skills[name].orEmpty()
            }

            else -> ""
        }
    }

    private class FakePersona(private val texts: Map<PersonaKind, String>) : PersonaStore {
        override fun observe(kind: PersonaKind): Flow<PersonaDocument?> =
            flowOf(texts[kind]?.let { PersonaDocument(kind, it.length.toLong(), 0L) })

        override suspend fun import(kind: PersonaKind, uri: Uri): PersonaImportResult =
            PersonaImportResult.Failure(whaleui.nightsky.ltd.domain.settings.PersonaImportError.NotReadable)

        override suspend fun readText(kind: PersonaKind): String? = texts[kind]

        override suspend fun remove(kind: PersonaKind) = Unit

        override suspend fun restore(kind: PersonaKind, text: String): PersonaDocument? = null
    }

    private class FakeMemory(private val items: List<Memory>) : MemoryRepository {
        override fun observeAll(): Flow<List<Memory>> = flowOf(items)
        override suspend fun add(content: String, category: MemoryCategory, origin: MemoryOrigin): Memory =
            throw UnsupportedOperationException()

        override suspend fun update(id: String, content: String, category: MemoryCategory): Memory? = null
        override suspend fun delete(id: String) = Unit
        override suspend fun restore(memory: Memory, index: Int) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun exportAll(): String = ""
    }

    private fun memory(id: String, content: String, category: MemoryCategory, updatedAt: Long) = Memory(
        id = id,
        content = content,
        category = category,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        origin = MemoryOrigin.UserAdded,
    )

    private fun build(
        soul: String?,
        memories: List<Memory> = emptyList(),
        skills: Map<String, String> = mapOf(
            "goutoujunshi" to "---\nname: goutoujunshi\n---\n狗头军师正文",
            "voice-message" to "---\nname: voice-message\n---\n发语音正文",
            "sticker" to "---\nname: sticker\n---\n发表情包正文",
        ),
        stickers: whaleui.nightsky.ltd.sticker.StickerAvailability? = null,
    ): String = runBlocking {
        WhaleSystemPrompt(
            readAsset = assets(skills),
            personaStore = FakePersona(soul?.let { mapOf(PersonaKind.Soul to it) } ?: emptyMap()),
            memoryRepository = FakeMemory(memories),
            stickerAvailability = stickers,
        ).build(assistant = Assistant())
    }

    /** 造一个「库里有这些表情」的可用性快照（round5 3.4）。 */
    private fun stickersAvailable(vararg emotions: List<String>): whaleui.nightsky.ltd.sticker.StickerAvailability =
        runBlocking {
            val repository = whaleui.nightsky.ltd.sticker.InMemoryStickerRepository()
            repository.savePack(
                whaleui.nightsky.ltd.sticker.StickerPack(
                    id = "01",
                    name = "冬季约会",
                    titleOriginal = "冬季约会",
                    characters = emptyList(),
                    animated = true,
                    hasSoundOriginally = false,
                    source = "测试",
                    licenseNote = "仅个人使用",
                    sortOrder = 0,
                ),
                emotions.mapIndexed { index, words ->
                    whaleui.nightsky.ltd.sticker.Sticker(
                        id = "01-$index",
                        packId = "01",
                        filePath = "stickers/01/$index.webp",
                        thumbPath = "stickers/_thumbs/01-$index.png",
                        description = "测试",
                        detail = "测试的详细说明",
                        emotions = words,
                    )
                },
            )
            whaleui.nightsky.ltd.sticker.StickerAvailability(repository, ttlMs = 0L)
        }

    @Test
    fun `soul comes first`() {
        val result = build("人设文本。")
        assertTrue(result.startsWith("人设文本。"))
    }

    @Test
    fun `missing soul falls back to the placeholder`() {
        val result = build(null)
        assertTrue(result.startsWith("（还没有导入人设）"))
    }

    @Test
    fun `order is soul then memory then goutoujunshi then voice-message`() {
        val result = build(
            soul = "SOUL正文",
            memories = listOf(memory("1", "测试记忆内容", MemoryCategory.AboutYou, 1L)),
        )
        val soulAt = result.indexOf("SOUL正文")
        val memoryAt = result.indexOf("关于对方")
        val gdtAt = result.indexOf("狗头军师正文")
        val voiceAt = result.indexOf("发语音正文")
        assertTrue("soul < memory", soulAt in 0 until memoryAt)
        assertTrue("memory < goutoujunshi", memoryAt in 0 until gdtAt)
        assertTrue("goutoujunshi < voice-message", gdtAt in 0 until voiceAt)
    }

    @Test
    fun `resident skill sections drop the frontmatter and use the hermes head`() {
        val result = build("SOUL")
        assertTrue(result.contains("「goutoujunshi」技能你一直带在身上"))
        assertTrue(result.contains("「voice-message」技能你一直带在身上"))
        assertFalse(result.contains("name: goutoujunshi"))
    }

    @Test
    fun `memory block is omitted when there are no memories`() {
        val result = build("SOUL")
        assertFalse(result.contains("# 关于对方"))
    }

    @Test
    fun `resident section is truncated to 3900 chars`() {
        val long = "长".repeat(5000)
        val result = build(
            soul = "SOUL",
            skills = mapOf("goutoujunshi" to long, "voice-message" to long),
        )
        // 每段正文被截到 3900（含引导语），所以「长」的总数接近 2*(3900 - 引导语长度)，远小于 5000*2
        val longChars = result.count { it == '长' }
        assertTrue("longChars=$longChars should be <= 7800", longChars <= 3900 * 2)
        assertTrue("longChars=$longChars should be > 7000", longChars > 7000)
    }

    @Test
    fun `missing skill assets are skipped without breaking the prompt`() {
        val result = build(soul = "SOUL", skills = emptyMap())
        assertEquals("SOUL", result.trim())
    }
    @Test
    fun `home city is appended to the memory section only when configured`() {
        val memories = listOf(memory("fake-about", "假的记忆内容", MemoryCategory.AboutYou, 1L))

        // 没填常住城市：不出现这一行
        whaleui.nightsky.ltd.whale.WhaleHomeCity.update("")
        val without = build("假的 SOUL", memories)
        assertFalse(without.contains("常住在"))

        // 填了：出现在记忆段落末尾（在第一个技能段之前）
        whaleui.nightsky.ltd.whale.WhaleHomeCity.update("北京")
        val with = build("假的 SOUL", memories)
        assertTrue(with, with.contains("对方常住在：北京"))
        val memoryIndex = with.indexOf("假的记忆内容")
        val cityIndex = with.indexOf("对方常住在：北京")
        val skillIndex = with.indexOf("狗头军师正文")
        assertTrue(memoryIndex in 0 until cityIndex && cityIndex < skillIndex)

        whaleui.nightsky.ltd.whale.WhaleHomeCity.update("")
    }

    @Test
    fun `home city line appears even without any memory`() {
        whaleui.nightsky.ltd.whale.WhaleHomeCity.update("北京")
        val result = build("假的 SOUL")
        whaleui.nightsky.ltd.whale.WhaleHomeCity.update("")

        assertTrue(result, result.startsWith("假的 SOUL\n\n对方常住在：北京\n\n"))
        assertFalse(result.contains("# 关于对方"))
    }

    @Test
    fun `skill head and body are on separate lines so the body heading stays a heading`() {
        val result = build(
            soul = "SOUL",
            skills = mapOf("goutoujunshi" to "---\nname: goutoujunshi\n---\n# 狗头军师\n正文"),
        )
        assertTrue(result, result.contains("打开它目录里的其他文件。\n# 狗头军师"))
    }

    // ---------------------------------------------------------- 表情（round5 3.4）

    @Test
    fun `sticker skill and emotion line appear only when stickers are available`() {
        val without = build("SOUL")
        assertFalse("没有表情时不该注入 sticker 技能", without.contains("发表情包正文"))
        assertFalse("没有表情时不该有情绪分类行", without.contains("表情包的情绪分类"))

        val with = build("SOUL", stickers = stickersAvailable(listOf("抱抱", "开心"), listOf("委屈")))
        assertTrue("有表情时要注入 sticker 技能", with.contains("发表情包正文"))
        // 情绪按 42 词表的顺序：开心 → 抱抱 → 委屈
        assertTrue(with, with.contains("表情包的情绪分类:开心、抱抱、委屈"))
    }

    @Test
    fun `sticker section comes after the other resident skills and before the emotion line`() {
        val result = build("SOUL", stickers = stickersAvailable(listOf("开心")))
        val voiceAt = result.indexOf("发语音正文")
        val stickerAt = result.indexOf("发表情包正文")
        val emotionAt = result.indexOf("表情包的情绪分类")
        assertTrue("voice-message < sticker", voiceAt in 0 until stickerAt)
        assertTrue("sticker < emotion line", stickerAt in 0 until emotionAt)
    }

    @Test
    fun `stickers without any known emotion fall back to the keyword hint`() {
        val result = build("SOUL", stickers = stickersAvailable(emptyList()))
        assertTrue(result.contains("表情包的情绪分类:（这批表情没标情绪，按关键词找）"))
    }
}