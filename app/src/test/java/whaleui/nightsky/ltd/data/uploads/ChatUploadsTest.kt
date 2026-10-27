package whaleui.nightsky.ltd.data.uploads

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W-039 / W-033：附件复制进私有目录的规则。图片的真实解码压缩在模拟器上验证，这里用假的压缩器。 */
class ChatUploadsTest {
    private val dir = File(TestDocuments.tempDir("whale-uploads"), "whale/uploads")

    private class MemorySource : UploadSource {
        val files = mutableMapOf<String, Pair<UploadInfo, ByteArray>>()
        override fun describe(uri: String): UploadInfo = files.getValue(uri).first
        override fun open(uri: String): InputStream = ByteArrayInputStream(files.getValue(uri).second)
    }

    /** 假压缩器：记下收到的 mime，把内容原样写成 jpg */
    private class RecordingShrinker : ImageShrinker {
        val seen = mutableListOf<String>()
        override fun shrink(source: File, mime: String, outputDir: File): ShrunkImage {
            seen += mime
            val out = File(outputDir, "shrunk_${source.name}.jpg")
            source.copyTo(out)
            return ShrunkImage(out, "image/jpeg")
        }
    }

    private val source = MemorySource()
    private val shrinker = RecordingShrinker()
    private val uploads = ChatUploads(dir, source, shrinker, maxBytes = 1_000)

    private fun ok(result: ChatUploads.Result) = (result as ChatUploads.Result.Ok).attachment
    private fun fileOf(url: String) = File(url.removePrefix("file://"))

    @Test
    fun `same content uri with new content is copied again under a new name`() {
        val uri = "content://provider/doc/1"
        source.files[uri] = UploadInfo("a.txt", 3, "text/plain") to "旧".toByteArray()
        val first = ok(uploads.import(uri))
        source.files[uri] = UploadInfo("a.txt", 3, "text/plain") to "新".toByteArray()
        val second = ok(uploads.import(uri))

        assertNotEquals(first.uri, second.uri)
        assertEquals("新", fileOf(second.uri).readText())
        assertTrue(Regex("upload_[0-9a-f-]{36}\\.txt").matches(fileOf(second.uri).name))
    }

    @Test
    fun `a file declared over the limit is rejected with a reason`() {
        source.files["content://big"] = UploadInfo("big.pdf", 5_000, "application/pdf") to ByteArray(10)
        val result = uploads.import("content://big")
        assertTrue(result is ChatUploads.Result.Rejected)
        assertTrue((result as ChatUploads.Result.Rejected).reason.contains("big.pdf"))
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a file whose real size is over the limit is rejected and the partial copy deleted`() {
        source.files["content://liar"] = UploadInfo("liar.txt", 10, "text/plain") to ByteArray(1_500)
        assertTrue(uploads.import("content://liar") is ChatUploads.Result.Rejected)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `mime falls back to the file extension`() {
        source.files["content://d"] = UploadInfo("信.docx", 4, "application/octet-stream") to "docx".toByteArray()
        source.files["content://m"] = UploadInfo("notes.md", 2, null) to "# x".toByteArray()
        assertEquals(UploadMime.DOCX, ok(uploads.import("content://d")).mimeType)
        assertEquals("text/markdown", ok(uploads.import("content://m")).mimeType)
    }

    @Test
    fun `unsupported files are rejected`() {
        source.files["content://z"] = UploadInfo("a.zip", 4, "application/zip") to "PK..".toByteArray()
        assertTrue(uploads.import("content://z") is ChatUploads.Result.Rejected)
    }

    @Test
    fun `images go through the shrinker and keep their display name`() {
        source.files["content://img"] = UploadInfo("IMG_1.jpg", 4, "image/jpeg") to "jpeg".toByteArray()
        val attachment = ok(uploads.import("content://img"))
        assertEquals(listOf("image/jpeg"), shrinker.seen)
        assertEquals("IMG_1.jpg", attachment.name)
        assertEquals("image/jpeg", attachment.mimeType)
        assertTrue(attachment.isImage)
        // 原始拷贝被删掉，只留压缩后的
        assertEquals(1, dir.listFiles().orEmpty().size)
    }

    @Test
    fun `discard only deletes files inside the uploads dir`() {
        source.files["content://t"] = UploadInfo("t.txt", 1, "text/plain") to "t".toByteArray()
        val attachment = ok(uploads.import("content://t"))
        val outside = File(dir.parentFile, "keep.txt").apply { writeText("keep") }
        uploads.discard("file://" + outside.absolutePath)
        uploads.discard(attachment.uri)
        assertTrue(outside.exists())
        assertTrue(!fileOf(attachment.uri).exists())
    }

    @Test
    fun `sample size keeps the long side at least 2048 before the exact scale`() {
        assertEquals(1, ChatUploads.sampleSizeFor(2048, 1536))
        assertEquals(1, ChatUploads.sampleSizeFor(4000, 3000))
        assertEquals(2, ChatUploads.sampleSizeFor(4096, 3072))
        assertEquals(4, ChatUploads.sampleSizeFor(9000, 6000))
    }

    @Test
    fun `scaled size caps the long side at 2048 and never upscales`() {
        assertEquals(2048 to 1536, ChatUploads.scaledSize(4000, 3000))
        assertEquals(1536 to 2048, ChatUploads.scaledSize(3000, 4000))
        assertEquals(800 to 600, ChatUploads.scaledSize(800, 600))
    }

    @Test
    fun `only transparent png or webp stay png`() {
        assertTrue(ChatUploads.keepsPng("image/png", hasAlpha = true))
        assertTrue(!ChatUploads.keepsPng("image/png", hasAlpha = false))
        assertTrue(!ChatUploads.keepsPng("image/jpeg", hasAlpha = true))
    }
}
