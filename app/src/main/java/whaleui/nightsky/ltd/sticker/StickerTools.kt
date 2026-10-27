package whaleui.nightsky.ltd.sticker

import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 她发表情的两个工具（任务书 3.3）：`find_sticker` / `send_sticker`。
 *
 * 接入方式和别的工具一样：`WhaleToolset.create()` 里挂上，`ChatToolFactory` 只在
 * assistant 是她时加进工具列表。**没有可用表情时 `WhaleToolset` 根本不会创建这两个工具**
 * （任务书 3.4 第 2 条：她就不知道有表情这回事）。
 *
 * 「一轮只能发一张」用 `create()` 里的局部开关实现：`ChatToolFactory.createTools` 每轮
 * （包括闹钟轮）新建一次工具列表，所以开关天然随轮次重置；工具回环里的第二次调用会被拦住。
 */
class StickerTools(
    private val repository: StickerRepository,
    /** App 的 `filesDir`（表情文件在这里）。 */
    private val filesDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun create(): List<Tool> {
        val sentThisTurn = java.util.concurrent.atomic.AtomicBoolean(false)
        return listOf(
            findStickerTool(),
            sendStickerTool(sentThisTurn),
        )
    }

    // ------------------------------------------------------------ find_sticker

    private fun findStickerTool(): Tool = Tool(
        name = "find_sticker",
        description = "在你的表情包里找一张。先按心情（emotion）筛，可以再加几个关键词（keywords，空格分隔）；" +
            "couple=true 只看两个角色亲密互动的（抱、亲、送爱心）；page 从 1 开始，一页最多 10 张。" +
            "返回每张的编号和说明，挑好之后用 send_sticker(编号) 发出去。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "emotion",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("情绪词，例如 抱抱、开心、撒娇、委屈"))
                        },
                    )
                    put(
                        "keywords",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("几个关键词，空格分隔，例如「围巾 脸红」"))
                        },
                    )
                    put(
                        "couple",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("description", JsonPrimitive("只要两个角色抱抱/亲亲/送爱心这类"))
                        },
                    )
                    put(
                        "page",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("minimum", JsonPrimitive(1))
                            put("description", JsonPrimitive("第几页，从 1 开始"))
                        },
                    )
                },
                required = emptyList(),
            )
        },
        execute = { args ->
            listOf(UIMessagePart.Text(findSticker(args.jsonObject)))
        },
    )

    internal suspend fun findSticker(args: kotlinx.serialization.json.JsonObject): String {
        val emotion = args["emotion"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        if (emotion != null && !StickerEmotions.isKnown(emotion)) {
            val available = availableEmotions()
            return buildString {
                append("不认识的情绪词「$emotion」。")
                if (available.isEmpty()) {
                    append("现在没有可用的表情。")
                } else {
                    append("可以用的情绪：").append(available.joinToString("、"))
                }
            }
        }
        val keywords = args["keywords"]?.jsonPrimitive?.contentOrNull.orEmpty()
            .split(' ', '　', ',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val couple = args["couple"]?.jsonPrimitive?.booleanOrNull ?: false
        val page = args["page"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1

        val candidates = repository.usableStickers()
        if (candidates.isEmpty()) return "现在没有可用的表情。"

        return when (
            val outcome = StickerFinder.find(
                candidates = candidates,
                query = StickerFinder.Query(
                    emotion = emotion,
                    keywords = keywords,
                    couple = couple,
                    page = page,
                ),
                nowMs = clock(),
            )
        ) {
            is StickerFinder.Outcome.Empty -> {
                diag("find_sticker emotion=${emotion ?: "-"} kws=${keywords.size} -> 0")
                buildString {
                    append("没有找到合适的。")
                    if (outcome.availableEmotions.isNotEmpty()) {
                        append("现在有图的情绪：").append(outcome.availableEmotions.joinToString("、"))
                    }
                }
            }

            is StickerFinder.Outcome.Rows -> {
                diag("find_sticker emotion=${emotion ?: "-"} kws=${keywords.size} -> ${outcome.total} 条")
                buildString {
                    append("共 ").append(outcome.total).append(" 张，第 ")
                    append(outcome.page).append("/").append(outcome.totalPages).append(" 页：")
                    outcome.rows.forEach { row ->
                        appendLine()
                        append(row)
                    }
                }
            }
        }
    }

    /** 现在实际有图的情绪分类（错误提示用）。 */
    private suspend fun availableEmotions(): List<String> =
        StickerFinder.availableEmotions(repository.usableStickers())

    // ------------------------------------------------------------ send_sticker

    private fun sendStickerTool(sentThisTurn: java.util.concurrent.atomic.AtomicBoolean): Tool = Tool(
        name = "send_sticker",
        description = "把挑好的一张表情发给对方。id 用 find_sticker 返回的编号，例如 01-1000001。" +
            "一轮最多发一张；没找到合适的就别发，用话说也行。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "id",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("表情编号，例如 01-1000001"))
                        },
                    )
                },
                required = listOf("id"),
            )
        },
        execute = { args ->
            sendSticker(
                id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                sentThisTurn = sentThisTurn,
            )
        },
    )

    internal suspend fun sendSticker(
        id: String,
        sentThisTurn: java.util.concurrent.atomic.AtomicBoolean,
    ): List<UIMessagePart> {
        val stickerId = id.trim()
        if (stickerId.isEmpty()) return listOf(UIMessagePart.Text(errorJson("要发哪一张？填编号，比如 01-1000001")))
        if (sentThisTurn.get()) {
            diag("send_sticker blocked id=$stickerId (already sent this turn)")
            return listOf(UIMessagePart.Text(errorJson("这一轮已经发过一张了")))
        }
        val sticker = repository.sticker(stickerId)
            ?: return listOf(UIMessagePart.Text(errorJson("没有这个编号的表情（编号形如 01-1000001）")))
        val pack = repository.pack(sticker.packId)
            ?: return listOf(UIMessagePart.Text(errorJson("这张表情的系列不见了")))
        if (!pack.enabledForHer) {
            return listOf(UIMessagePart.Text(errorJson("这个系列你现在不能用")))
        }
        // 只读 filesDir/stickers 里面的文件：库里的路径带 ../ 指到别处时当作文件不在（F2 附 4）
        val file = stickerPathInRoot(filesDir, sticker.filePath)
        if (file == null || !file.isFile || file.length() <= 0L) {
            return listOf(UIMessagePart.Text(errorJson("这张表情的文件不在了")))
        }
        if (!sentThisTurn.compareAndSet(false, true)) {
            diag("send_sticker blocked id=$stickerId (race, already sent)")
            return listOf(UIMessagePart.Text(errorJson("这一轮已经发过一张了")))
        }
        repository.markSentByHer(sticker.id, clock())
        diag("send_sticker id=${sticker.id} desc=${sticker.description}")
        return listOf(
            UIMessagePart.Text(buildJsonObject {
                put("sent", JsonPrimitive(true))
                put("id", JsonPrimitive(sticker.id))
            }.toString()),
            UIMessagePart.Image(
                url = "file://" + file.absolutePath,
                metadata = stickerMetadata(sticker),
            ),
        )
    }

    /** 仅 debug 的行为日志（设备验证时从 logcat 看她的调用；不含正文，只有编号和计数）。 */
    private fun diag(message: String) {
        if (me.rerere.rikkahub.BuildConfig.DEBUG) {
            runCatching { android.util.Log.i("WhaleSticker", message) }
        }
    }

    private fun errorJson(message: String): String =
        buildJsonObject { put("error", JsonPrimitive(message)) }.toString()
}

/**
 * 表情消息部件上的 metadata 约定（不新增 `UIMessagePart` 子类）：
 * `{"sticker":true,"stickerId":"01-1000001","description":"…","detail":"…"}`。
 *
 * 请求转换器靠它把图片换成文字（DeepSeek 的 assistant 消息不能带图），界面靠它把
 * 这条消息画成「没有气泡的表情」；`sticker` 字段就是识别标记。
 */
fun stickerMetadata(sticker: Sticker): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("sticker", JsonPrimitive(true))
    put("stickerId", JsonPrimitive(sticker.id))
    put("description", JsonPrimitive(sticker.description))
    put("detail", JsonPrimitive(sticker.detail))
}

/** 他从面板发的表情也用同一套 metadata。 */
fun stickerMetadata(id: String, description: String, detail: String): kotlinx.serialization.json.JsonObject =
    buildJsonObject {
        put("sticker", JsonPrimitive(true))
        put("stickerId", JsonPrimitive(id))
        put("description", JsonPrimitive(description))
        put("detail", JsonPrimitive(detail))
    }

/** 这个部件是不是「表情」。 */
fun UIMessagePart.isStickerPart(): Boolean =
    this is UIMessagePart.Image &&
        (metadata?.get("sticker") as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true

/** 表情编号（不是表情部件时返回 null）。 */
fun UIMessagePart.stickerIdOrNull(): String? =
    if (!isStickerPart()) null
    else (metadata?.get("stickerId") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** 表情描述（`description`，≤12 字，她发的用这个）。 */
fun UIMessagePart.stickerDescriptionOrNull(): String? =
    if (!isStickerPart()) null
    else (metadata?.get("description") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** 表情详细说明（`detail`，30–90 字，他发的用这个）。 */
fun UIMessagePart.stickerDetailOrNull(): String? =
    if (!isStickerPart()) null
    else (metadata?.get("detail") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** 表情描述（她的用 description，他的用 detail；缺了退回另一个）。 */
fun UIMessagePart.stickerTextOrNull(): String? {
    if (!isStickerPart()) return null
    val description = stickerDescriptionOrNull().orEmpty()
    val detail = stickerDetailOrNull().orEmpty()
    return description.ifBlank { detail }.takeIf { it.isNotBlank() }
}

/**
 * 把 `file://` 地址还原成本地文件并判断还在不在（表情被删掉后聊天里留占位）。
 * 不是 `file://` 的地址一律当作还在（不猜）。
 */
fun stickerUrlAlive(url: String): Boolean {
    if (!url.startsWith("file://")) return true
    return java.io.File(url.removePrefix("file://")).isFile
}

// ------------------------------------------------------------------ 消息层面的小工具

/** 这条消息里有几张表情（顶层部件 + 工具输出里的；切开的气泡通常各只有一张）。 */
fun UIMessage.stickerPartCount(): Int = parts.sumOf { part ->
    when {
        part.isStickerPart() -> 1
        part is UIMessagePart.Tool -> part.output.count { it.isStickerPart() }
        else -> 0
    }
}

/** 这条消息里所有表情图片的地址（用来把它们从「普通图片」里剔掉）。 */
fun List<UIMessagePart>.stickerUrls(): Set<String> {
    val parts = this
    return buildSet {
        parts.forEach { part ->
            when {
                part.isStickerPart() ->
                    (part as UIMessagePart.Image).url.takeIf { it.isNotBlank() }?.let(::add)

                part is UIMessagePart.Tool -> addAll(part.output.stickerUrls())
            }
        }
    }
}

/** 这条消息里的第一张表情部件（顶层优先，其次工具输出）。 */
fun UIMessage.firstStickerPart(): UIMessagePart? =
    parts.firstOrNull { it.isStickerPart() }
        ?: parts.filterIsInstance<UIMessagePart.Tool>()
            .firstNotNullOfOrNull { tool -> tool.output.firstOrNull { it.isStickerPart() } }

/**
 * 会话列表 / 通知栏用的最后一条消息预览：有文字用文字，只有表情就用「[表情] description」。
 */
fun UIMessage.previewTextForWhale(): String {
    val text = parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
        .takeIf { it.isNotEmpty() }
    if (text != null) return text
    val sticker = firstStickerPart() ?: return ""
    return stickerPreviewText(sticker.stickerTextOrNull().orEmpty())
}
