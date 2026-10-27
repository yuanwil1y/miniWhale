package whaleui.nightsky.ltd.data

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import whaleui.nightsky.ltd.sticker.StickerRepository
import whaleui.nightsky.ltd.domain.chat.ChatMessage
import whaleui.nightsky.ltd.domain.chat.ChatRepository
import whaleui.nightsky.ltd.domain.chat.ChatListRepository
import whaleui.nightsky.ltd.domain.chat.ConversationItem
import whaleui.nightsky.ltd.domain.memory.DiaryRepository
import whaleui.nightsky.ltd.domain.memory.Memory
import whaleui.nightsky.ltd.domain.memory.MemoryRepository
import whaleui.nightsky.ltd.domain.settings.ExportError
import whaleui.nightsky.ltd.domain.settings.ExportResult
import whaleui.nightsky.ltd.domain.settings.ExportService
import whaleui.nightsky.ltd.domain.settings.PersonaKind
import whaleui.nightsky.ltd.domain.settings.PersonaStore
import whaleui.nightsky.ltd.domain.settings.SecretStore

/**
 * 导出真实实现：一个 zip 装下全部可迁移数据，布局见 [BundleFormat]。
 *
 * ```
 * soul.md                       人设（没导入就跳过）
 * user.md
 * memory/memories.md            记忆（Markdown，和导入格式一致）
 * memory/memories.json          记忆（结构化，字段完整）
 * diary/2026-10-25.md           日记（`[HH:mm] 文本`）
 * chat/2026-10-25_miniWhale.md   聊天记录（和导入格式一致）
 * index.json                    表情库索引（whale-sticker-library v2）
 * <系列号>/series.json           表情系列（whale-sticker-series v2）
 * <系列号>/<文件名>.webp
 * ```
 *
 * 关键约束：
 * - 导出的记忆 / 日记 / 聊天 / 表情都是**导入端能直接读回**的格式，跨设备迁移闭环；
 * - 这里完全不碰 `SecretStore`，导出内容里不会出现任何 API 密钥；
 * - 所有 IO 都在 [Dispatchers.IO]。
 */
class FileExportService(
    context: Context,
    private val memoryRepository: MemoryRepository,
    private val diaryRepository: DiaryRepository,
    private val personaStore: PersonaStore,
    private val chatListRepository: ChatListRepository,
    private val chatRepository: ChatRepository,
    private val stickerRepository: StickerRepository,
) : ExportService {

    private val appContext: Context = context.applicationContext

    private val json = Json { prettyPrint = true }

    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)

    // ---------------------------- 全量导出 -----------------------------------

    override suspend fun exportAll(destination: Uri): ExportResult = withContext(Dispatchers.IO) {
        val files = try {
            buildAllFiles()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext ExportResult.Failure(ExportError.ReadFailed)
        }
        if (files.isEmpty()) return@withContext ExportResult.Failure(ExportError.NoData)

        val written = try {
            writeZip(destination, files)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext ExportResult.Failure(ExportError.WriteFailed)
        }
        ExportResult.Success(bytes = written, fileCount = files.size)
    }

    /** 收集五类数据 + 表情库。读失败会抛异常，由 [exportAll] 转成 [ExportError.ReadFailed]。 */
    private suspend fun buildAllFiles(): List<BundleEntry> {
        val files = mutableListOf<BundleEntry>()

        // 人设
        PersonaKind.entries.forEach { kind ->
            val text = personaStore.readText(kind)?.takeIf { it.isNotBlank() } ?: return@forEach
            files += BundleFile(name = kind.fileName, bytes = text.toByteArray(Charsets.UTF_8))
        }

        // 记忆（Markdown 给导入用，JSON 保留完整字段）
        val memories = memoryRepository.observeAll().first()
        if (memories.isNotEmpty()) {
            files += BundleFile(
                name = BundleFormat.MEMORY_MD,
                bytes = memoryRepository.exportAll().toByteArray(Charsets.UTF_8),
            )
            files += BundleFile(name = BundleFormat.MEMORY_JSON, bytes = encodeMemoriesJson(memories))
        }

        // 日记
        diaryRepository.observeAll().first().forEach { entry ->
            files += BundleFile(
                name = BundleFormat.diaryPath(entry.date),
                bytes = (entry.content.trimEnd() + "\n").toByteArray(Charsets.UTF_8),
            )
        }

        // 聊天记录
        val conversations = chatListRepository.loadConversations()
        buildConversationFiles(conversations).forEach { file ->
            files += BundleFile(name = BundleFormat.chatPath(file.name), bytes = file.bytes)
        }

        // 表情库
        files += StickerBundleExporter(appContext.filesDir, stickerRepository).entries()

        return files
    }

    // ---------------------------- 记忆 ---------------------------------------

    /**
     * 记忆 JSON。这里单独定义一个 DTO，不给 [Memory] 加 `@Serializable`：
     * 数据模型属于已验收的界面层，导出格式变了也不该回头改它。
     */
    private fun encodeMemoriesJson(memories: List<Memory>): ByteArray {
        val items = memories.map { memory ->
            MemoryExportDto(
                id = memory.id,
                content = memory.content,
                category = memory.category.name,
                origin = memory.origin.name,
                createdAt = memory.createdAt,
                updatedAt = memory.updatedAt,
            )
        }
        val text = json.encodeToString(ListSerializer(MemoryExportDto.serializer()), items)
        return text.toByteArray(Charsets.UTF_8)
    }

    // ---------------------------- 聊天 ---------------------------------------

    /** 把每个对话按 dayLabel 拆成若干 Markdown 文件；某天没消息的对话不出现。 */
    private suspend fun buildConversationFiles(
        conversations: List<ConversationItem>,
    ): List<BundleFile> {
        val files = mutableListOf<BundleFile>()
        val usedNames = mutableSetOf<String>()

        conversations.forEach { conversation ->
            val messages = chatRepository.loadInitial(conversation.id)
            if (messages.isEmpty()) return@forEach

            // 保持消息里 dayLabel 第一次出现的顺序，和聊天页的阅读顺序一致。
            val byDay = LinkedHashMap<String, MutableList<ChatMessage>>()
            messages.forEach { message ->
                val dayLabel = message.dayLabel.ifBlank { DefaultDayLabel }
                byDay.getOrPut(dayLabel) { mutableListOf() }.add(message)
            }

            byDay.forEach { (dayLabel, dayMessages) ->
                val fileName = buildFileName(dayLabel, conversation.title, usedNames)
                val body = buildConversationMarkdown(conversation.title, dayLabel, dayMessages)
                files += BundleFile(name = fileName, bytes = body.toByteArray(Charsets.UTF_8))
            }
        }
        return files
    }

    private fun buildConversationMarkdown(
        title: String,
        dayLabel: String,
        messages: List<ChatMessage>,
    ): String = buildString {
        append("# ").append(title.oneLine()).append("\n\n")
        // 标题写真实日期（yyyy-MM-dd），导入端靠它还原日期，跨设备迁移时不会丢年。
        append("## ").append(datePrefix(dayLabel)).append("\n\n")
        messages.forEach { message ->
            append("- ").append(messageLine(message)).append('\n')
        }
    }

    /** 一条消息 → `- [21:10] 她：文本` / `[图片]` / `[语音 7s]` / 文件名。 */
    private fun messageLine(message: ChatMessage): String {
        val speaker = if (message.fromHer) "她" else "我"
        val body = when {
            message.text != null && message.text.isNotBlank() -> message.text.oneLine()
            message.isImage ->
                if (message.imageUris.size > 1) "[图片 ×${message.imageUris.size}]" else "[图片]"

            message.isVoice -> "[语音 ${message.voiceSeconds ?: 0}s]"
            message.isDocument -> message.documentName.orEmpty().oneLine()
            else -> ""
        }
        return "[${message.time}] $speaker：$body"
    }

    // ---------------------------- 文件名 -------------------------------------

    /**
     * 把界面上的日期标签换成文件名前缀 `yyyy-MM-dd`：
     * 「今天 / 昨天 / 前天」按当前日期推算，`M月d日` 按今年补全（比今天晚就退一年），
     * 认不出来的原样保留。这样压缩包里每个文件都是"按日期命名"的。
     */
    private fun datePrefix(dayLabel: String): String {
        val today = LocalDate.now()
        val date: LocalDate? = when {
            dayLabel.contains("今天") -> today
            dayLabel.contains("昨天") -> today.minusDays(1)
            dayLabel.contains("前天") -> today.minusDays(2)
            else -> {
                val m = DayLabelDate.find(dayLabel)
                val month = m?.groupValues?.get(1)?.toIntOrNull()
                val dayOfMonth = m?.groupValues?.get(2)?.toIntOrNull()
                if (month == null || dayOfMonth == null) {
                    null
                } else {
                    runCatching { LocalDate.of(today.year, month, dayOfMonth) }
                        .map { if (it.isAfter(today)) it.minusYears(1) else it }
                        .getOrNull()
                }
            }
        }
        return date?.toString() ?: sanitizeFileName(dayLabel)
    }

    /** `10月23日` 这种界面日期标签。 */
    private val DayLabelDate = Regex("(\\d{1,2})月(\\d{1,2})日")

    /**
     * `<yyyy-MM-dd>_<标题>.md`：日期由 dayLabel 推出；去掉文件系统不认的
     * 字符和控制字符，超长截断；同名（不同对话同一天）时加 `_2`、`_3` 后缀，保证 zip 里不重名。
     */
    private fun buildFileName(
        dayLabel: String,
        title: String,
        usedNames: MutableSet<String>,
    ): String {
        val day = datePrefix(dayLabel).ifEmpty { DefaultDayLabel }
        val safeTitle = sanitizeFileName(title).ifEmpty { DefaultFileStem }
        val stem = "${day}_$safeTitle".take(MaxFileNameLength)
        var candidate = "$stem.md"
        var index = 2
        while (!usedNames.add(candidate)) {
            val suffix = "_$index"
            val base = stem.take(MaxFileNameLength - suffix.length)
            candidate = "$base$suffix.md"
            index += 1
        }
        return candidate
    }

    private fun sanitizeFileName(raw: String): String = buildString {
        raw.forEach { ch ->
            when {
                ch in ForbiddenFileNameChars -> Unit
                ch.code < 0x20 || ch.code == 0x7F -> Unit
                else -> append(ch)
            }
        }
    }.trim().trimEnd('.')

    /** 正文 / 标题里的换行换成空格，保证 Markdown 列表一项一行。 */
    private fun String.oneLine(): String =
        replace('\n', ' ').replace('\r', ' ').trim()

    // ---------------------------- 写文件 -------------------------------------

    /** 打包 zip；返回实际写入的字节数（压缩后）。 */
    private fun writeZip(destination: Uri, files: List<BundleEntry>): Long =
        writeBundleZip(BufferedOutputStream(openOutput(destination)), files)

    private fun openOutput(destination: Uri): OutputStream =
        appContext.contentResolver.openOutputStream(destination, "wt")
            ?: throw IOException("无法打开导出目标")

    private companion object {
        const val DefaultDayLabel = "对话"
        const val DefaultFileStem = "对话"

        /** zip 内文件名的字符数上限（不含 `.md`）。 */
        const val MaxFileNameLength = 80

        val ForbiddenFileNameChars = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
    }
}

/**
 * 记忆 JSON 的导出结构。字段固定，改字段等于改导出格式。
 * [category] / [origin] 存枚举的 name，方便以后加值时不用改解析。
 */
@Serializable
private data class MemoryExportDto(
    val id: String,
    val content: String,
    val category: String,
    val origin: String,
    val createdAt: Long,
    val updatedAt: Long,
)
