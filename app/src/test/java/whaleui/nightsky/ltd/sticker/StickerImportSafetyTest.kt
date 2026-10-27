package whaleui.nightsky.ltd.sticker

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表情导入的安全边界（F2）：
 * - W-014 编号白名单、落盘路径必须在 `filesDir/stickers` 内；
 * - W-015 按实际字节限流、文本上限、先写 `.tmp` 再替换；
 * - W-016 检查阶段在 IO 线程、单个系列读失败不拖垮整次解析；
 * - W-006 合并导入保留「她能用」开关。
 */
class StickerImportSafetyTest {

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("whale-sticker-safety").toFile().apply { deleteOnExit() }

    private fun importer(filesDir: File, repository: StickerRepository = InMemoryStickerRepository()) =
        StickerImporter(
            filesDir = filesDir,
            repository = repository,
            thumbnailer = StickerTestData.fakeThumbnailer(),
            clock = { 1_700_000_000_000L },
        )

    /** 写一个系列文件夹；[stickers] 是 (编号, 文件名, 文件内容)。 */
    private fun writeCustomSeries(
        root: File,
        folder: String,
        seriesId: String,
        stickers: List<Triple<String, String, ByteArray>>,
        padding: String = "",
    ) {
        val dir = File(root, folder).apply { mkdirs() }
        val items = stickers.joinToString(",\n") { (id, file, bytes) ->
            File(dir, file).writeBytes(bytes)
            """{"id": "$id", "file": "$file", "description": "说明", "detail": "详细说明", "emotions": ["开心"]}"""
        }
        File(dir, STICKER_SERIES_FILE).writeText(
            """
            {"format": "whale-sticker-series", "version": 2, "series_id": "$seriesId",
             "title_original": "测试", "name": "测试", "stickers": [
            $items
            ]}
            """.trimIndent() + padding,
        )
    }

    // ---------------------------------------------------------------- W-014

    @Test
    fun `sticker ids with path parts are skipped and nothing is written outside filesDir`() = runBlocking {
        val sourceRoot = tempDir()
        writeCustomSeries(
            sourceRoot,
            folder = "01_测试",
            seriesId = "01",
            stickers = listOf(
                Triple("01-1000001", "1000001.webp", StickerTestData.webpBytes()),
                Triple("01-x/a", "a.webp", StickerTestData.webpBytes()),
                Triple("01-x/../../../escape", "b.webp", StickerTestData.webpBytes()),
            ),
        )
        val filesDir = tempDir()
        val repository = InMemoryStickerRepository()
        val importer = importer(filesDir, repository)

        val plan = importer.inspect(FileStickerSource(sourceRoot))
        val report = importer.execute(FileStickerSource(sourceRoot), plan)

        assertFalse("不能在 filesDir 下落盘：escape.png", File(filesDir, "escape.png").exists())
        assertFalse(File(filesDir, "$STICKER_DIR_NAME/escape.png").exists())
        assertFalse(File(filesDir, "$STICKER_DIR_NAME/$STICKER_THUMB_DIR_NAME/01-x").exists())
        assertEquals("合法编号照常导入", listOf("01-1000001"), repository.stickers().map { it.id })
        assertEquals(1, report.stickersImported)
        val skipped = report.skippedStickers.map { it.subject }
        assertTrue("非法编号要记进跳过原因：$skipped", skipped.contains("01-x/a"))
        assertTrue("非法编号要记进跳过原因：$skipped", skipped.contains("01-x/../../../escape"))
    }

    @Test
    fun `importer refuses destinations outside the stickers folder even with a hand made plan`() = runBlocking {
        val sourceRoot = tempDir()
        File(sourceRoot, "01").mkdirs()
        File(sourceRoot, "01/b.webp").writeBytes(StickerTestData.webpBytes())
        val filesDir = tempDir()
        val repository = InMemoryStickerRepository()
        val importer = importer(filesDir, repository)
        val hostile = StickerParser.ParsedSticker(
            id = "01-x/../../../escape",
            file = "b.webp",
            sourcePath = "01/b.webp",
            animated = true,
            description = "说明",
            detail = "详细说明",
            textInImage = "",
            textMeaning = "",
            characters = emptyList(),
            couple = false,
            emotions = listOf("开心"),
            useWhen = "",
            needsReview = false,
        )
        val plan = StickerImportPlan(
            parsed = StickerParser.Result(
                packs = listOf(
                    StickerParser.ParsedPack(
                        seriesId = "01", folder = "01", name = "测试", titleOriginal = "测试",
                        characters = emptyList(), animated = true, hasSoundOriginally = false,
                        source = "", licenseNote = "", sortOrder = 0, stickers = listOf(hostile),
                    ),
                ),
            ),
            conflicts = emptyList(),
        )

        val report = importer.execute(FileStickerSource(sourceRoot), plan)

        assertFalse("缩略图不能写到 filesDir/escape.png", File(filesDir, "escape.png").exists())
        assertEquals(0, report.stickersImported)
        assertTrue(repository.stickers().isEmpty())
    }

    // ---------------------------------------------------------------- W-015

    @Test
    fun `zip entry bigger than its declared size is not copied`() = runBlocking {
        val sourceRoot = tempDir()
        writeCustomSeries(
            sourceRoot,
            folder = "01_测试",
            seriesId = "01",
            stickers = listOf(
                Triple("01-1001", "1001.webp", StickerTestData.webpBytes()),
                Triple("01-1002", "1002.webp", StickerTestData.webpBytes(payload = 3 * 1024 * 1024)),
            ),
        )
        val zipFile = File(tempDir(), "lying.zip")
        StickerTestData.zipDirectory(sourceRoot, zipFile)
        declareSmallSize(zipFile, "01_测试/1002.webp", declared = 1000L)

        val filesDir = tempDir()
        val repository = InMemoryStickerRepository()
        val importer = importer(filesDir, repository)
        val report = ZipFile(zipFile).use { zip ->
            assertEquals("前置条件：中央目录里声明的大小被改小了", 1000L, zip.getEntry("01_测试/1002.webp").size)
            importer.execute(ZipStickerSource(zip), importer.inspect(ZipStickerSource(zip)))
        }

        assertEquals(listOf("01-1001"), repository.stickers().map { it.id })
        assertTrue("超大的那张要记进失败原因：${report.failedCopies}", report.failedCopies.any { it.contains("01-1002") })
        val dest = File(filesDir, "$STICKER_DIR_NAME/01/1002.webp")
        assertFalse("半成品要删掉：${dest.path}", dest.exists())
        assertTrue(
            "不能留下 .tmp：${File(filesDir, "$STICKER_DIR_NAME/01").list()?.toList()}",
            File(filesDir, "$STICKER_DIR_NAME/01").listFiles().orEmpty().none { it.name.endsWith(".tmp") },
        )
    }

    @Test
    fun `failed overwrite keeps the file from the previous import`() = runBlocking {
        val original = StickerTestData.webpBytes(payload = 64)
        val firstRoot = tempDir()
        writeCustomSeries(firstRoot, "01_测试", "01", listOf(Triple("01-1001", "1001.webp", original)))
        val filesDir = tempDir()
        val repository = InMemoryStickerRepository()
        val importer = importer(filesDir, repository)
        importer.execute(FileStickerSource(firstRoot), importer.inspect(FileStickerSource(firstRoot)))
        val dest = File(filesDir, "$STICKER_DIR_NAME/01/1001.webp")
        assertArrayEquals(original, dest.readBytes())

        // 第二次：同一张换成一个声明很小、实际 3 MB 的条目
        val secondRoot = tempDir()
        writeCustomSeries(
            secondRoot,
            "01_测试",
            "01",
            listOf(Triple("01-1001", "1001.webp", StickerTestData.webpBytes(payload = 3 * 1024 * 1024))),
        )
        val zipFile = File(tempDir(), "second.zip")
        StickerTestData.zipDirectory(secondRoot, zipFile)
        declareSmallSize(zipFile, "01_测试/1001.webp", declared = 1000L)
        ZipFile(zipFile).use { zip ->
            importer.execute(ZipStickerSource(zip), importer.inspect(ZipStickerSource(zip)))
        }

        assertArrayEquals("覆盖失败时旧文件必须原样保留", original, dest.readBytes())
    }

    @Test
    fun `oversized series json is skipped instead of being read into memory`() = runBlocking {
        val sourceRoot = tempDir()
        writeCustomSeries(
            sourceRoot,
            folder = "01_测试",
            seriesId = "01",
            stickers = listOf(Triple("01-1001", "1001.webp", StickerTestData.webpBytes())),
            padding = " ".repeat(1024 * 1024 + 16),
        )
        StickerTestData.writeSeries(sourceRoot, "02", includeIndex = false)

        val plan = importer(tempDir()).inspect(FileStickerSource(sourceRoot))

        assertEquals(listOf("02"), plan.parsed.packs.map { it.seriesId })
        assertTrue(
            "超大的 series.json 要记进跳过原因：${plan.parsed.skippedSeries}",
            plan.parsed.skippedSeries.any { it.subject == "01_测试" },
        )
    }

    // ---------------------------------------------------------------- W-016

    @Test
    fun `inspect runs on a background thread`() {
        val sourceRoot = tempDir()
        StickerTestData.writeSeries(sourceRoot, "01", stickerCount = 1)
        val threads = mutableSetOf<Thread>()
        val recording = RecordingSource(FileStickerSource(sourceRoot)) { threads += Thread.currentThread() }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val caller = executor.submit<Thread> { Thread.currentThread() }.get()
            runBlocking(executor.asCoroutineDispatcher()) {
                importer(tempDir()).inspect(recording)
            }
            assertTrue(threads.isNotEmpty())
            threads.forEach { assertNotEquals("inspect 不能在调用方（主线程）上读源", caller, it) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a series whose files cannot be read is skipped instead of failing the whole inspect`() = runBlocking {
        val sourceRoot = tempDir()
        StickerTestData.writeSeries(sourceRoot, "01", stickerCount = 1)
        StickerTestData.writeSeries(sourceRoot, "02", stickerCount = 1)
        val broken = object : StickerSource by FileStickerSource(sourceRoot) {
            override fun readText(path: String): String? {
                if (path.startsWith("01_") && path.endsWith(STICKER_SERIES_FILE)) {
                    throw IOException("provider 崩了")
                }
                return FileStickerSource(sourceRoot).readText(path)
            }
        }

        val plan = importer(tempDir()).inspect(broken)

        assertEquals(listOf("02"), plan.parsed.packs.map { it.seriesId })
        assertTrue(
            "读失败的系列要记进跳过原因：${plan.parsed.skippedSeries}",
            plan.parsed.skippedSeries.any { it.subject.startsWith("01_") },
        )
    }

    // ---------------------------------------------------------------- W-006

    @Test
    fun `merge import keeps the her-can-use switch the user turned off`() = runBlocking {
        val sourceRoot = tempDir()
        StickerTestData.writeSeries(sourceRoot, "01", stickerCount = 2)
        val filesDir = tempDir()
        val repository = InMemoryStickerRepository()
        val importer = importer(filesDir, repository)
        importer.execute(FileStickerSource(sourceRoot), importer.inspect(FileStickerSource(sourceRoot)))
        repository.setEnabledForHer("01", false)

        importer.execute(FileStickerSource(sourceRoot), importer.inspect(FileStickerSource(sourceRoot)))

        assertFalse("合并导入不能把关掉的「她能用」改回打开", repository.pack("01")!!.enabledForHer)
    }

    // ---------------------------------------------------------------- 工具

    /** 把 zip 中央目录里 [entryName] 的「解压后大小」改成 [declared]（模拟伪造的声明值）。 */
    private fun declareSmallSize(zipFile: File, entryName: String, declared: Long) {
        val bytes = zipFile.readBytes()
        val name = entryName.toByteArray(Charsets.UTF_8)
        var patched = false
        var i = 0
        while (i + 46 <= bytes.size) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 0x01.toByte() && bytes[i + 3] == 0x02.toByte()
            ) {
                val nameLength = (bytes[i + 28].toInt() and 0xff) or ((bytes[i + 29].toInt() and 0xff) shl 8)
                val candidate = bytes.copyOfRange(i + 46, i + 46 + nameLength)
                if (candidate.contentEquals(name)) {
                    for (k in 0 until 4) bytes[i + 24 + k] = ((declared shr (8 * k)) and 0xff).toByte()
                    patched = true
                }
                i += 46
            } else {
                i++
            }
        }
        check(patched) { "没找到条目 $entryName" }
        zipFile.writeBytes(bytes)
    }

    private class RecordingSource(
        private val delegate: StickerSource,
        private val onCall: () -> Unit,
    ) : StickerSource {
        override fun list(path: String) = delegate.list(path).also { onCall() }
        override fun exists(path: String) = delegate.exists(path).also { onCall() }
        override fun length(path: String) = delegate.length(path).also { onCall() }
        override fun readText(path: String) = delegate.readText(path).also { onCall() }
        override fun readHead(path: String, count: Int) = delegate.readHead(path, count).also { onCall() }
        override fun open(path: String): InputStream? = delegate.open(path).also { onCall() }
    }
}
