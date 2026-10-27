package whaleui.nightsky.ltd.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入日记（任务书 4.7）：只合并不覆盖、按时间插入、按日期分组、zip 解析。 */
class WhaleDiaryImporterTest {

    private val date = LocalDate.of(2026, 10, 25)

    @Test
    fun `parses only bracketed time segments`() {
        val content = """
            # 假的标题
            [09:15] 假的早上
            [9:20:33] 假的上午
            没有时间戳的一行
            [11:00] 
        """.trimIndent()
        val segments = WhaleDiaryImporter.parseSegments(content)
        assertEquals(listOf("09:15", "09:20"), segments.map { it.time })
        // 时间戳行之前的标题不算段；不以时间戳开头的行是上一段的续行（W-001）
        assertEquals(listOf("假的早上", "假的上午\n没有时间戳的一行"), segments.map { it.text })
    }

    @Test
    fun `merge keeps existing segments and inserts new ones by time`() {
        val existing = "[08:00] 旧的早\n[20:00] 旧的晚"
        val incoming = listOf(
            WhaleDiaryImporter.Segment("08:00", "旧的早"),   // 重复，跳过
            WhaleDiaryImporter.Segment("12:00", "新的中午"), // 插到中间
        )
        val result = WhaleDiaryImporter.merge(existing, incoming)
        assertEquals("[08:00] 旧的早\n[12:00] 新的中午\n[20:00] 旧的晚", result.content)
        assertEquals(1, result.addedSegments)
        assertEquals(1, result.skippedSegments)
    }

    @Test
    fun `merge never deletes content that exists only on the device`() {
        val existing = "[09:00] 手机上写的\n[10:00] 也在手机上"
        val result = WhaleDiaryImporter.merge(existing, listOf(WhaleDiaryImporter.Segment("11:00", "导入的")))
        assertTrue(result.content.contains("手机上写的"))
        assertTrue(result.content.contains("也在手机上"))
        assertTrue(result.content.endsWith("[11:00] 导入的"))
    }

    @Test
    fun `date comes from the file name only`() {
        assertEquals(date, WhaleDiaryImporter.dateOf("2026-10-25.md"))
        assertEquals(date, WhaleDiaryImporter.dateOf("diary/2026-10-25.md"))
        assertEquals(null, WhaleDiaryImporter.dateOf("notes.md"))
        assertEquals(null, WhaleDiaryImporter.dateOf("2026-13-01.md"))
    }

    @Test
    fun `import groups by date and reports days and segments`() {
        val files = listOf(
            WhaleDiaryImporter.SourceFile("2026-10-25.md", "[08:00] 一天早"),
            WhaleDiaryImporter.SourceFile("2026-10-25.md", "[20:00] 一天晚"),
            WhaleDiaryImporter.SourceFile("2026-10-26.md", "[09:00] 二天早"),
            WhaleDiaryImporter.SourceFile("README.md", "忽略我"),
        )
        val result = WhaleDiaryImporter.importAll(files, emptyMap())
        assertEquals(2, result.days)
        assertEquals(3, result.addedSegments)
        assertEquals("导入了 2 天、3 段", result.summary())
        assertTrue(result.contentByDate.getValue(LocalDate.of(2026, 10, 25)).contains("一天晚"))
    }

    @Test
    fun `zip bytes are parsed and non diary entries ignored`() {
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                fun entry(name: String, content: String) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                entry("2026-10-25.md", "[08:00] zip 里的")
                entry("sub/2026-10-26.md", "[09:00] 子目录里的")
                entry("ignore.txt", "不是日记")
            }
            out.toByteArray()
        }
        val files = WhaleDiaryImporter.readZip(bytes)
        assertEquals(listOf("2026-10-25.md", "2026-10-26.md"), files.map { it.name })
    }

    // ---------------------------------------------------------------- W-001 续行

    @Test
    fun `merge keeps continuation lines of existing multi-line segments`() {
        val existing = "[08:00] 第一行\n第二行\n  第三行缩进\n[20:00] 晚上"
        val result = WhaleDiaryImporter.merge(existing, listOf(WhaleDiaryImporter.Segment("12:00", "新的中午")))
        assertEquals(
            "[08:00] 第一行\n第二行\n  第三行缩进\n[12:00] 新的中午\n[20:00] 晚上",
            result.content,
        )
        assertEquals(1, result.addedSegments)
    }

    @Test
    fun `merge keeps title and weather lines verbatim`() {
        val existing = "# 10 月 25 日\n天气：晴 18°C\n\n[08:00] 早\n[20:00] 晚\n"
        val result = WhaleDiaryImporter.merge(
            existing,
            listOf(
                WhaleDiaryImporter.Segment("07:00", "更早"),
                WhaleDiaryImporter.Segment("23:00", "睡前"),
            ),
        )
        assertEquals(
            "# 10 月 25 日\n天气：晴 18°C\n\n[07:00] 更早\n[08:00] 早\n[20:00] 晚\n[23:00] 睡前\n",
            result.content,
        )
        // 已有文本逐字保留：删掉新段后和原文完全一样
        val withoutNew = result.content.replace("[07:00] 更早\n", "").replace("[23:00] 睡前\n", "")
        assertEquals(existing, withoutNew)
    }

    @Test
    fun `imported multi-line segments are kept whole`() {
        val files = listOf(
            WhaleDiaryImporter.SourceFile("2026-10-25.md", "[08:00] 早上第一行\n早上第二行\n\n[21:00] 晚上"),
        )
        val result = WhaleDiaryImporter.importAll(files, mapOf(date to "[12:00] 已有\n已有续行"))
        assertEquals(
            "[08:00] 早上第一行\n早上第二行\n[12:00] 已有\n已有续行\n[21:00] 晚上",
            result.contentByDate.getValue(date),
        )
        assertEquals(2, result.addedSegments)
    }

    @Test
    fun `re-importing the same multi-line segment adds nothing`() {
        val existing = "[08:00] 第一行\n第二行"
        val files = listOf(WhaleDiaryImporter.SourceFile("2026-10-25.md", "[08:00] 第一行\n第二行\n"))
        val result = WhaleDiaryImporter.importAll(files, mapOf(date to existing))
        assertEquals(0, result.addedSegments)
        assertEquals(1, result.skippedSegments)
        assertTrue(result.contentByDate.isEmpty())
    }

    @Test
    fun `same first line with different continuation is a different segment`() {
        val existing = "[08:00] 第一行\n旧的续行"
        val result = WhaleDiaryImporter.merge(
            existing,
            WhaleDiaryImporter.parseSegments("[08:00] 第一行\n新的续行"),
        )
        assertEquals("[08:00] 第一行\n旧的续行\n[08:00] 第一行\n新的续行", result.content)
    }

    // ---------------------------------------------------------------- W-010 解压上限

    @Test
    fun `readZip rejects archives that inflate far beyond the limit`() {
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("2026-10-25.md"))
                zip.write("[08:00] 正常的".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                // 压缩后几百 KB、解压后 300 MB 的条目
                zip.putNextEntry(java.util.zip.ZipEntry("padding.bin"))
                val chunk = ByteArray(1024 * 1024)
                repeat(300) { zip.write(chunk) }
                zip.closeEntry()
            }
            out.toByteArray()
        }
        assertTrue("测试用的 zip 本身应该很小", bytes.size < 2 * 1024 * 1024)
        val error = runCatching { WhaleDiaryImporter.readZip(bytes) }.exceptionOrNull()
        assertTrue("解压总量超限应该抛出受控异常，实际：$error", error is IllegalArgumentException)
    }
}
