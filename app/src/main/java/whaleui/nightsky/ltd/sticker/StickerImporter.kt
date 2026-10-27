package whaleui.nightsky.ltd.sticker

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 生成缩略图（首帧 PNG，长边 256）。接口化是为了让导入逻辑能在 JVM 单测里跑。 */
fun interface StickerThumbnailer {
    /** @return 是否真的生成了；失败不阻断导入（面板会退回用原图）。 */
    fun generate(source: File, destFile: File): Boolean
}

/** 空实现（单测 / 不需要缩略图的场景）。 */
object NoopStickerThumbnailer : StickerThumbnailer {
    override fun generate(source: File, destFile: File): Boolean = false
}

/** 一次导入的计划：解析结果 + 哪些系列号已经存在（导入前要先提示）。 */
data class StickerImportPlan(
    val parsed: StickerParser.Result,
    /** 库里已经有的系列号；合并导入（同编号更新说明，不删已有的）。 */
    val conflicts: List<String>,
) {
    val stickerCount: Int get() = parsed.packs.sumOf { it.stickers.size }

    val isEmpty: Boolean get() = parsed.packs.isEmpty()
}

/** 导入汇总（任务书 3.2 第 1 条：最后给汇总，不要因为一张坏图中断全部）。 */
data class StickerImportReport(
    val seriesImported: Int = 0,
    val stickersImported: Int = 0,
    val skippedSeries: List<StickerParser.Issue> = emptyList(),
    val skippedStickers: List<StickerParser.Issue> = emptyList(),
    val warnings: List<String> = emptyList(),
    val mergedSeries: List<String> = emptyList(),
    val cancelled: Boolean = false,
    /** 复制文件失败、被跳过的贴图（编号 + 原因）。 */
    val failedCopies: List<String> = emptyList(),
    /** 缩略图生成失败的张数（原图仍可用）。 */
    val thumbnailFailures: Int = 0,
) {
    fun summary(): String = buildString {
        append("导入 $seriesImported 个系列、$stickersImported 张")
        if (mergedSeries.isNotEmpty()) append("（合并 ${mergedSeries.size} 个已有系列）")
        if (cancelled) append("，已取消")
        if (skippedStickers.isNotEmpty()) append("，跳过 ${skippedStickers.size} 张")
        if (skippedSeries.isNotEmpty()) append("，跳过 ${skippedSeries.size} 个系列")
    }

    /** 汇总详情（管理页里展开看）。 */
    fun details(): List<String> = buildList {
        skippedSeries.forEach { add("跳过系列「${it.subject}」：${it.reason}") }
        skippedStickers.forEach { add("跳过「${it.subject}」：${it.reason}") }
        failedCopies.forEach { add("跳过 $it") }
        warnings.forEach { add(it) }
        if (thumbnailFailures > 0) add("有 $thumbnailFailures 张缩略图生成失败，面板里会用原图")
    }
}

/** 导入进度（后台协程里回调，界面显示「已处理 / 总数」）。 */
data class StickerImportProgress(val done: Int, val total: Int) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * 表情库导入（任务书 3.1 / 3.2）。
 *
 * 两段式，方便界面在覆盖已有系列前提示：
 * 1. [inspect]：只解析校验，不写任何东西，返回张数和冲突的系列号；
 * 2. [execute]：复制文件、生成缩略图、写两张表，后台协程里跑，可取消。
 *
 * **不识图**、不收散图、只收 WebP，规则都在 `StickerParser` 里。
 */
class StickerImporter(
    /** App 的 `filesDir`。测试传临时目录。 */
    private val filesDir: File,
    private val repository: StickerRepository,
    private val thumbnailer: StickerThumbnailer = NoopStickerThumbnailer,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 只读阶段：解析 + 查冲突系列，不动文件、不写库。
     *
     * 解析要对源做几百次读取（SAF 每次都是一次跨进程调用），所以整个放在 IO 线程（W-016）。
     */
    suspend fun inspect(source: StickerSource): StickerImportPlan = withContext(Dispatchers.IO) {
        val parsed = StickerParser.parse(source)
        val existing = repository.packs().map { it.id }.toSet()
        val conflicts = parsed.packs.map { it.seriesId }.filter { it in existing }
        StickerImportPlan(parsed = parsed, conflicts = conflicts)
    }

    /**
     * 真正导入。可取消：每处理一张检查一次 [isCancelled]，取消时保留已经导入完的系列。
     */
    suspend fun execute(
        source: StickerSource,
        plan: StickerImportPlan,
        onProgress: (StickerImportProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): StickerImportReport = withContext(Dispatchers.IO) {
        val total = plan.stickerCount
        var done = 0
        var seriesImported = 0
        var stickersImported = 0
        var thumbnailFailures = 0
        val failedCopies = mutableListOf<String>()
        val merged = mutableListOf<String>()
        val root = File(filesDir, STICKER_DIR_NAME)
        val thumbs = File(root, STICKER_THUMB_DIR_NAME)
        root.mkdirs()
        thumbs.mkdirs()
        val importedAt = clock()

        onProgress(StickerImportProgress(0, total))

        for (pack in plan.parsed.packs) {
            if (isCancelled()) break
            val packDir = File(root, pack.seriesId)
            // 落盘路径必须在 filesDir/stickers 里面（W-014）；系列号由解析器校验过，这里再兜一次底
            if (!isInsideDir(packDir, root) || packDir.name == STICKER_THUMB_DIR_NAME) {
                pack.stickers.forEach { failedCopies += "${it.id}（系列号不合法）" }
                done += pack.stickers.size
                onProgress(StickerImportProgress(done, total))
                continue
            }
            packDir.mkdirs()
            val importedStickers = mutableListOf<Sticker>()
            for (sticker in pack.stickers) {
                if (isCancelled()) break
                val dest = File(packDir, sticker.file)
                val thumbFile = File(thumbs, "${sticker.id}.png")
                if (!sticker.id.matches(STICKER_ID_PATTERN) ||
                    !isInsideDir(dest, packDir) ||
                    !isInsideDir(thumbFile, thumbs)
                ) {
                    failedCopies += "${sticker.id}（编号或文件名不合法）"
                    done++
                    onProgress(StickerImportProgress(done, total))
                    continue
                }
                val copied = copyFile(source, sticker.sourcePath, dest)
                val copyError = copied.error
                if (copyError != null) {
                    // 复制失败：这一张跳过，其余继续（不要因为一张坏图中断全部）
                    failedCopies += "${sticker.id}（$copyError）"
                    done++
                    onProgress(StickerImportProgress(done, total))
                    continue
                }
                // W-060：图片内容变了（或第一次导入）就重新生成缩略图；没变才复用已有的
                if (copied.changed) thumbFile.delete()
                val thumbOk = thumbFile.isFile && thumbFile.length() > 0L ||
                    runCatching { thumbnailer.generate(dest, thumbFile) }.getOrDefault(false)
                if (!thumbOk) thumbnailFailures++
                importedStickers += Sticker(
                    id = sticker.id,
                    packId = pack.seriesId,
                    filePath = "${STICKER_DIR_NAME}/${pack.seriesId}/${sticker.file}",
                    thumbPath = "${STICKER_DIR_NAME}/$STICKER_THUMB_DIR_NAME/${sticker.id}.png",
                    format = STICKER_FORMAT_WEBP,
                    animated = sticker.animated,
                    description = sticker.description,
                    detail = sticker.detail,
                    textInImage = sticker.textInImage,
                    textMeaning = sticker.textMeaning,
                    characters = sticker.characters,
                    couple = sticker.couple,
                    emotions = sticker.emotions,
                    useWhen = sticker.useWhen,
                    needsReview = sticker.needsReview,
                )
                done++
                onProgress(StickerImportProgress(done, total))
            }

            if (importedStickers.isNotEmpty()) {
                if (pack.seriesId in plan.conflicts) merged += pack.seriesId
                // 合并导入保留用户设过的「她能用」开关；新系列默认打开（W-006）
                val enabledForHer = repository.pack(pack.seriesId)?.enabledForHer ?: true
                repository.savePack(
                    pack = StickerPack(
                        id = pack.seriesId,
                        name = pack.name,
                        titleOriginal = pack.titleOriginal,
                        characters = pack.characters,
                        animated = pack.animated,
                        hasSoundOriginally = pack.hasSoundOriginally,
                        source = pack.source,
                        licenseNote = pack.licenseNote,
                        sortOrder = pack.sortOrder,
                        enabledForHer = enabledForHer,
                        importedAt = importedAt,
                    ),
                    stickers = importedStickers,
                )
                seriesImported++
                stickersImported += importedStickers.size
            }
        }

        StickerImportReport(
            seriesImported = seriesImported,
            stickersImported = stickersImported,
            skippedSeries = plan.parsed.skippedSeries,
            skippedStickers = plan.parsed.skippedStickers,
            warnings = plan.parsed.warnings,
            mergedSeries = merged,
            cancelled = isCancelled(),
            failedCopies = failedCopies,
            thumbnailFailures = thumbnailFailures,
        )
    }

    /**
     * 复制一张图（W-015）：按**实际读到的字节**计数，超过 [STICKER_MAX_BYTES] 立刻中止（不信 zip 里声明的大小）；
     * 先写 `<文件名>.tmp`，完整写完再替换正式文件，中途失败时旧文件原样保留、半成品删掉。
     *
     * 写完后和已有的正式文件逐字节比较（单张不超过 2 MB）：内容相同就不替换，[CopyOutcome.changed] 为 false，
     * 缩略图可以复用；内容不同或原来没有时替换，changed 为 true（W-060）。
     */
    private fun copyFile(source: StickerSource, from: String, dest: File): CopyOutcome {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        return try {
            dest.parentFile?.mkdirs()
            val input = source.open(from) ?: return CopyOutcome.failed(COPY_FAILED)
            var total = 0L
            input.use { stream ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > STICKER_MAX_BYTES) return CopyOutcome.failed("实际大小超过 2 MB")
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (total == 0L) return CopyOutcome.failed(COPY_FAILED)
            if (dest.isFile && dest.length() == total && sameBytes(tmp, dest)) {
                CopyOutcome(changed = false)
            } else {
                moveReplacing(tmp, dest)
                CopyOutcome(changed = true)
            }
        } catch (e: Exception) {
            CopyOutcome.failed(COPY_FAILED)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun sameBytes(a: File, b: File): Boolean = a.readBytes().contentEquals(b.readBytes())

    /** 复制一张图的结果：[error] 非空表示失败；[changed] 表示正式文件这次被写成了新内容 */
    private class CopyOutcome(val changed: Boolean, val error: String? = null) {
        companion object {
            fun failed(reason: String) = CopyOutcome(changed = false, error = reason)
        }
    }

    /** 用临时文件原子替换正式文件；文件系统不支持原子移动时退回普通替换。 */
    private fun moveReplacing(tmp: File, dest: File) {
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val COPY_FAILED = "复制文件失败"
        const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
