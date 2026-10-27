package whaleui.nightsky.ltd.sticker

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 表情库导入的「源」抽象：目录、zip、系统文件选择器（SAF）三种实现统一成同一套读接口。
 *
 * 解析/校验（[StickerParser]）只依赖这个接口，所以 JVM 单测可以直接用
 * [FileStickerSource]（临时目录）和 [ZipStickerSource]（临时 zip）跑，不需要模拟器。
 *
 * 路径约定：全部用 `/` 分隔的相对路径，根目录是空串 `""`。
 */
interface StickerSource {

    data class Entry(
        /** 相对路径的最后一段（文件名或目录名）。 */
        val name: String,
        val isDirectory: Boolean,
        /** 文件字节数；目录给 0。 */
        val length: Long,
    )

    /** 列出某一层（不递归）。路径不存在时返回空列表。 */
    fun list(path: String): List<Entry>

    fun exists(path: String): Boolean

    /** 文件字节数；不存在返回 -1。 */
    fun length(path: String): Long

    /** 读 UTF-8 文本（去掉可能的 BOM）；不存在、读失败或超过 [STICKER_TEXT_MAX_BYTES] 返回 null。 */
    fun readText(path: String): String?

    /** 只读文件开头 [count] 字节，用来判断 WebP 文件头，不把整张图读进内存。 */
    fun readHead(path: String, count: Int): ByteArray?

    /** 打开文件流（复制文件时用）；不存在返回 null。 */
    fun open(path: String): InputStream?

    companion object {
        const val RIFF_HEADER_SIZE: Int = 16

        /**
         * 按文件内容判断是不是 WebP：`RIFF....WEBP`（不能只看扩展名，任务书 3.1）。
         */
        fun isWebp(head: ByteArray?): Boolean {
            if (head == null || head.size < 12) return false
            val riff = String(head, 0, 4, Charsets.US_ASCII)
            val webp = String(head, 8, 4, Charsets.US_ASCII)
            return riff == "RIFF" && webp == "WEBP"
        }
    }
}

/** 本地目录源（单测、以及导入后内部校验用）。 */
class FileStickerSource(private val root: File) : StickerSource {

    private fun resolve(path: String): File? {
        val clean = path.trim('/').replace('\\', '/')
        if (clean.split('/').any { it == ".." }) return null
        return if (clean.isEmpty()) root else File(root, clean)
    }

    override fun list(path: String): List<StickerSource.Entry> {
        val dir = resolve(path) ?: return emptyList()
        val children = dir.listFiles() ?: return emptyList()
        return children
            .sortedBy { it.name }
            .map {
                StickerSource.Entry(
                    name = it.name,
                    isDirectory = it.isDirectory,
                    length = if (it.isDirectory) 0L else it.length(),
                )
            }
    }

    override fun exists(path: String): Boolean = resolve(path)?.exists() == true

    override fun length(path: String): Long {
        val file = resolve(path) ?: return -1L
        return if (file.isFile) file.length() else -1L
    }

    override fun readText(path: String): String? {
        val file = resolve(path) ?: return null
        if (!file.isFile) return null
        return runCatching { file.inputStream().use { readTextLimited(it) } }.getOrNull()
    }

    override fun readHead(path: String, count: Int): ByteArray? {
        val file = resolve(path) ?: return null
        if (!file.isFile) return null
        return runCatching {
            file.inputStream().use { readAtMost(it, count) }
        }.getOrNull()
    }

    override fun open(path: String): InputStream? {
        val file = resolve(path) ?: return null
        if (!file.isFile) return null
        return runCatching { file.inputStream() }.getOrNull()
    }
}

/**
 * zip 源：把整个 zip 的条目先索引一遍，然后再按上面的统一接口读。
 *
 * 注意：[ZipFile] 不支持并发读，导入在单个后台协程里顺序跑，所以这里不加锁。
 */
class ZipStickerSource(private val zip: ZipFile) : StickerSource {

    /** 规范化后的条目名（去掉目录末尾的 `/`）→ ZipEntry。 */
    private val files: Map<String, java.util.zip.ZipEntry> = zip.entries().asSequence()
        .filter { !it.isDirectory }
        .associateBy { it.name.trimEnd('/').replace('\\', '/') }

    private val dirs: Set<String> = buildSet {
        add("")
        files.keys.forEach { name ->
            var index = name.indexOf('/')
            while (index >= 0) {
                add(name.substring(0, index))
                index = name.indexOf('/', index + 1)
            }
        }
    }

    override fun list(path: String): List<StickerSource.Entry> {
        val prefix = if (path.isBlank()) "" else path.trimEnd('/') + "/"
        val entries = linkedMapOf<String, StickerSource.Entry>()
        dirs.filter { it.isNotEmpty() && it.startsWith(prefix) }.forEach { dir ->
            val rest = dir.removePrefix(prefix)
            if (rest.isNotEmpty() && '/' !in rest) {
                entries[rest] = StickerSource.Entry(name = rest, isDirectory = true, length = 0L)
            }
        }
        files.entries
            .filter { it.key.startsWith(prefix) }
            .forEach { (name, entry) ->
                val rest = name.removePrefix(prefix)
                if (rest.isNotEmpty() && '/' !in rest) {
                    entries[rest] = StickerSource.Entry(
                        name = rest,
                        isDirectory = false,
                        length = entry.size.coerceAtLeast(0L),
                    )
                }
            }
        return entries.values.sortedBy { it.name }
    }

    override fun exists(path: String): Boolean = files.containsKey(normalize(path))

    override fun length(path: String): Long =
        files[normalize(path)]?.size?.coerceAtLeast(0L) ?: -1L

    override fun readText(path: String): String? {
        val entry = files[normalize(path)] ?: return null
        // 按实际解压出的字节限流（entry.size 是中央目录里的声明值，可以伪造，W-015）
        return runCatching { zip.getInputStream(entry).use { readTextLimited(it) } }.getOrNull()
    }

    override fun readHead(path: String, count: Int): ByteArray? {
        val entry = files[normalize(path)] ?: return null
        return runCatching {
            zip.getInputStream(entry).use { readAtMost(it, count) }
        }.getOrNull()
    }

    override fun open(path: String): InputStream? {
        val entry = files[normalize(path)] ?: return null
        return runCatching { zip.getInputStream(entry) }.getOrNull()
    }

    private fun normalize(path: String): String =
        path.trim('/').trimEnd('/').replace('\\', '/')
}

/**
 * 读 UTF-8 文本（去掉 BOM），最多 [maxBytes] 字节：按实际读到的字节计数，超过就返回 null，
 * 不会把一个伪造大小的超大 `series.json` / `index.json` 整个读进内存。
 */
internal fun readTextLimited(input: InputStream, maxBytes: Int = STICKER_TEXT_MAX_BYTES): String? {
    val bytes = readAtMost(input, maxBytes + 1)
    if (bytes.size > maxBytes) return null
    return bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
}

/** 最多读 [count] 字节；流比 count 短就返回实际读到的。 */
internal fun readAtMost(input: InputStream, count: Int): ByteArray {
    val buffer = ByteArray(count)
    var total = 0
    while (total < count) {
        val read = input.read(buffer, total, count - total)
        if (read <= 0) break
        total += read
    }
    return buffer.copyOf(total)
}
