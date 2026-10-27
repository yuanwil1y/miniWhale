package whaleui.nightsky.ltd.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.sticker.FileStickerSource
import whaleui.nightsky.ltd.sticker.InMemoryStickerRepository
import whaleui.nightsky.ltd.sticker.STICKER_INDEX_FILE
import whaleui.nightsky.ltd.sticker.StickerImporter
import whaleui.nightsky.ltd.sticker.StickerTestData
import whaleui.nightsky.ltd.sticker.ZipStickerSource

/** 「导出全部数据」的表情库部分（W-017）：边读边写，且导出 → 导入往返内容一致。 */
class StickerBundleExporterTest {

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("whale-export").toFile().apply { deleteOnExit() }

    /** 先用导入器建一个真实的表情库（filesDir/stickers/...）。 */
    private fun library(): Pair<File, InMemoryStickerRepository> = runBlocking {
        val sourceRoot = tempDir()
        StickerTestData.writeSeries(sourceRoot, "01", stickerCount = 2)
        StickerTestData.writeSeries(sourceRoot, "02", stickerCount = 3)
        val filesDir = tempDir()
        val repository = InMemoryStickerRepository()
        val importer = StickerImporter(filesDir, repository, StickerTestData.fakeThumbnailer())
        importer.execute(FileStickerSource(sourceRoot), importer.inspect(FileStickerSource(sourceRoot)))
        filesDir to repository
    }

    private fun zipEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    @Test
    fun `sticker files are streamed from disk while the zip is written`() = runBlocking {
        val (filesDir, repository) = library()
        val entries = StickerBundleExporter(filesDir, repository).entries()

        // 收集条目之后、写 zip 之前改掉一张图：边读边写的话 zip 里应该是新内容
        val sticker = repository.stickers().first { it.packId == "01" }
        val file = File(filesDir, sticker.filePath)
        val changed = StickerTestData.webpBytes(payload = 77)
        file.writeBytes(changed)

        val out = ByteArrayOutputStream()
        writeBundleZip(out, entries)
        val zipped = zipEntries(out.toByteArray())

        assertArrayEquals(
            "表情图片应该在写 zip 时才从磁盘读（不提前整读进内存）",
            changed,
            zipped.getValue("01/${file.name}"),
        )
    }

    @Test
    fun `exported sticker library round trips through the importer`() = runBlocking {
        val (filesDir, repository) = library()
        val out = ByteArrayOutputStream()
        val markdown = BundleFile(BundleFormat.MEMORY_MD, "## 关于你\n\n- 甲\n".toByteArray(Charsets.UTF_8))
        writeBundleZip(out, listOf(markdown) + StickerBundleExporter(filesDir, repository).entries())
        val bytes = out.toByteArray()

        // 结构：md 在前，index.json，然后每个系列的图片 + series.json
        val names = zipEntries(bytes).keys.toList()
        assertEquals(BundleFormat.MEMORY_MD, names[0])
        assertEquals(STICKER_INDEX_FILE, names[1])
        assertEquals(1 + 1 + (2 + 1) + (3 + 1), names.size)
        assertTrue(names.contains("01/series.json"))
        assertTrue(names.contains("02/series.json"))

        // 导入记忆能从同一个包里读回 md
        val memory = ImportBundleReader.extractMarkdown(bytes, "all.zip", BundleFormat::isMemoryMarkdown)
        assertEquals("## 关于你\n\n- 甲\n", memory.single().content)

        // 表情导入能从同一个包里恢复全部表情，文件逐字节一致
        val zipFile = File(tempDir(), "all.zip").apply { writeBytes(bytes) }
        val restoredDir = tempDir()
        val restored = InMemoryStickerRepository()
        val importer = StickerImporter(restoredDir, restored, StickerTestData.fakeThumbnailer())
        ZipFile(zipFile).use { zip ->
            val report = importer.execute(ZipStickerSource(zip), importer.inspect(ZipStickerSource(zip)))
            assertEquals(5, report.stickersImported)
        }
        assertEquals(repository.stickers().map { it.id }.sorted(), restored.stickers().map { it.id }.sorted())
        repository.stickers().forEach { original ->
            val copy = restored.sticker(original.id)!!
            assertEquals(original.description, copy.description)
            assertEquals(original.detail, copy.detail)
            assertEquals(original.emotions, copy.emotions)
            assertArrayEquals(
                File(filesDir, original.filePath).readBytes(),
                File(restoredDir, copy.filePath).readBytes(),
            )
        }
        assertEquals(repository.packs().map { it.name }, restored.packs().map { it.name })
    }
}
