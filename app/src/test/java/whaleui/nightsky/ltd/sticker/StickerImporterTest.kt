package whaleui.nightsky.ltd.sticker

import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入的可写部分：复制文件、生成缩略图、写库、合并语义、可取消（任务书 3.1 / 3.2）。 */
class StickerImporterTest {

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("whale-sticker-import").toFile().apply { deleteOnExit() }

    private fun importer(
        filesDir: File,
        repository: StickerRepository,
        thumbnailer: StickerThumbnailer = StickerTestData.fakeThumbnailer(),
    ) = StickerImporter(
        filesDir = filesDir,
        repository = repository,
        thumbnailer = thumbnailer,
        clock = { 1_700_000_000_000L },
    )

    @Test
    fun `imports a whole library, copies files and writes rows`() = runBlocking {
        val sourceRoot = tempDir()
        StickerTestData.writeSeries(sourceRoot, "01", stickerCount = 2)
        StickerTestData.writeSeries(sourceRoot, "02", stickerCount = 3)
        val filesDir = tempDir()
        val repository = InMemoryStickerRepository()
        val importer = importer(filesDir, repository, StickerTestData.fakeThumbnailer())

        val plan = importer.inspect(FileStickerSource(sourceRoot))
        assertEquals(5, plan.stickerCount)
        assertTrue(plan.isEmpty.not())
        assertTrue(plan.conflicts.isEmpty())

        val report = importer.execute(FileStickerSource(sourceRoot), plan)

        assertEquals(2, report.seriesImported)
        assertEquals(5, report.stickersImported)
        assertFalse(report.cancelled)
        assertEquals(2, repository.packs().size)
        assertEquals(5, repository.stickers().size)
        // 文件真的复制到 filesDir/stickers/<系列号>/ 下
        val sticker = repository.stickers().first()
        val copied = File(filesDir, sticker.filePath)
        assertTrue("复制后的文件应该存在：${copied.path}", copied.isFile)
        assertTrue(copied.length() > 0L)
        val thumb = File(filesDir, sticker.thumbPath)
        assertTrue("缩略图应该生成：${thumb.path}", thumb.isFile)
        assertEquals(STICKER_FORMAT_WEBP, sticker.format)
        assertEquals("webp", sticker.format)
        assertEquals(1_700_000_000_000L, repository.pack(sticker.packId)?.importedAt)
    }

    @Test
    fun `re-import keeps her send statistics for the same sticker id`() = runBlocking {
        val sourceRoot = tempDir()
        StickerTestData.writeSeries(sourceRoot, "01", stickerCount = 1)
        val filesDir = tempDir()
        val repository = InMemoryStickerRepository()
        val importer = importer(filesDir, repository)

        importer.execute(
            FileStickerSource(sourceRoot),
            importer.inspect(FileStickerSource(sourceRoot)),
        )
        val id = repository.stickers().single().id
        repository.markSentByHer(id, 1_700_000_123_000L)
        assertEquals(1, repository.sticker(id)?.sentByHerCount)

        // 再导入一次：说明更新，但发送次数/时间不能被清掉
        importer.execute(
            FileStickerSource(sourceRoot),
            importer.inspect(FileStickerSource(sourceRoot)),
        )
        assertEquals(1, repository.sticker(id)?.sentByHerCount)
        assertEquals(1_700_000_123_000L, repository.sticker(id)?.lastSentByHerAt)
    }

    @Test
    fun `re-import regenerates the thumbnail only when the image changed`() = runBlocking {
        // W-060：以前缩略图文件在就直接复用，同编号换了图之后面板还是旧图
        val sourceRoot = tempDir()
        val folder = StickerTestData.writeSeries(sourceRoot, "01", stickerCount = 2)
        val filesDir = tempDir()
        val repository = InMemoryStickerRepository()
        val generated = mutableListOf<String>()
        val recording = StickerThumbnailer { image, dest ->
            generated += dest.nameWithoutExtension
            dest.parentFile?.mkdirs()
            dest.writeBytes(image.readBytes().copyOf(8))
            true
        }
        val importer = importer(filesDir, repository, recording)
        importer.execute(FileStickerSource(sourceRoot), importer.inspect(FileStickerSource(sourceRoot)))
        assertEquals(2, generated.size)

        // 内容不变：两张都复用
        generated.clear()
        importer.execute(FileStickerSource(sourceRoot), importer.inspect(FileStickerSource(sourceRoot)))
        assertEquals(emptyList<String>(), generated)

        // 换掉其中一张图：只重新生成这一张，正式文件也换成新内容
        val changedSource = folder.listFiles()!!.filter { it.name.endsWith(".webp") }.minBy { it.name }
        val newBytes = StickerTestData.webpBytes(payload = 64)
        changedSource.writeBytes(newBytes)
        importer.execute(FileStickerSource(sourceRoot), importer.inspect(FileStickerSource(sourceRoot)))
        val changed = repository.stickers().single { it.filePath.endsWith(changedSource.name) }
        assertEquals(listOf(changed.id), generated)
        assertTrue(File(filesDir, changed.filePath).readBytes().contentEquals(newBytes))
    }

    @Test
    fun `second import of the same series reports a conflict and merges`() = runBlocking {
        val sourceRoot = tempDir()
        StickerTestData.writeSeries(sourceRoot, "01", stickerCount = 2)
        val filesDir = tempDir()
        val repository = InMemoryStickerRepository()
        val importer = importer(filesDir, repository)

        val first = importer.inspect(FileStickerSource(sourceRoot))
        importer.execute(FileStickerSource(sourceRoot), first)

        val second = importer.inspect(FileStickerSource(sourceRoot))
        assertEquals(listOf("01"), second.conflicts)
        val report = importer.execute(FileStickerSource(sourceRoot), second)
        assertEquals(listOf("01"), report.mergedSeries)
        // 合并导入不删已有的：还是 2 张
        assertEquals(2, repository.stickers().size)
    }

    @Test
    fun `import can be cancelled midway and keeps what was already copied`() = runBlocking {
        val sourceRoot = tempDir()
        StickerTestData.writeSeries(sourceRoot, "01", stickerCount = 2)
        StickerTestData.writeSeries(sourceRoot, "02", stickerCount = 2)
        val filesDir = tempDir()
        val repository = InMemoryStickerRepository()
        val importer = importer(filesDir, repository)

        val plan = importer.inspect(FileStickerSource(sourceRoot))
        // 复制完第一个系列（2 张图 + 2 张缩略图 = 4 个文件）之后取消
        val report = importer.execute(
            source = FileStickerSource(sourceRoot),
            plan = plan,
            isCancelled = {
                File(filesDir, "stickers").walkTopDown().count { it.isFile } >= 4
            },
        )

        assertTrue(report.cancelled)
        assertEquals(1, report.seriesImported)
        assertEquals(2, report.stickersImported)
        assertEquals(1, repository.packs().size)
    }

    @Test
    fun `import report lists skipped series and stickers with reasons`() = runBlocking {
        val sourceRoot = tempDir()
        // 不写 index.json：走扫描路径，才会看到没有 series.json 的目录
        StickerTestData.writeSeries(sourceRoot, "01", stickerCount = 1, includeIndex = false)
        File(sourceRoot, "02_没有说明").apply { mkdirs() }
        File(sourceRoot, "02_没有说明/1000001.webp").writeBytes(StickerTestData.webpBytes())
        val filesDir = tempDir()
        val repository = InMemoryStickerRepository()
        val importer = importer(filesDir, repository)

        val report = importer.execute(
            FileStickerSource(sourceRoot),
            importer.inspect(FileStickerSource(sourceRoot)),
        )

        assertEquals(1, report.seriesImported)
        assertEquals(1, report.skippedSeries.size)
        assertTrue(report.summary().contains("导入 1 个系列、1 张"))
        assertTrue(report.details().any { it.contains("缺少 series.json") })
    }

    @Test
    fun `imports from a zip file`() = runBlocking {
        val sourceRoot = tempDir()
        StickerTestData.writeSeries(sourceRoot, "01", stickerCount = 2)
        StickerTestData.writeSeries(sourceRoot, "08", stickerCount = 1)
        val zipFile = File(tempDir(), "library.zip")
        StickerTestData.zipDirectory(sourceRoot, zipFile)
        val filesDir = tempDir()
        val repository = InMemoryStickerRepository()
        val importer = importer(filesDir, repository)

        ZipFile(zipFile).use { zip ->
            val report = importer.execute(
                ZipStickerSource(zip),
                importer.inspect(ZipStickerSource(zip)),
            )
            assertEquals(2, report.seriesImported)
            assertEquals(3, report.stickersImported)
        }
        assertEquals(3, repository.stickers().size)
    }

    @Test
    fun `thumbnail failures do not abort the import`() = runBlocking {
        val sourceRoot = tempDir()
        StickerTestData.writeSeries(sourceRoot, "01", stickerCount = 2)
        val filesDir = tempDir()
        val repository = InMemoryStickerRepository()
        val importer = importer(filesDir, repository, thumbnailer = StickerThumbnailer { _, _ -> false })

        val report = importer.execute(
            FileStickerSource(sourceRoot),
            importer.inspect(FileStickerSource(sourceRoot)),
        )

        assertEquals(2, report.stickersImported)
        assertEquals(2, report.thumbnailFailures)
        assertTrue(report.details().any { it.contains("缩略图") })
    }
}
