package whaleui.nightsky.ltd.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** 导出包里的一个文本文件，内容已按 UTF-8 解码。 */
data class BundleText(val name: String, val content: String)

/**
 * 读取导出包：`zip` 里按路径挑文件；如果选的是单个 `.md`，直接当文本读。
 *
 * 导入记忆、导入聊天记录、导入日记都走这里，保证和导出端的命名对得上。
 * 不读 tar.gz（数据整形好之后用 zip 或单个 md）。
 *
 * 所有读取都有上限，超限抛 [IllegalArgumentException]（界面显示「导入失败：…」）：
 * - 选中的文件本身最多 [MAX_ARCHIVE_BYTES]，用 [readStream] 边读边数，超过立刻停，不先整读再判断；
 * - zip 里所有条目**实际解压出来**的字节累计最多 [MAX_INFLATED_BYTES]（不信条目头里声明的大小）；
 * - 挑中留下的文本累计最多 [MAX_KEPT_BYTES]，单个最多 [MAX_ENTRY_BYTES]（超过的那个跳过）。
 */
object ImportBundleReader {

    /** 单个文本文件上限。 */
    const val MAX_ENTRY_BYTES: Int = 4 * 1024 * 1024

    /** 整个包上限（导出包通常几十 KB，带表情库也就几十 MB，留足余量）。 */
    const val MAX_ARCHIVE_BYTES: Int = 64 * 1024 * 1024

    /**
     * 解压累计上限（所有条目，包括没挑中的）。表情图片几乎不可压缩，正常导出包解压后和压缩前差不多大，
     * 64 MB 的包解出几百 MB 只可能是压缩炸弹。
     */
    const val MAX_INFLATED_BYTES: Long = 256L * 1024 * 1024

    /** 挑中留在内存里的文本累计上限。 */
    const val MAX_KEPT_BYTES: Long = 64L * 1024 * 1024

    private const val MAX_ENTRIES = 5_000

    private const val BUFFER_SIZE = 64 * 1024

    /**
     * @param bytes 文件全部字节
     * @param displayName 文件名（单个 md 时用来生成 [BundleText.name]）
     * @param selector 在 zip 里挑哪些路径
     */
    fun extractMarkdown(
        bytes: ByteArray,
        displayName: String,
        selector: (path: String) -> Boolean,
    ): List<BundleText> {
        require(bytes.size <= MAX_ARCHIVE_BYTES) { "文件太大，不像是导出包" }
        if (!isZip(bytes)) {
            val name = displayName.ifBlank { "import.md" }
            // 不是 zip 就当成单个文本文件，格式对不对交给解析器判断。
            return listOf(BundleText(name = name, content = bytes.toString(Charsets.UTF_8)))
        }
        val result = mutableListOf<BundleText>()
        val budget = InflateBudget()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                count += 1
                if (count > MAX_ENTRIES) throw IllegalArgumentException("压缩包条目太多，不像是导出包")
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                val wanted = selector(name) && entry.size <= MAX_ENTRY_BYTES
                // 没挑中的条目也要自己读完并计数：交给 nextEntry 跳过的话，解压量不受控制。
                val content = consumeEntry(zip, keep = wanted, budget = budget) ?: continue
                budget.keep(content.size)
                result += BundleText(name = name, content = content.toString(Charsets.UTF_8))
            }
        }
        return result
    }

    /**
     * 把选中的文件读成字节：边读边数，超过 [limit] 立刻停下并拒绝（不会先把整个大文件读进内存）。
     */
    fun readStream(input: InputStream, limit: Int = MAX_ARCHIVE_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IllegalArgumentException("文件太大，不像是导出包")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() &&
            bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 3.toByte() &&
            bytes[3] == 4.toByte()

    /**
     * 读完当前条目，每一块都记进 [budget]；[keep] 时把内容攒下来返回，
     * 单个超过 [MAX_ENTRY_BYTES] 就丢掉已攒的内容（返回 null），但仍然读完计数。
     */
    private fun consumeEntry(input: InputStream, keep: Boolean, budget: InflateBudget): ByteArray? {
        var out: ByteArrayOutputStream? = if (keep) ByteArrayOutputStream() else null
        val buffer = ByteArray(BUFFER_SIZE)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            budget.inflate(read)
            size += read
            if (out != null && size > MAX_ENTRY_BYTES) out = null
            out?.write(buffer, 0, read)
        }
        return out?.toByteArray()
    }

    /** 解压累计量 / 留下的文本累计量。 */
    private class InflateBudget {
        private var inflated = 0L
        private var kept = 0L

        fun inflate(bytes: Int) {
            inflated += bytes
            if (inflated > MAX_INFLATED_BYTES) {
                throw IllegalArgumentException("压缩包解压后太大，不像是导出包")
            }
        }

        fun keep(bytes: Int) {
            kept += bytes
            if (kept > MAX_KEPT_BYTES) throw IllegalArgumentException("压缩包里的文本太多，不像是导出包")
        }
    }
}
