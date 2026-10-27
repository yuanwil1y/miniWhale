package me.rerere.rikkahub.data.ai.transformers

import java.io.File
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.ai.compatTransform
import whaleui.nightsky.ltd.data.uploads.TestDocuments
import whaleui.nightsky.ltd.data.uploads.UploadMime

/**
 * W-033：他发的文档要以文字形式出现在 user 消息里（DeepSeek 只收文字）。
 * 文档都是测试里现场生成的最小合法文件。
 */
class DocumentAsPromptTransformerTest {
    private val dir = TestDocuments.tempDir()

    private fun file(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }

    private fun userWith(document: UIMessagePart.Document, text: String = "帮我看看") =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text), document))

    /** 走完文档转换 + DeepSeek 兼容转换后，这条 user 消息里发给模型的全部文字 */
    private fun requestText(document: UIMessagePart.Document): String {
        val converted = compatTransform(documentsAsPrompt(listOf(userWith(document))))
        return converted.single().parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    }

    private fun doc(file: File, mime: String) =
        UIMessagePart.Document(url = "file://" + file.absolutePath, fileName = file.name, mime = mime)

    @Test
    fun `txt content goes into the user message`() {
        val text = requestText(doc(file("note.txt", "测试文档正文".toByteArray()), "text/plain"))
        assertTrue(text, text.contains("<UploadFile name=\"note.txt\">"))
        assertTrue(text, text.contains("测试文档正文"))
        assertTrue(text, text.contains("帮我看看"))
    }

    @Test
    fun `markdown content goes into the user message`() {
        val text = requestText(doc(file("plan.md", "# 计划\n- 买菜\n- 洗衣服".toByteArray()), "text/markdown"))
        assertTrue(text, text.contains("# 计划\n- 买菜\n- 洗衣服"))
    }

    @Test
    fun `docx is parsed into text`() {
        val text = requestText(doc(file("letter.docx", TestDocuments.docx("测试文档标题")), UploadMime.DOCX))
        assertTrue(text, text.contains("测试文档标题"))
    }

    @Test
    fun `pdf is parsed into text`() {
        val text = requestText(doc(file("menu.pdf", TestDocuments.pdf("Whale PDF menu 42")), UploadMime.PDF))
        assertTrue(text, text.contains("Whale PDF menu 42"))
    }

    @Test
    fun `old messages stored as octet-stream are parsed by file extension`() {
        val text = requestText(
            doc(file("old.docx", TestDocuments.docx("老消息里的文档")), "application/octet-stream"),
        )
        assertTrue(text, text.contains("老消息里的文档"))
    }

    @Test
    fun `document text and his words become one text part so content is sent as a string`() {
        val document = doc(file("a.txt", "文件内容".toByteArray()), "text/plain")
        val parts = compatTransform(documentsAsPrompt(listOf(userWith(document)))).single().parts
        assertEquals(1, parts.size)
        assertTrue((parts.single() as UIMessagePart.Text).text.endsWith("帮我看看"))
    }

    @Test
    fun `no workspace upload path is added`() {
        val uploadDir = File(dir, "upload").apply { mkdirs() }
        val text = requestText(doc(File(uploadDir, "a.txt").apply { writeText("内容") }, "text/plain"))
        assertFalse(text, text.contains("/upload/"))
        assertFalse(text, text.contains("path="))
    }

    @Test
    fun `a missing file is explained instead of failing the request`() {
        val text = requestText(doc(File(dir, "gone.pdf"), UploadMime.PDF))
        assertTrue(text, text.contains("文件已经不在了：gone.pdf"))
    }

    @Test
    fun `very long documents are cut with a note`() {
        val long = "字".repeat(MAX_DOCUMENT_CHARS + 10)
        val text = requestText(doc(file("long.txt", long.toByteArray()), "text/plain"))
        assertTrue(text, text.contains("后面还有 10 字没有带上"))
        assertEquals(MAX_DOCUMENT_CHARS, text.substringBefore("[文件太长").count { it == '字' })
    }
}
