package whaleui.nightsky.ltd.prompt

import android.content.Context
import me.rerere.rikkahub.data.model.Assistant
import whaleui.nightsky.ltd.domain.settings.PersonaKind
import whaleui.nightsky.ltd.domain.settings.PersonaStore
import whaleui.nightsky.ltd.memory.WhaleMemoryPrompt
import whaleui.nightsky.ltd.domain.memory.MemoryRepository
import kotlinx.coroutines.flow.first

/**
 * 系统提示词的拼装（任务书 4.2）。
 *
 * 顺序固定：
 * 1. SOUL 原文（用户导入的 `soul.md`；没导入时用一句占位，并在设置页提示）
 * 2. 记忆（[WhaleMemoryPrompt.build]，有 3000 字上限）+「对方常住在：…」（填了城市就有，和有没有记忆无关）
 * 3. `goutoujunshi` 常驻正文（Hermes 的引导语 + 去掉 frontmatter 的 SKILL.md，≤3900 字）
 * 4. `voice-message` 常驻正文（同上）
 *
 * 之后才是 RikkaHub 原生拼接的「工具 systemPrompt（Skills 清单）」，我们不动它。
 *
 * 这一段在同一会话里是稳定的（只有导入新人设或记忆变化时才变），有利于命中提示词缓存；
 * 每轮会变的东西（报时、提醒、防复读）在 [WhaleGuard] 里，走「最后一条 user 消息末尾追加」。
 *
 * 拼装逻辑放在 `ChatRequestFactory.buildStep()` 的 system 文本处，仅当 assistant 是小鲸鱼时生效。
 */
class WhaleSystemPrompt(
    private val readAsset: (String) -> String,
    private val personaStore: PersonaStore,
    private val memoryRepository: MemoryRepository,
    /** 表情库（round5）：没有可用表情时，`sticker` 技能和情绪分类行都不注入（任务书 3.4）。 */
    private val stickerAvailability: whaleui.nightsky.ltd.sticker.StickerAvailability? = null,
    /** 设置里的常住城市；默认读进程内缓存 [whaleui.nightsky.ltd.whale.WhaleHomeCity]。 */
    private val homeCity: () -> String = { whaleui.nightsky.ltd.whale.WhaleHomeCity.value },
) {

    /** 生产用的构造：从 APK 的 assets 读。 */
    constructor(
        context: Context,
        personaStore: PersonaStore,
        memoryRepository: MemoryRepository,
        stickerAvailability: whaleui.nightsky.ltd.sticker.StickerAvailability? = null,
    ) : this(
        readAsset = { path ->
            runCatching {
                context.assets.open(path).bufferedReader().use { it.readText() }
            }.getOrDefault("")
        },
        personaStore = personaStore,
        memoryRepository = memoryRepository,
        stickerAvailability = stickerAvailability,
    )

    suspend fun build(assistant: Assistant? = null): String {
        val soul = personaStore.readText(PersonaKind.Soul)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: SOUL_PLACEHOLDER

        val memories = memoryRepository.observeAll().first()
        val memoryBlock = WhaleMemoryPrompt.build(memories) { line ->
            android.util.Log.i(TAG, line)
        }

        // 有可用表情才注入 sticker 技能 + 情绪分类行（任务书 3.4 第 2 条）
        val stickerStatus = stickerAvailability?.get() ?: whaleui.nightsky.ltd.sticker.StickerAvailability.Status.NONE
        val residents = if (stickerStatus.available) {
            RESIDENT_SKILLS + STICKER_SKILL
        } else {
            RESIDENT_SKILLS
        }

        // 记录每段的起点和长度，仅 debug 打日志（只有数字，没有正文），用来核对拼装顺序
        val sections = LinkedHashMap<String, Pair<Int, Int>>()
        val result = buildString {
            val soulStart = length
            append(soul)
            sections["soul"] = soulStart to (length - soulStart)

            // 决定 round4 4.3：常住城市填了才加这一行，让她聊天时也知道对方在哪。
            // 提示词第 1 批：这一行独立于记忆段，没有任何记忆时也输出。
            val cityLine = cityLine(homeCity())
            if (memoryBlock.isNotBlank() || cityLine != null) {
                appendLine()
                appendLine()
                val start = length
                if (memoryBlock.isNotBlank()) append(memoryBlock)
                if (cityLine != null) {
                    if (memoryBlock.isNotBlank()) appendLine()
                    append(cityLine)
                }
                sections["memory"] = start to (length - start)
            }
            residents.forEach { skill ->
                val section = residentSection(skill)
                if (section.isNotBlank()) {
                    appendLine()
                    appendLine()
                    val start = length
                    append(section)
                    sections[skill] = start to (length - start)
                }
            }
            if (stickerStatus.available) {
                appendLine()
                appendLine()
                val start = length
                append(stickerEmotionLine(stickerStatus.emotions))
                sections["sticker-emotions"] = start to (length - start)
            }
        }

        if (me.rerere.rikkahub.BuildConfig.DEBUG) {
            val summary = sections.entries.joinToString(" ") { (name, at) -> "$name@${at.first}+${at.second}" }
            // runCatching：JVM 单测里 android.util.Log 是 stub，会抛 not mocked；日志不该影响拼装结果
            runCatching {
                android.util.Log.i(
                    TAG,
                    "system prompt total=${result.length} placeholder=${soul == SOUL_PLACEHOLDER} sections: $summary",
                )
            }
        }
        return result
    }

    /** 照抄 Hermes `_make_section`：引导语 + 去掉 frontmatter 的正文，整段截到 3900 字。 */
    private fun residentSection(name: String): String {
        val raw = readAsset("builtin_skills/$name/SKILL.md").takeIf { it.isNotBlank() } ?: return ""

        val body = if (raw.startsWith("---")) {
            // frontmatter 形如 ---\n...\n---\n正文
            raw.split("---", limit = 3).takeIf { it.size == 3 }?.get(2) ?: raw
        } else {
            raw
        }
        // 引导语和正文之间一定换行：否则正文的「# 标题」会接在引导语同一行，Markdown 标题失效
        val head = readPrompt("skill_section_head.txt").replace("{name}", name).trimEnd()
        val joined = if (head.isEmpty()) body.trim() else head + "\n" + body.trim()
        return joined.take(MAX_SECTION_CHARS)
    }

    /** 「对方常住在：{city}」；没填城市返回 null。 */
    private fun cityLine(city: String): String? =
        city.trim().takeIf { it.isNotEmpty() }?.let { "对方常住在：$it" }

    private fun readPrompt(name: String): String = readAsset("whale/prompts/$name")

    /**
     * 「表情包的情绪分类:<…>」这一行（任务书 3.4 第 2 条），情绪用顿号隔开。
     *
     * 万一这一批表情都没标进 42 词表（导入时只记警告），给一句兜底说明，不留一个空冒号。
     */
    private fun stickerEmotionLine(emotions: List<String>): String =
        if (emotions.isEmpty()) {
            "表情包的情绪分类:（这批表情没标情绪，按关键词找）"
        } else {
            "表情包的情绪分类:" + emotions.joinToString("、")
        }

    private companion object {
        const val TAG = "WhaleSystemPrompt"
        const val MAX_SECTION_CHARS = 3900

        /** 人设没导入时的占位（任务书 4.2）。 */
        const val SOUL_PLACEHOLDER = "（还没有导入人设）"

        /** 正文每次请求都拼进系统提示词的技能（决策 10）。 */
        val RESIDENT_SKILLS = listOf("goutoujunshi", "voice-message")

        /** 有可用表情时才常驻的表情技能（round5 3.4）。 */
        const val STICKER_SKILL = "sticker"
    }
}
