package whaleui.nightsky.ltd.sticker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 表情库解析与校验（任务书 3.1 / 3.2）。
 *
 * **纯 JVM**：只通过 [StickerSource] 读文件，不认识 Android，所以 144 张真实库和
 * 各种坏数据都能在 JVM 单测里跑。规则：
 *
 * - 一个系列 = 一个带 `series.json` 的文件夹；有 `index.json` 就按它列系列，没有就扫子文件夹；
 * - 系列级校验不过（format/version 不对、没有贴图）→ **整个系列跳过**；
 * - 单张校验不过（缺 file/description/detail、不是 WebP、超过 2 MB）→ **只跳过这一张**；
 * - `emotions` 里不在 42 词表的词 → 丢掉该词、记警告，**不整条拒绝**；
 * - 没有 `series.json` 的文件夹、根目录的散图 → 不导入，记「跳过：缺少 series.json」。
 */
object StickerParser {

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    /** 系列文件夹名开头的两位系列号，例如 `01_3620_...` → `01`。 */
    private val FOLDER_SERIES_ID = Regex("^(\\d{2})_")

    data class ParsedSticker(
        val id: String,
        val file: String,
        /** 相对源根的路径，导入时按它读文件。 */
        val sourcePath: String,
        val animated: Boolean,
        val description: String,
        val detail: String,
        val textInImage: String,
        val textMeaning: String,
        val characters: List<String>,
        val couple: Boolean,
        val emotions: List<String>,
        val useWhen: String,
        val needsReview: Boolean,
    )

    data class ParsedPack(
        val seriesId: String,
        val folder: String,
        val name: String,
        val titleOriginal: String,
        val characters: List<String>,
        val animated: Boolean,
        val hasSoundOriginally: Boolean,
        val source: String,
        val licenseNote: String,
        val sortOrder: Int,
        val stickers: List<ParsedSticker>,
    )

    /** 一条「跳过 / 警告」记录。 */
    data class Issue(val subject: String, val reason: String)

    data class Result(
        val packs: List<ParsedPack>,
        val skippedSeries: List<Issue> = emptyList(),
        val skippedStickers: List<Issue> = emptyList(),
        val warnings: List<String> = emptyList(),
    )

    fun parse(source: StickerSource): Result {
        val skippedSeries = mutableListOf<Issue>()
        val skippedStickers = mutableListOf<Issue>()
        val warnings = mutableListOf<String>()

        val rootEntries = source.list("")
        val rootFiles = rootEntries.filter { !it.isDirectory }
        val rootDirs = rootEntries.filter { it.isDirectory }

        // 根目录零散的图片：不收（任务书 3.2 第 2 条）
        val looseImages = rootFiles.count { it.name.lowercase().substringAfterLast('.') in IMAGE_EXTENSIONS }
        if (looseImages > 0) {
            warnings += "根目录有 $looseImages 个图片文件未导入：缺少 series.json（只收按约定做好的数据包）"
        }

        // 源读失败（SAF provider 抛异常等）不能让整次解析失败：index.json 读不出来就退回扫子文件夹
        val indexText = try {
            source.readText(STICKER_INDEX_FILE)
        } catch (e: Exception) {
            warnings += "$STICKER_INDEX_FILE 读不出来（${e.message ?: e::class.simpleName}），改为扫描子文件夹"
            null
        }
        val packs = mutableListOf<ParsedPack>()

        if (indexText != null) {
            val parsedIndex = runCatching { json.parseToJsonElement(indexText) }.getOrNull()
            val indexObject = parsedIndex as? JsonObject
            if (indexObject == null) {
                warnings += "$STICKER_INDEX_FILE 不是合法 JSON，改为扫描子文件夹"
                scanFolders(source, rootDirs.map { it.name }, skippedSeries, skippedStickers, warnings, packs)
            } else {
                val format = indexObject.string("format")
                val version = indexObject.int("version")
                if (format != null && format != "whale-sticker-library") {
                    warnings += "$STICKER_INDEX_FILE 的 format 是 $format，不是 whale-sticker-library"
                }
                if (version != null && version != STICKER_SERIES_VERSION) {
                    warnings += "$STICKER_INDEX_FILE 的 version 是 $version，不是 $STICKER_SERIES_VERSION"
                }
                val series = indexObject.array("series")
                if (series.isEmpty()) {
                    warnings += "$STICKER_INDEX_FILE 里没有系列，改为扫描子文件夹"
                    scanFolders(source, rootDirs.map { it.name }, skippedSeries, skippedStickers, warnings, packs)
                } else {
                    series.forEachIndexed { index, item ->
                        val entry = item as? JsonObject ?: return@forEachIndexed
                        val folder = entry.string("folder")
                        if (folder.isNullOrBlank()) {
                            warnings += "$STICKER_INDEX_FILE 第 ${index + 1} 个系列没有 folder，跳过"
                            return@forEachIndexed
                        }
                        val directoryName = folder.trimEnd('/').substringAfterLast('/')
                        if (directoryName !in rootDirs.map { it.name }) {
                            skippedSeries += Issue(directoryName, "index.json 里列出了，但目录不存在")
                            return@forEachIndexed
                        }
                        parseSeriesSafely(
                            source = source,
                            folder = directoryName,
                            sortOrder = index,
                            skippedSeries = skippedSeries,
                            skippedStickers = skippedStickers,
                            warnings = warnings,
                        )?.let(packs::add)
                    }
                }
            }
        } else {
            scanFolders(source, rootDirs.map { it.name }, skippedSeries, skippedStickers, warnings, packs)
        }

        return Result(
            packs = packs.sortedBy { it.sortOrder },
            skippedSeries = skippedSeries,
            skippedStickers = skippedStickers,
            warnings = warnings.distinct(),
        )
    }

    private fun scanFolders(
        source: StickerSource,
        folders: List<String>,
        skippedSeries: MutableList<Issue>,
        skippedStickers: MutableList<Issue>,
        warnings: MutableList<String>,
        out: MutableList<ParsedPack>,
    ) {
        folders.sorted().forEachIndexed { index, folder ->
            val hasSeriesFile = try {
                source.exists("$folder/$STICKER_SERIES_FILE")
            } catch (e: Exception) {
                skippedSeries += Issue(folder, "读取失败：${e.message ?: e::class.simpleName}")
                return@forEachIndexed
            }
            if (!hasSeriesFile) {
                skippedSeries += Issue(folder, "缺少 series.json")
                return@forEachIndexed
            }
            parseSeriesSafely(
                source = source,
                folder = folder,
                sortOrder = index,
                skippedSeries = skippedSeries,
                skippedStickers = skippedStickers,
                warnings = warnings,
            )?.let(out::add)
        }
    }

    /**
     * 解析一个系列；源在读这个系列时抛异常（SAF 的 IOException / SecurityException 等）
     * 就只跳过这个系列并记原因，其余系列继续（W-016）。
     */
    private fun parseSeriesSafely(
        source: StickerSource,
        folder: String,
        sortOrder: Int,
        skippedSeries: MutableList<Issue>,
        skippedStickers: MutableList<Issue>,
        warnings: MutableList<String>,
    ): ParsedPack? = try {
        parseSeries(source, folder, sortOrder, skippedSeries, skippedStickers, warnings)
    } catch (e: Exception) {
        skippedSeries += Issue(folder, "读取失败：${e.message ?: e::class.simpleName}")
        null
    }

    private fun parseSeries(
        source: StickerSource,
        folder: String,
        sortOrder: Int,
        skippedSeries: MutableList<Issue>,
        skippedStickers: MutableList<Issue>,
        warnings: MutableList<String>,
    ): ParsedPack? {
        val text = source.readText("$folder/$STICKER_SERIES_FILE")
        if (text == null) {
            skippedSeries += Issue(folder, "series.json 读不出来（不存在、读取失败或超过 1 MB）")
            return null
        }
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
        val series = element as? JsonObject
        if (series == null) {
            skippedSeries += Issue(folder, "series.json 不是合法 JSON")
            return null
        }

        val format = series.string("format")
        if (format != STICKER_SERIES_FORMAT) {
            skippedSeries += Issue(folder, "format 是 ${format ?: "(空)"}，不是 $STICKER_SERIES_FORMAT")
            return null
        }
        val version = series.int("version")
        if (version != STICKER_SERIES_VERSION) {
            skippedSeries += Issue(folder, "version 是 ${version ?: "(空)"}，不是 $STICKER_SERIES_VERSION")
            return null
        }

        val folderSeriesId = FOLDER_SERIES_ID.find(folder)?.groupValues?.get(1)
        val seriesId = series.string("series_id")?.trim()?.takeIf { it.isNotEmpty() } ?: folderSeriesId
        if (seriesId == null) {
            skippedSeries += Issue(folder, "缺少 series_id，文件夹名里也没有两位系列号")
            return null
        }
        if (!seriesId.matches(SERIES_ID_PATTERN)) {
            skippedSeries += Issue(folder, "series_id「$seriesId」不合法（只允许字母数字下划线）")
            return null
        }
        if (folderSeriesId != null && folderSeriesId != seriesId) {
            warnings += "系列 $folder 的 series_id 是 $seriesId，和文件夹名开头的 $folderSeriesId 不一致，按 $seriesId 导入"
        }

        val titleOriginal = series.string("title_original")?.trim().orEmpty().ifEmpty { folder }
        val name = series.string("name")?.trim().orEmpty().ifEmpty { titleOriginal }
        val characters = series.stringList("characters")
        val animated = series.bool("animated") ?: true
        val hasSound = series.bool("has_sound_originally") ?: false
        val sourceText = series.string("source")?.trim().orEmpty()
        val licenseNote = series.string("license_note")?.trim().orEmpty()

        val stickerItems = series.array("stickers")
        if (stickerItems.isEmpty()) {
            skippedSeries += Issue(folder, "没有 stickers 条目")
            return null
        }

        val stickers = mutableListOf<ParsedSticker>()
        val seenIds = mutableSetOf<String>()
        stickerItems.forEachIndexed { index, item ->
            val sticker = item as? JsonObject ?: run {
                skippedStickers += Issue("$folder 第 ${index + 1} 条", "条目不是 JSON 对象")
                null
            } ?: return@forEachIndexed
            val parsed = parseSticker(
                source = source,
                folder = folder,
                seriesId = seriesId,
                packAnimated = animated,
                packCharacters = characters,
                item = sticker,
                skippedStickers = skippedStickers,
                warnings = warnings,
            ) ?: return@forEachIndexed
            if (!seenIds.add(parsed.id)) {
                warnings += "系列 $seriesId 里编号 ${parsed.id} 重复，只保留第一条"
                return@forEachIndexed
            }
            stickers.add(parsed)
        }

        if (stickers.isEmpty()) {
            skippedSeries += Issue(folder, "所有贴图都没通过校验")
            return null
        }

        return ParsedPack(
            seriesId = seriesId,
            folder = folder,
            name = name,
            titleOriginal = titleOriginal,
            characters = characters,
            animated = animated,
            hasSoundOriginally = hasSound,
            source = sourceText,
            licenseNote = licenseNote,
            sortOrder = sortOrder,
            stickers = stickers,
        )
    }

    private fun parseSticker(
        source: StickerSource,
        folder: String,
        seriesId: String,
        packAnimated: Boolean,
        packCharacters: List<String>,
        item: JsonObject,
        skippedStickers: MutableList<Issue>,
        warnings: MutableList<String>,
    ): ParsedSticker? {
        val file = item.string("file")?.trim().orEmpty()
        if (file.isEmpty() || '/' in file || '\\' in file || file.contains("..")) {
            skippedStickers += Issue(folder, "缺少合法的 file 字段")
            return null
        }
        val path = "$folder/$file"
        if (!source.exists(path)) {
            skippedStickers += Issue(file, "file 不存在")
            return null
        }
        val length = source.length(path)
        if (length > STICKER_MAX_BYTES) {
            skippedStickers += Issue(file, "超过 2 MB（${length / 1024} KB）")
            return null
        }
        val head = source.readHead(path, StickerSource.RIFF_HEADER_SIZE)
        if (!StickerSource.isWebp(head)) {
            skippedStickers += Issue(file, "不是 WebP（只支持 WebP，请先在电脑上转换）")
            return null
        }

        val fileBase = file.substringBeforeLast('.')
        val rawId = item.string("id")?.trim().orEmpty()
        val id = when {
            rawId.isEmpty() -> "$seriesId-$fileBase"
            !rawId.startsWith("$seriesId-") -> {
                warnings += "系列 $seriesId 的 $file 编号写成「$rawId」，按 <系列号>-<文件名> 改成「$seriesId-$fileBase」"
                "$seriesId-$fileBase"
            }

            else -> rawId
        }
        // 编号会拼进缩略图文件名：只收字母数字、下划线、连字符（W-014），不合格的这一张跳过
        if (!id.matches(STICKER_ID_PATTERN)) {
            skippedStickers += Issue(id, "编号不合法（只允许字母、数字、下划线和连字符）")
            return null
        }

        val description = item.string("description")?.trim().orEmpty()
        if (description.isEmpty()) {
            skippedStickers += Issue(id, "缺少 description")
            return null
        }
        val detail = item.string("detail")?.trim().orEmpty()
        if (detail.isEmpty()) {
            skippedStickers += Issue(id, "缺少 detail")
            return null
        }

        val rawEmotions = item.stringList("emotions")
        val emotions = rawEmotions.filter { StickerEmotions.isKnown(it) }
        val dropped = rawEmotions.filterNot { StickerEmotions.isKnown(it) }
        if (dropped.isNotEmpty()) {
            warnings += "系列 $seriesId 的 $id 忽略了不在词表里的情绪词：${dropped.joinToString("、")}"
        }
        if (emotions.isEmpty()) {
            warnings += "系列 $seriesId 的 $id 没有任何合法情绪词，仍然导入（只能靠关键词找到）"
        }

        val characters = item.stringList("characters").ifEmpty { packCharacters }

        return ParsedSticker(
            id = id,
            file = file,
            sourcePath = path,
            animated = item.bool("animated") ?: packAnimated,
            description = description,
            detail = detail,
            textInImage = item.string("text_in_image")?.trim().orEmpty(),
            textMeaning = item.string("text_meaning")?.trim().orEmpty(),
            characters = characters,
            couple = item.bool("couple") ?: false,
            emotions = emotions.distinct(),
            useWhen = item.string("use_when")?.trim().orEmpty(),
            needsReview = item.bool("needs_review") ?: false,
        )
    }

    private val SERIES_ID_PATTERN = Regex("^[0-9A-Za-z_-]+$")

    private val IMAGE_EXTENSIONS = setOf("webp", "png", "gif", "apng", "tgs", "webm", "jpg", "jpeg")

    // ------------------------------------------------------------ JSON 小工具

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean? {
        val text = (this[key] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: return null
        return when (text) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }

    private fun JsonObject.array(key: String): List<kotlinx.serialization.json.JsonElement> =
        (this[key] as? JsonArray)?.toList().orEmpty()

    private fun JsonObject.stringList(key: String): List<String> =
        array(key).mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
            .filter { it.isNotEmpty() }
}
