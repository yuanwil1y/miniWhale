package whaleui.nightsky.ltd.tools

import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import whaleui.nightsky.ltd.data.RoomDiaryRepository
import whaleui.nightsky.ltd.memory.MemoryTag
import whaleui.nightsky.ltd.memory.WhaleMemoryPrompt
import whaleui.nightsky.ltd.domain.memory.Memory
import whaleui.nightsky.ltd.domain.memory.MemoryCategory
import whaleui.nightsky.ltd.domain.memory.MemoryOrigin
import whaleui.nightsky.ltd.domain.memory.MemoryRepository
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 小鲸鱼自己的三个工具：`write_diary` / `read_diary` / `remember`（任务书 3.5）。
 *
 * 通过原生扩展点接入：`ChatToolFactory.createTools` 里，**仅当 assistant 是小鲸鱼时**才加入。
 * 工具名、参数 schema、错误文案逐字照抄 Hermes `plugins/daily_life/__init__.py` 的 `register`。
 *
 * 三个工具都只返回一段文字（`UIMessagePart.Text`），内容和 Hermes 一样是给模型看的 JSON 文本。
 */
class WhaleTools private constructor(
    private val memoryRepository: MemoryRepository,
    private val diary: () -> RoomDiaryRepository,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) {

    constructor(memoryRepository: MemoryRepository, diaryRepository: RoomDiaryRepository) :
        this(memoryRepository, { diaryRepository }, Unit)

    private val diaryRepository: RoomDiaryRepository get() = diary()

    internal object Testing {
        /** 单测用：只测记忆工具，日记仓库（要真数据库）一碰就报错。 */
        fun memoryOnly(memoryRepository: MemoryRepository): WhaleTools =
            WhaleTools(memoryRepository, { error("这个测试里没有日记仓库") }, Unit)
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun create(): List<Tool> = listOf(
        writeDiaryTool(),
        readDiaryTool(),
        rememberTool(),
        updateMemoryTool(),
        forgetMemoryTool(),
    )

    // ------------------------------------------------------------ write_diary

    private fun writeDiaryTool() = Tool(
        name = "write_diary",
        description = "在今天的日记里写一段，自动带上时间。写你自己今天干了什么、看到了什么、心情怎么样。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "text",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("maxLength", JsonPrimitive(1500))
                            put("description", JsonPrimitive("这一段要写的内容，用你自己的口气，几句话就够"))
                        },
                    )
                },
                required = listOf("text"),
            )
        },
        execute = { args ->
            listOf(UIMessagePart.Text(writeDiary(textArgument(args))))
        },
    )

    internal suspend fun writeDiary(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return errorJson("日记是空的")
        val clipped = trimmed.take(MAX_SEGMENT_CHARS)
        val today = today()
        val old = todayEntry()?.content.orEmpty()
        if (old.length + clipped.length > MAX_CHARS_PER_DAY) {
            return errorJson("今天的日记本写满了，明天再写吧")
        }
        val time = nowTime()
        diaryRepository.appendSegment(today, time, clipped)
        return buildJsonObject {
            put("saved", JsonPrimitive(true))
            put("date", JsonPrimitive(today.toString()))
            put("time", JsonPrimitive(time))
        }.toString()
    }

    // ------------------------------------------------------------ read_diary

    private fun readDiaryTool() = Tool(
        name = "read_diary",
        description = "翻自己的日记。不填参数就是今天；days 是往前看几天（最多 7）；date 指定某一天，格式 2026-10-24。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "days",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(1))
                            put("maximum", JsonPrimitive(7))
                            put("description", JsonPrimitive("往前看几天（含今天），1-7，默认 1"))
                        },
                    )
                    put(
                        "date",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("只看某一天，格式 2026-10-24；填了就不看 days"))
                        },
                    )
                },
                required = emptyList(),
            )
        },
        execute = { args ->
            listOf(UIMessagePart.Text(readDiary(args.jsonObject)))
        },
    )

    internal suspend fun readDiary(args: JsonObject): String {
        val date = args["date"]?.jsonPrimitive?.content?.trim().orEmpty()
        val days = args["days"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 7) ?: 1
        val dates: List<LocalDate> = if (date.isNotEmpty()) {
            val parsed = runCatching { LocalDate.parse(date) }.getOrNull()
                ?: return errorJson("日期格式是 2026-10-24 这样")
            listOf(parsed)
        } else {
            val now = today()
            (days - 1 downTo 0).map { now.minusDays(it.toLong()) }
        }
        val entries = diaryRepository.observeAll().first()
            .filter { it.date in dates }
            .sortedBy { it.date }
        if (entries.isEmpty()) {
            return buildJsonObject {
                put("entries", buildJsonArray { })
                put("note", JsonPrimitive("这几天还没写日记"))
            }.toString()
        }
        return buildJsonObject {
            put(
                "entries",
                buildJsonArray {
                    entries.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("date", JsonPrimitive(entry.date.toString()))
                                put("text", JsonPrimitive(entry.content.trim().takeLast(MAX_READ_CHARS_PER_DAY)))
                            },
                        )
                    }
                },
            )
        }.toString()
    }

    // ------------------------------------------------------------ remember

    private fun rememberTool() = Tool(
        name = "remember",
        description = "记住一件事。category 三选一，默认「关于对方」：" +
            "「关于对方」= 对方的喜好、计划、重要的日子；" +
            "「约定」= 你们约好的事；" +
            "「你自己的」= 你们一起聊过、经历过的事，和你当时的感受。" +
            "content 一句话说清楚，不超过 200 字。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "content",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("maxLength", JsonPrimitive(MAX_MEMORY_CHARS))
                            put("description", JsonPrimitive("要记的事，一句话说清楚"))
                        },
                    )
                    put("category", categorySchema())
                },
                required = listOf("content"),
            )
        },
        execute = { args ->
            listOf(UIMessagePart.Text(remember(args.jsonObject)))
        },
    )

    internal suspend fun remember(args: JsonObject): String {
        val content = args["content"]?.jsonPrimitive?.content?.trim()
            ?.take(MAX_MEMORY_CHARS)
            ?.takeIf { it.isNotEmpty() }
            ?: return errorJson("内容不能为空")
        val category = WhaleMemoryPrompt.parseCategory(args["category"]?.jsonPrimitive?.content)
            ?: MemoryCategory.AboutYou
        val existing: List<Memory> = memoryRepository.observeAll().first()
        if (existing.any { it.content.trim() == content }) {
            return buildJsonObject {
                put("saved", JsonPrimitive(false))
                put("note", JsonPrimitive("这条已经记过了"))
            }.toString()
        }
        val memory = memoryRepository.add(
            content = content,
            category = category,
            origin = MemoryOrigin.HerRecorded,
        )
        return buildJsonObject {
            put("saved", JsonPrimitive(true))
            // 和记忆段里的编号一致（m1a2b3c），不是原始 id
            put("id", JsonPrimitive("m" + MemoryTag.tagOf(memory.id)))
            put("category", JsonPrimitive(WhaleMemoryPrompt.promptLabel(category)))
        }.toString()
    }

    // ------------------------------------------------------ update_memory / forget_memory

    private fun updateMemoryTool() = Tool(
        name = "update_memory",
        description = "改一条你自己记下的记忆（合并重复、修正记错的）。id 用记忆前面的编号，比如 m1a2b3c。" +
            LOCK_RULE,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("记忆前面的编号，例如 m1a2b3c"))
                    })
                    put("content", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("maxLength", JsonPrimitive(MAX_MEMORY_CHARS))
                        put("description", JsonPrimitive("改好之后的整条内容，一句话说清楚"))
                    })
                    put("category", categorySchema(keepHint = true))
                },
                required = listOf("id", "content"),
            )
        },
        execute = { args -> listOf(UIMessagePart.Text(updateMemory(args.jsonObject))) },
    )

    private fun forgetMemoryTool() = Tool(
        name = "forget_memory",
        description = "删掉一条你自己记下的记忆（过时的、重复的）。id 用记忆前面的编号。$LOCK_RULE",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("记忆前面的编号，例如 m1a2b3c"))
                    })
                },
                required = listOf("id"),
            )
        },
        execute = { args -> listOf(UIMessagePart.Text(forgetMemory(args.jsonObject))) },
    )

    internal suspend fun updateMemory(args: JsonObject): String {
        val memory = resolveMemory(args["id"]?.jsonPrimitive?.content.orEmpty()) ?: return errorJson(NOT_FOUND)
        if (memory.origin != MemoryOrigin.HerRecorded) return errorJson(USER_ADDED_LOCKED)

        val content = args["content"]?.jsonPrimitive?.content?.trim()
            ?.take(MAX_MEMORY_CHARS)
            ?.takeIf { it.isNotEmpty() }
            ?: return errorJson("内容不能为空")
        val category = WhaleMemoryPrompt.parseCategory(args["category"]?.jsonPrimitive?.content)
            ?: memory.category
        val updated = memoryRepository.update(memory.id, content, category) ?: return errorJson(NOT_FOUND)
        return buildJsonObject {
            put("updated", JsonPrimitive(true))
            put("id", JsonPrimitive(MemoryTag.labelOf(updated.id)))
        }.toString()
    }

    internal suspend fun forgetMemory(args: JsonObject): String {
        val memory = resolveMemory(args["id"]?.jsonPrimitive?.content.orEmpty()) ?: return errorJson(NOT_FOUND)
        if (memory.origin != MemoryOrigin.HerRecorded) return errorJson(USER_ADDED_LOCKED)
        memoryRepository.delete(memory.id)
        return buildJsonObject {
            put("forgotten", JsonPrimitive(true))
            put("id", JsonPrimitive(MemoryTag.labelOf(memory.id)))
        }.toString()
    }

    /** 先按编号（`m1a2b3c`）找，再按原始 id 兜底。 */
    private suspend fun resolveMemory(raw: String): Memory? {
        val tag = MemoryTag.normalizeIdInput(raw)
        val all = memoryRepository.observeAll().first()
        if (tag != null) all.firstOrNull { MemoryTag.tagOf(it.id) == tag }?.let { return it }
        return all.firstOrNull { it.id == raw.trim() }
    }
    // ------------------------------------------------------------ 小工具

    private suspend fun todayEntry() = diaryRepository.observeAll().first().firstOrNull { it.date == today() }

    private fun today(): LocalDate = LocalDate.now()

    private fun nowTime(): String = LocalDateTime.now().format(TIME_FORMATTER)

    private fun textArgument(args: kotlinx.serialization.json.JsonElement): String =
        runCatching { args.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }.getOrDefault("")

    private fun errorJson(message: String): String =
        buildJsonObject { put("error", JsonPrimitive(message)) }.toString()

    /** category 参数：取值和记忆段标题一致（[WhaleMemoryPrompt.ABOUT_THEM] 等）。 */
    private fun categorySchema(keepHint: Boolean = false) = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("enum", buildJsonArray {
            add(JsonPrimitive(WhaleMemoryPrompt.ABOUT_THEM))
            add(JsonPrimitive(WhaleMemoryPrompt.AGREEMENT))
            add(JsonPrimitive(WhaleMemoryPrompt.HERS))
        })
        put(
            "description",
            JsonPrimitive(if (keepHint) "分类；不填就保持原来的分类" else "分类，不填就是「关于对方」"),
        )
    }

    private companion object {
        /** 带（对方让记的）标记的条目：不能改也不能删（决定 1 / 决定 2；提示词第 8 批） */
        const val LOCK_RULE = "带（对方让记的）标记的不能改也不能删，想补充就另记一条。"
        const val USER_ADDED_LOCKED = "这条是对方让记的。$LOCK_RULE"
        const val NOT_FOUND = "没有这条记忆（编号可能不对，先看看现在的记忆）"

        const val MAX_SEGMENT_CHARS = 1500
        const val MAX_CHARS_PER_DAY = 6000
        const val MAX_READ_CHARS_PER_DAY = 3000
        const val MAX_MEMORY_CHARS = 200

        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    }
}
