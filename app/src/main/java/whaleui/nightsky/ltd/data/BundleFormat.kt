package whaleui.nightsky.ltd.data

import java.time.LocalDate

/** 导出包里的一个条目：[name] 是 zip 内路径。 */
sealed interface BundleEntry {
    val name: String
}

/**
 * 一份待写入导出包的文件。导入端只读文本（UTF-8），所以这里统一按字节存。
 */
data class BundleFile(override val name: String, val bytes: ByteArray) : BundleEntry

/** 直接从磁盘文件写进导出包的条目（表情图片）：写 zip 时边读边写，不整读进内存。 */
data class BundleFileRef(override val name: String, val file: java.io.File) : BundleEntry

/**
 * 导出包内部的统一路径与命名。
 *
 * 导出和导入共用这一份定义：导出按它命名，导入按它查找，
 * 这样「导出 → 换设备 → 导入」走的是同一套约定，不需要额外转换。
 *
 * 包结构：
 * ```
 * soul.md
 * user.md
 * memory/memories.md
 * memory/memories.json
 * diary/2026-10-25.md
 * chat/2026-10-25_miniWhale.md
 * ```
 */
object BundleFormat {

    const val SOUL = "soul.md"
    const val USER = "user.md"

    const val MEMORY_DIR = "memory"
    const val MEMORY_MD = "$MEMORY_DIR/memories.md"
    const val MEMORY_JSON = "$MEMORY_DIR/memories.json"

    const val DIARY_DIR = "diary"
    const val CHAT_DIR = "chat"

    fun diaryPath(date: LocalDate): String = "$DIARY_DIR/$date.md"

    fun chatPath(fileName: String): String = "$CHAT_DIR/$fileName"

    /** 归档里的路径拆成段（兼容反斜杠、忽略空段）。 */
    fun segments(path: String): List<String> = path.replace('\\', '/')
        .split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "." && it != ".." }

    /** `memory/memories.md`（也容忍只叫 `memories.md` 的文件）。 */
    fun isMemoryMarkdown(path: String): Boolean =
        segments(path).lastOrNull()?.equals("memories.md", ignoreCase = true) == true

    /**
     * `chat/` 目录下的 md。只看目录和扩展名、不看文件名，所以旧版导出的 `<日期>_小鲸鱼.md`
     * 和现在的 `<日期>_miniWhale.md` 都能导入。
     */
    fun isChatMarkdown(path: String): Boolean {
        val segments = segments(path)
        val file = segments.lastOrNull() ?: return false
        return file.endsWith(".md", ignoreCase = true) &&
            segments.dropLast(1).any { it.equals(CHAT_DIR, ignoreCase = true) }
    }
}
