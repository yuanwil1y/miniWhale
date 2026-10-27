package whaleui.nightsky.ltd.data

import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import whaleui.nightsky.ltd.sticker.STICKER_INDEX_FILE
import whaleui.nightsky.ltd.sticker.STICKER_SERIES_FILE
import whaleui.nightsky.ltd.sticker.STICKER_SERIES_FORMAT
import whaleui.nightsky.ltd.sticker.STICKER_SERIES_VERSION
import whaleui.nightsky.ltd.sticker.Sticker
import whaleui.nightsky.ltd.sticker.StickerPack
import whaleui.nightsky.ltd.sticker.StickerRepository
import whaleui.nightsky.ltd.domain.settings.ExportResult

/**
 * 「导出全部数据」里的表情库部分：导出成 `StickerParser` 能直接读回的格式
 * （根 `index.json` + 每个系列一个目录）。
 *
 * 这样把整个导出包在「设置 → 表情包 → 导入」里选一遍，就能在另一台设备恢复所有表情包。
 * 从 [FileExportService] 拆出来，是为了在 JVM 单测里跑「导出 → 导入」往返。
 */
internal class StickerBundleExporter(
    private val filesDir: File,
    private val stickerRepository: StickerRepository,
) {

    suspend fun entries(): List<BundleEntry> {
        val packs = stickerRepository.packs()
        if (packs.isEmpty()) return emptyList()
        val byPack = stickerRepository.stickers().groupBy { it.packId }
        val usedFolders = mutableSetOf<String>()
        val seriesFiles = mutableListOf<BundleEntry>()
        val listedFolders = mutableListOf<String>()

        packs.sortedBy { it.sortOrder }.forEachIndexed { index, pack ->
            val stickers = byPack[pack.id].orEmpty()
            if (stickers.isEmpty()) return@forEachIndexed
            val folder = uniqueFolderName(pack, index, usedFolders)

            val exported = mutableListOf<ExportedSticker>()
            for (sticker in stickers.sortedBy { it.id }) {
                val source = File(filesDir, sticker.filePath)
                if (!source.isFile || source.length() == 0L) continue
                val fileName = source.name.replace('/', '_').replace('\\', '_')
                // 只记路径，写 zip 时边读边写（W-017：不把整个表情库读进内存）
                seriesFiles += BundleFileRef(name = "$folder/$fileName", file = source)
                exported += ExportedSticker(sticker = sticker, fileName = fileName)
            }
            if (exported.isEmpty()) return@forEachIndexed
            listedFolders += folder
            seriesFiles += BundleFile(
                name = "$folder/$STICKER_SERIES_FILE",
                bytes = buildSeriesJson(pack, exported).toByteArray(Charsets.UTF_8),
            )
        }

        if (listedFolders.isEmpty()) return emptyList()
        val index = buildString {
            append("{\"format\":")
            appendJsonString("whale-sticker-library")
            append(",\"version\":").append(STICKER_SERIES_VERSION).append(",\"series\":[")
            listedFolders.forEachIndexed { index, folder ->
                if (index > 0) append(',')
                append("{\"folder\":")
                appendJsonString(folder)
                append('}')
            }
            append("]}")
        }
        return listOf(BundleFile(name = STICKER_INDEX_FILE, bytes = index.toByteArray(Charsets.UTF_8))) +
            seriesFiles
    }

    private fun uniqueFolderName(pack: StickerPack, index: Int, used: MutableSet<String>): String {
        val base = pack.id.ifBlank { "pack-${index + 1}" }
            .replace(NonFolderChars, "_")
            .trim('_')
            .ifBlank { "pack-${index + 1}" }
        var candidate = base
        var suffix = 2
        while (!used.add(candidate)) {
            candidate = "${base}_$suffix"
            suffix += 1
        }
        return candidate
    }

    /** 按表情包格式 v2 的字段（见 `docs/IMPORT_FORMAT.md`）写一个系列的 `series.json`。 */
    private fun buildSeriesJson(pack: StickerPack, stickers: List<ExportedSticker>): String = buildString {
        append("{\"format\":")
        appendJsonString(STICKER_SERIES_FORMAT)
        append(",\"version\":").append(STICKER_SERIES_VERSION)
        append(",\"series_id\":")
        appendJsonString(pack.id)
        append(",\"title_original\":")
        appendJsonString(pack.titleOriginal)
        append(",\"name\":")
        appendJsonString(pack.name)
        append(",\"characters\":")
        appendJsonStringArray(pack.characters)
        append(",\"animated\":").append(pack.animated)
        append(",\"has_sound_originally\":").append(pack.hasSoundOriginally)
        append(",\"source\":")
        appendJsonString(pack.source)
        append(",\"license_note\":")
        appendJsonString(pack.licenseNote)
        append(",\"stickers\":[")
        stickers.forEachIndexed { index, exported ->
            if (index > 0) append(',')
            appendStickerJson(pack, exported)
        }
        append("]}")
    }

    private fun StringBuilder.appendStickerJson(pack: StickerPack, exported: ExportedSticker) {
        val sticker = exported.sticker
        append("{\"file\":")
        appendJsonString(exported.fileName)
        append(",\"id\":")
        appendJsonString(sticker.id)
        append(",\"description\":")
        appendJsonString(sticker.description)
        append(",\"detail\":")
        appendJsonString(sticker.detail)
        append(",\"text_in_image\":")
        appendJsonString(sticker.textInImage)
        append(",\"text_meaning\":")
        appendJsonString(sticker.textMeaning)
        append(",\"characters\":")
        appendJsonStringArray(sticker.characters.ifEmpty { pack.characters })
        append(",\"couple\":").append(sticker.couple)
        append(",\"emotions\":")
        appendJsonStringArray(sticker.emotions)
        append(",\"use_when\":")
        appendJsonString(sticker.useWhen)
        append(",\"needs_review\":").append(sticker.needsReview)
        append('}')
    }

    private fun StringBuilder.appendJsonStringArray(values: List<String>) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            appendJsonString(value)
        }
        append(']')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u%04x".format(ch.code))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }

    /** 一张已经复制进导出包的表情。 */
    private data class ExportedSticker(val sticker: Sticker, val fileName: String)

    private companion object {
        /** 表情系列目录名里不允许出现的字符（和 StickerParser 的 series_id 规则一致）。 */
        val NonFolderChars = Regex("[^0-9A-Za-z_-]")
    }
}

/**
 * 把导出条目按顺序写进 zip；返回实际写给 [output] 的字节数（压缩后）。
 *
 * [BundleFileRef] 边读边写（`copyTo`），不会把整个表情库读进内存。
 */
internal fun writeBundleZip(output: OutputStream, files: List<BundleEntry>): Long {
    val counting = CountingOutputStream(output)
    ZipOutputStream(counting).use { zip ->
        files.forEach { file ->
            zip.putNextEntry(ZipEntry(file.name))
            when (file) {
                is BundleFile -> zip.write(file.bytes)
                is BundleFileRef -> file.file.inputStream().use { it.copyTo(zip) }
            }
            zip.closeEntry()
        }
        zip.finish()
    }
    return counting.count
}

/** 只统计真正交给底层流的字节数，用来回报 [ExportResult.Success.bytes]。 */
private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {

    var count: Long = 0L
        private set

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        count += length.toLong()
    }

    override fun write(byte: Int) {
        out.write(byte)
        count += 1L
    }
}
