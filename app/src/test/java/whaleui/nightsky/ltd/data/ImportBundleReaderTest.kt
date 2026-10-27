package whaleui.nightsky.ltd.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.memory.MemoryMarkdownImporter

/** 导出包的读取与路径约定（导入记忆 / 导入聊天记录共用）。 */
class ImportBundleReaderTest {

    private val bundle: ByteArray = zipOf(
        "soul.md" to "人设文本",
        "user.md" to "关于你的文本",
        "memory/memories.md" to "# 记忆\n\n## 关于你\n\n- 甲（2026-10-25）\n",
        "memory/memories.json" to "[]",
        "diary/2026-10-25.md" to "[08:00] 今天的日记",
        "chat/2026-10-25_小鲸鱼.md" to "# default\n\n## 2026-10-25\n\n- [08:30] 她：消息1\n",
        "index.json" to "{}",
        "01/series.json" to "{}",
        "01/1000001.webp" to "RIFF0000WEBP",
    )

    @Test
    fun `memory entries are found by path`() {
        val files = ImportBundleReader.extractMarkdown(bundle, "bundle.zip", BundleFormat::isMemoryMarkdown)
        assertEquals(listOf("memory/memories.md"), files.map { it.name })
        val items = MemoryMarkdownImporter.parse(files.single().content)
        assertEquals(listOf("甲"), items.map { it.content })
    }

    @Test
    fun `chat entries are found and parsed`() {
        val files = ImportBundleReader.extractMarkdown(bundle, "bundle.zip", BundleFormat::isChatMarkdown)
        assertEquals(listOf("chat/2026-10-25_小鲸鱼.md"), files.map { it.name })
        val messages = ChatMarkdownParser.parse(files.single().content)
        assertEquals(1, messages.size)
        assertEquals("消息1", messages.single().text)
    }

    /** 改名后导出的是 `_miniWhale.md`；旧包里的 `_小鲸鱼.md` 必须照样能导入，两种混在一个包里也行。 */
    @Test
    fun `chat entries with old and new app names are both imported`() {
        val mixed = zipOf(
            "chat/2026-10-25_小鲸鱼.md" to "# 小鲸鱼\n\n## 2026-10-25\n\n- [08:30] 她：旧包消息\n",
            "chat/2026-10-26_miniWhale.md" to "# miniWhale\n\n## 2026-10-26\n\n- [09:00] 我：新包消息\n",
        )
        val files = ImportBundleReader.extractMarkdown(mixed, "miniWhale-2026-10-26.zip", BundleFormat::isChatMarkdown)
        assertEquals(
            listOf("chat/2026-10-25_小鲸鱼.md", "chat/2026-10-26_miniWhale.md"),
            files.map { it.name }.sorted(),
        )
        val texts = files.sortedBy { it.name }.flatMap { ChatMarkdownParser.parse(it.content) }.map { it.text }
        assertEquals(listOf("旧包消息", "新包消息"), texts)
    }

    @Test
    fun `single markdown file is read as one text`() {
        val files = ImportBundleReader.extractMarkdown(
            bytes = "## 关于你\n- 甲".toByteArray(),
            displayName = "memories.md",
            selector = BundleFormat::isMemoryMarkdown,
        )
        assertEquals(1, files.size)
        assertEquals("memories.md", files.single().name)
    }

    @Test
    fun `zip without matching entries returns empty list`() {
        val files = ImportBundleReader.extractMarkdown(bundle, "bundle.zip") { it.contains("nope") }
        assertTrue(files.isEmpty())
    }

    @Test
    fun `bundle paths are classified as expected`() {
        assertTrue(BundleFormat.isMemoryMarkdown("memory/memories.md"))
        assertTrue(BundleFormat.isMemoryMarkdown("memories.md"))
        assertTrue(BundleFormat.isChatMarkdown("chat/2026-10-25_小鲸鱼.md"))
        assertTrue(BundleFormat.isChatMarkdown("chat/2026-10-25_miniWhale.md"))
        assertTrue(!BundleFormat.isChatMarkdown("memory/memories.md"))
        assertTrue(!BundleFormat.isChatMarkdown("diary/2026-10-25.md"))
        assertTrue(!BundleFormat.isMemoryMarkdown("soul.md"))
    }

    // ---------------------------------------------------------------- W-010 有上限的读取

    @Test
    fun `stream reading stops at the limit instead of reading the whole file`() {
        var served = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int {
                served++
                return 0
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= 16L * 1024 * 1024) return -1
                served += len
                return len
            }
        }
        val error = runCatching { ImportBundleReader.readStream(endless, limit = 1024) }.exceptionOrNull()
        assertTrue("超过上限应该拒绝，实际：$error", error is IllegalArgumentException)
        assertTrue("超过上限后应该立刻停，实际读了 $served 字节", served <= 1024L + 64 * 1024)
    }

    @Test
    fun `zip that inflates far beyond the total limit is rejected`() {
        val bomb = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("memory/memories.md"))
                zip.write("## 关于你\n- 甲".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("01/padding.webp"))
                val chunk = ByteArray(1024 * 1024)
                repeat(300) { zip.write(chunk) }
                zip.closeEntry()
            }
            out.toByteArray()
        }
        val error = runCatching {
            ImportBundleReader.extractMarkdown(bomb, "bundle.zip", BundleFormat::isMemoryMarkdown)
        }.exceptionOrNull()
        assertTrue("解压总量超限应该抛出受控异常，实际：$error", error is IllegalArgumentException)
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            out.toByteArray()
        }
}
