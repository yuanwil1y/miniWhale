package whaleui.nightsky.ltd.sticker

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表情库解析与校验（任务书 3.2 / 第四节「导入」）。
 *
 * 全部用测试里自己生成的假 WebP，不碰用户素材、不依赖 Android。
 */
class StickerParserTest {

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("whale-stickers").toFile().apply { deleteOnExit() }

    // ------------------------------------------------------------ 正常库

    @Test
    fun `parses a full library with index json`() {
        val root = tempDir()
        StickerTestData.writeSeries(root, "01", stickerCount = 2)
        StickerTestData.writeSeries(root, "02", stickerCount = 3)

        val result = StickerParser.parse(FileStickerSource(root))

        assertEquals(2, result.packs.size)
        assertEquals(5, result.packs.sumOf { it.stickers.size })
        assertEquals(0, result.skippedSeries.size)
        assertEquals(0, result.skippedStickers.size)
        val first = result.packs.first()
        assertEquals("01", first.seriesId)
        assertEquals("测试 01", first.name)
        assertEquals("角色甲", first.characters.first())
        assertEquals("HELLO!", first.stickers.first().textInImage)
        assertEquals("你好！", first.stickers.first().textMeaning)
        assertTrue(first.stickers[1].couple)
    }

    @Test
    fun `scans subfolders when index json is missing`() {
        val root = tempDir()
        StickerTestData.writeSeries(root, "01", includeIndex = false)
        StickerTestData.writeSeries(root, "05", includeIndex = false)

        val result = StickerParser.parse(FileStickerSource(root))

        assertEquals(listOf("01", "05"), result.packs.map { it.seriesId }.sorted())
    }

    @Test
    fun `folder without series json is skipped with a reason`() {
        val root = tempDir()
        // 不写 index.json，走「扫描子文件夹」的路径，才会看到那个没有 series.json 的目录
        StickerTestData.writeSeries(root, "01", includeIndex = false)
        File(root, "02_3620_没有说明").apply { mkdirs() }
        File(root, "02_3620_没有说明/1000001.webp").writeBytes(StickerTestData.webpBytes())

        val result = StickerParser.parse(FileStickerSource(root))

        assertEquals(1, result.packs.size)
        assertEquals(1, result.skippedSeries.size)
        assertTrue(result.skippedSeries.single().reason.contains("缺少 series.json"))
    }

    @Test
    fun `loose images at the root are reported and not imported`() {
        val root = tempDir()
        File(root, "loose.webp").writeBytes(StickerTestData.webpBytes())

        val result = StickerParser.parse(FileStickerSource(root))

        assertTrue(result.packs.isEmpty())
        assertTrue(result.warnings.any { it.contains("散图") || it.contains("根目录") })
    }

    @Test
    fun `wrong format is rejected for the whole series`() {
        val root = tempDir()
        StickerTestData.writeSeries(
            root = root,
            seriesId = "01",
            seriesJsonOverride = { stickers ->
                """
                {
                  "format": "something-else",
                  "version": 2,
                  "series_id": "01",
                  "name": "测试",
                  "title_original": "测试",
                  "characters": ["角色甲"],
                  "animated": true,
                  "has_sound_originally": false,
                  "stickers": [$stickers]
                }
                """.trimIndent()
            },
        )

        val result = StickerParser.parse(FileStickerSource(root))

        assertTrue(result.packs.isEmpty())
        assertTrue(result.skippedSeries.single().reason.contains("format"))
    }

    @Test
    fun `wrong version is rejected for the whole series`() {
        val root = tempDir()
        StickerTestData.writeSeries(
            root = root,
            seriesId = "01",
            seriesJsonOverride = { stickers ->
                """
                {
                  "format": "whale-sticker-series",
                  "version": 1,
                  "series_id": "01",
                  "name": "测试",
                  "title_original": "测试",
                  "characters": ["角色甲"],
                  "animated": true,
                  "has_sound_originally": false,
                  "stickers": [$stickers]
                }
                """.trimIndent()
            },
        )

        val result = StickerParser.parse(FileStickerSource(root))

        assertTrue(result.packs.isEmpty())
        assertTrue(result.skippedSeries.single().reason.contains("version"))
    }

    // ------------------------------------------------------------ 单张跳过

    @Test
    fun `sticker whose file does not exist is skipped`() {
        val root = tempDir()
        StickerTestData.writeSeries(
            root = root,
            seriesId = "01",
            seriesJsonOverride = { _ ->
                """
                {
                  "format": "whale-sticker-series",
                  "version": 2,
                  "series_id": "01",
                  "name": "测试",
                  "title_original": "测试",
                  "characters": ["角色甲"],
                  "animated": true,
                  "has_sound_originally": false,
                  "stickers": [
                    { "id": "01-1", "file": "missing.webp", "description": "假的", "detail": "根本不存在的文件" }
                  ]
                }
                """.trimIndent()
            },
        )

        val result = StickerParser.parse(FileStickerSource(root))

        assertTrue(result.packs.isEmpty())
        assertTrue(result.skippedStickers.single().reason.contains("不存在"))
    }

    @Test
    fun `non webp content is rejected even with a webp extension`() {
        val root = tempDir()
        val folder = File(root, "01_3620_测试").apply { mkdirs() }
        File(folder, "1000001.webp").writeBytes(StickerTestData.pngBytes())
        File(folder, "series.json").writeText(
            """
            {
              "format": "whale-sticker-series", "version": 2, "series_id": "01",
              "name": "测试", "title_original": "测试", "characters": [],
              "animated": true, "has_sound_originally": false,
              "stickers": [
                { "id": "01-1000001", "file": "1000001.webp", "description": "假的", "detail": "其实是 PNG" }
              ]
            }
            """.trimIndent(),
        )

        val result = StickerParser.parse(FileStickerSource(root))

        assertTrue(result.packs.isEmpty())
        assertTrue(result.skippedStickers.single().reason.contains("WebP"))
    }

    @Test
    fun `sticker larger than two megabytes is skipped`() {
        val root = tempDir()
        val folder = File(root, "01_3620_测试").apply { mkdirs() }
        val big = StickerTestData.webpBytes()
        val file = File(folder, "1000001.webp")
        file.outputStream().use { out ->
            out.write(big)
            out.write(ByteArray((STICKER_MAX_BYTES + 1024).toInt()))
        }
        File(folder, "series.json").writeText(
            """
            {
              "format": "whale-sticker-series", "version": 2, "series_id": "01",
              "name": "测试", "title_original": "测试", "characters": [],
              "animated": true, "has_sound_originally": false,
              "stickers": [
                { "id": "01-1", "file": "1000001.webp", "description": "太大", "detail": "超过 2 MB 的文件" }
              ]
            }
            """.trimIndent(),
        )

        val result = StickerParser.parse(FileStickerSource(root))

        assertTrue(result.packs.isEmpty())
        assertTrue(result.skippedStickers.single().reason.contains("2 MB"))
    }

    @Test
    fun `sticker without description or detail is skipped`() {
        val root = tempDir()
        val folder = File(root, "01_3620_测试").apply { mkdirs() }
        File(folder, "a.webp").writeBytes(StickerTestData.webpBytes())
        File(folder, "b.webp").writeBytes(StickerTestData.webpBytes())
        File(folder, "c.webp").writeBytes(StickerTestData.webpBytes())
        File(folder, "series.json").writeText(
            """
            {
              "format": "whale-sticker-series", "version": 2, "series_id": "01",
              "name": "测试", "title_original": "测试", "characters": [],
              "animated": true, "has_sound_originally": false,
              "stickers": [
                { "id": "01-a", "file": "a.webp", "detail": "没有 description" },
                { "id": "01-b", "file": "b.webp", "description": "没有 detail" },
                { "id": "01-c", "file": "c.webp", "description": "正常的", "detail": "这一张两条都有，所以能进来。" }
              ]
            }
            """.trimIndent(),
        )

        val result = StickerParser.parse(FileStickerSource(root))

        assertEquals(1, result.packs.single().stickers.size)
        assertEquals("01-c", result.packs.single().stickers.single().id)
        assertEquals(2, result.skippedStickers.size)
    }

    @Test
    fun `unknown emotion words are dropped but the sticker stays`() {
        val root = tempDir()
        StickerTestData.writeSeries(
            root = root,
            seriesId = "01",
            stickerCount = 1,
            emotions = listOf("开心", "吃醋", "抱抱"),
        )

        val result = StickerParser.parse(FileStickerSource(root))

        assertEquals(listOf("开心", "抱抱"), result.packs.single().stickers.single().emotions)
        assertTrue(result.warnings.any { it.contains("吃醋") })
    }

    @Test
    fun `sticker with no known emotion at all is still imported with a warning`() {
        val root = tempDir()
        StickerTestData.writeSeries(
            root = root,
            seriesId = "01",
            stickerCount = 1,
            emotions = listOf("吃醋"),
        )

        val result = StickerParser.parse(FileStickerSource(root))

        assertEquals(1, result.packs.single().stickers.size)
        assertTrue(result.packs.single().stickers.single().emotions.isEmpty())
        assertTrue(result.warnings.any { it.contains("没有任何合法情绪词") })
    }

    @Test
    fun `missing id is derived from the series and file name`() {
        val root = tempDir()
        val folder = File(root, "01_3620_测试").apply { mkdirs() }
        File(folder, "1000001.webp").writeBytes(StickerTestData.webpBytes())
        File(folder, "series.json").writeText(
            """
            {
              "format": "whale-sticker-series", "version": 2, "series_id": "01",
              "name": "测试", "title_original": "测试", "characters": [],
              "animated": true, "has_sound_originally": false,
              "stickers": [
                { "file": "1000001.webp", "description": "没有编号", "detail": "编号要按系列号和文件名补出来。" }
              ]
            }
            """.trimIndent(),
        )

        val result = StickerParser.parse(FileStickerSource(root))

        assertEquals("01-1000001", result.packs.single().stickers.single().id)
    }

    @Test
    fun `sticker id with a wrong prefix is corrected with a warning`() {
        val root = tempDir()
        val folder = StickerTestData.writeSeries(
            root = root,
            seriesId = "01",
            stickerCount = 1,
            seriesJsonOverride = { _ ->
                // 手工写一条前缀错误的
                """
                {
                  "format": "whale-sticker-series", "version": 2, "series_id": "01",
                  "name": "测试", "title_original": "测试", "characters": [],
                  "animated": true, "has_sound_originally": false,
                  "stickers": [
                    { "id": "p01-007", "file": "100000101.webp", "description": "旧编号", "detail": "旧方案的编号要被纠正成 v2 的格式。" }
                  ]
                }
                """.trimIndent()
            },
        )
        File(folder, "100000101.webp").writeBytes(StickerTestData.webpBytes())

        val result = StickerParser.parse(FileStickerSource(root))

        val id = result.packs.single().stickers.single().id
        assertTrue("实际编号：$id", id.startsWith("01-"))
        assertTrue(result.warnings.any { it.contains("p01-007") })
    }

    @Test
    fun `duplicate sticker ids inside one series keep the first`() {
        val root = tempDir()
        val folder = StickerTestData.writeSeries(
            root = root,
            seriesId = "01",
            stickerCount = 1,
            seriesJsonOverride = { _ ->
                """
                {
                  "format": "whale-sticker-series", "version": 2, "series_id": "01",
                  "name": "测试", "title_original": "测试", "characters": [],
                  "animated": true, "has_sound_originally": false,
                  "stickers": [
                    { "id": "01-100000101", "file": "100000101.webp", "description": "第一条", "detail": "重复编号时保留第一条，这是第一条。" },
                    { "id": "01-100000101", "file": "100000102.webp", "description": "第二条", "detail": "重复编号时保留第一条，这是第二条。" }
                  ]
                }
                """.trimIndent()
            },
        )
        // 两张的文件都要真实存在，否则会先因为「文件不存在」被跳过，测不到重复编号
        File(folder, "100000101.webp").writeBytes(StickerTestData.webpBytes())
        File(folder, "100000102.webp").writeBytes(StickerTestData.webpBytes())

        val result = StickerParser.parse(FileStickerSource(root))

        assertEquals(1, result.packs.single().stickers.size)
        assertEquals("第一条", result.packs.single().stickers.single().description)
    }

    // ------------------------------------------------------------ zip

    @Test
    fun `zip source parses the same library`() {
        val root = tempDir()
        StickerTestData.writeSeries(root, "01", stickerCount = 2)
        StickerTestData.writeSeries(root, "08", stickerCount = 1)
        val zip = File(tempDir(), "stickers.zip")
        StickerTestData.zipDirectory(root, zip)

        ZipFile(zip).use { zipFile ->
            val result = StickerParser.parse(ZipStickerSource(zipFile))
            assertEquals(2, result.packs.size)
            assertEquals(3, result.packs.sumOf { it.stickers.size })
            assertNotNull(result.packs.first().stickers.first().sourcePath)
            assertFalse(result.packs.first().stickers.first().sourcePath.startsWith("/"))
        }
    }

    // ------------------------------------------------------------ 情绪词表

    @Test
    fun `emotion vocabulary has exactly 42 unique words`() {
        assertEquals(42, StickerEmotions.ALL.size)
        assertEquals(42, StickerEmotions.ALL.toSet().size)
        assertTrue(StickerEmotions.isKnown("开心"))
        assertTrue(StickerEmotions.isKnown("庆祝"))
        assertFalse(StickerEmotions.isKnown("吃醋"))
    }

    @Test
    fun `webp header detection is based on content`() {
        assertTrue(StickerSource.isWebp(StickerTestData.webpBytes()))
        assertFalse(StickerSource.isWebp(StickerTestData.pngBytes()))
        assertFalse(StickerSource.isWebp(StickerTestData.gifBytes()))
        assertFalse(StickerSource.isWebp(null))
        assertFalse(StickerSource.isWebp(ByteArray(4)))
    }
}
