package whaleui.nightsky.ltd.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 导入日记（任务书 4.7，通用功能）。
 *
 * 支持两种输入：
 * 1. 一堆 `YYYY-MM-DD.md` 文件（每天一个）；
 * 2. 一个 zip，里面是同样结构的文件（允许带一层目录）。
 *
 * 文件内容由若干段组成：一段 = 一行 `[HH:mm] 文本` + 后面所有不以时间戳开头的续行，
 * 和 `write_diary` 写入的格式一致（她写的段落可以带换行）。合并规则**只加不删**：
 * - 同一天里「时间和全文（含续行）都一样」的段跳过；
 * - 已有内容**逐字保留**（标题、天气、续行、空行、顺序都不动），新段按时间插到合适的位置；
 * - 不改天气、不删任何已有内容。
 */
object WhaleDiaryImporter {

    const val FILE_NAME_PATTERN = "yyyy-MM-dd"
    val fileNameFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(FILE_NAME_PATTERN)

    /** 一段日记；[text] 可以有多行（续行之间用 `\n` 连接）。 */
    data class Segment(val time: String, val text: String) {
        /** 去重用：时间 + 全文（含续行，每行去掉首尾空白）都要一样。 */
        val key: String get() = "$time|" + text.lines().joinToString("\n") { it.trim() }
    }

    /** 一份待导入的文件：显示名 + 文本内容。 */
    data class SourceFile(val name: String, val content: String)

    /** 合并结果。 */
    data class MergeResult(
        val content: String,
        val addedSegments: Int,
        val skippedSegments: Int,
    )

    /** 从文件名解析日期；不是 `YYYY-MM-DD.md` 的返回 null。 */
    fun dateOf(fileName: String): LocalDate? {
        val base = fileName.substringAfterLast('/').removeSuffix(".md")
        return runCatching { LocalDate.parse(base, fileNameFormatter) }.getOrNull()
    }

    /**
     * 解析一份文件里的所有段。
     *
     * 一段 = `[HH:mm] 文本` 这一行 + 后面不以时间戳开头的续行（去掉段尾空行）。
     * 容忍：行首空白、`[HH:mm:ss]`、`[H:mm]`；第一个时间戳之前的行（标题 / 天气）不算段；
     * 没有任何文字的段忽略。
     */
    fun parseSegments(content: String): List<Segment> =
        parseBlocks(content.split("\n")).mapNotNull { it.segment }

    /** 一个时间戳块：从时间戳行 [start] 到下一个时间戳行之前（[end] 不含）。 */
    private class Block(val time: String, val start: Int, val end: Int, val segment: Segment?)

    private fun parseBlocks(lines: List<String>): List<Block> {
        val starts = lines.indices.mapNotNull { index -> stampOf(lines[index])?.let { index to it } }
        return starts.mapIndexed { order, (start, stamp) ->
            val end = if (order + 1 < starts.size) starts[order + 1].first else lines.size
            val body = buildList {
                add(stamp.second)
                for (index in start + 1 until end) add(lines[index].trimEnd())
            }.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            val text = body.joinToString("\n")
            Block(
                time = stamp.first,
                start = start,
                end = end,
                segment = if (text.isBlank()) null else Segment(stamp.first, text),
            )
        }
    }

    /** `[HH:mm] 文本` → (HH:mm, 文本)；不是时间戳行返回 null。 */
    private fun stampOf(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("[")) return null
        val close = trimmed.indexOf(']')
        if (close <= 1) return null
        val time = normalizeTime(trimmed.substring(1, close)) ?: return null
        return time to trimmed.substring(close + 1).trim()
    }

    private fun format(segment: Segment): String = "[${segment.time}] ${segment.text}"

    /**
     * 合并：已有内容 + 新段。
     *
     * 新段先按「去重键」过滤；**已有文本一个字都不改**，只在行与行之间插入新段：
     * 每个新段插在第一个「时间晚于它」的已有时间戳行之前；没有更晚的就接在最后一个非空行后面。
     * 时间相同时已有段在前；多个新段落在同一位置时按时间（再按导入顺序）排。
     */
    fun merge(existingContent: String, incoming: List<Segment>): MergeResult {
        val lines = existingContent.split("\n")
        val blocks = parseBlocks(lines)
        val existingKeys = blocks.mapNotNull { it.segment?.key }.toHashSet()
        val fresh = mutableListOf<Segment>()
        var skipped = 0
        for (segment in incoming) {
            if (!existingKeys.add(segment.key)) {
                skipped++
                continue
            }
            fresh += segment
        }
        if (fresh.isEmpty()) {
            return MergeResult(existingContent, addedSegments = 0, skippedSegments = skipped)
        }
        val ordered = fresh.sortedBy { it.time } // 稳定排序：同一时间保持导入顺序
        if (existingContent.isBlank()) {
            return MergeResult(
                content = ordered.joinToString("\n", transform = ::format),
                addedSegments = fresh.size,
                skippedSegments = skipped,
            )
        }
        val appendAt = lines.indexOfLast { it.isNotBlank() } + 1
        val insertions = ordered.groupBy { segment ->
            blocks.firstOrNull { it.time > segment.time }?.start ?: appendAt
        }
        val out = ArrayList<String>(lines.size + fresh.size * 2)
        for (index in 0..lines.size) {
            insertions[index]?.forEach { out += format(it) }
            if (index < lines.size) out += lines[index]
        }
        return MergeResult(
            content = out.joinToString("\n"),
            addedSegments = fresh.size,
            skippedSegments = skipped,
        )
    }

    /**
     * 一次性处理多个文件：按日期分组合并。
     *
     * @param existing 已有的日记内容（日期 → 内容）
     * @return 日期 → 合并后的内容，以及总的天数/段数
     */
    fun importAll(
        files: List<SourceFile>,
        existing: Map<LocalDate, String>,
    ): Result {
        val byDate = LinkedHashMap<LocalDate, MutableList<Segment>>()
        files.forEach { file ->
            val date = dateOf(file.name) ?: return@forEach
            byDate.getOrPut(date) { mutableListOf() }
                .addAll(parseSegments(file.content))
        }
        val merged = LinkedHashMap<LocalDate, String>()
        var added = 0
        var skipped = 0
        byDate.forEach { (date, segments) ->
            val result = merge(existing[date].orEmpty(), segments)
            if (result.addedSegments > 0) merged[date] = result.content
            added += result.addedSegments
            skipped += result.skippedSegments
        }
        return Result(days = merged.size, addedSegments = added, skippedSegments = skipped, contentByDate = merged)
    }

    /** [importAll] 的返回值。 */
    data class Result(
        val days: Int,
        val addedSegments: Int,
        val skippedSegments: Int,
        val contentByDate: Map<LocalDate, String>,
    ) {
        /** 给界面用的提示文案。 */
        fun summary(): String = "导入了 $days 天、$addedSegments 段"
    }

    /** `[HH:mm:ss]` / `[H:mm]` → `HH:mm`；认不出返回 null。 */
    fun normalizeTime(raw: String): String? {
        val parts = raw.trim().split(":")
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return "%02d:%02d".format(hour, minute)
    }

    /**
     * 从 zip 字节里解出所有 `YYYY-MM-DD.md`（返回的 [SourceFile.name] 只保留文件名）。
     *
     * 只认 `.md`，忽略目录和其他文件；读取走 [ImportBundleReader]（单个条目、解压总量、条目数都有上限，
     * 超限抛 [IllegalArgumentException]，不会把压缩炸弹整个解进内存）。不是 zip 返回空列表。
     */
    fun readZip(bytes: ByteArray): List<SourceFile> {
        if (!ImportBundleReader.isZip(bytes)) return emptyList()
        return ImportBundleReader.extractMarkdown(bytes, displayName = "diary.zip", selector = ::isDiaryPath)
            .map { SourceFile(it.name.substringAfterLast('/'), it.content) }
    }

    /** 选中的一个文件 → 待导入的日记文件：`.zip` 按包解，其余当成单个 md。 */
    fun sourcesOf(displayName: String, bytes: ByteArray): List<SourceFile> =
        if (displayName.endsWith(".zip", ignoreCase = true)) {
            readZip(bytes)
        } else {
            listOf(SourceFile(name = displayName, content = bytes.toString(Charsets.UTF_8)))
        }

    private fun isDiaryPath(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name.endsWith(".md", ignoreCase = true) && dateOf(name) != null
    }
}
