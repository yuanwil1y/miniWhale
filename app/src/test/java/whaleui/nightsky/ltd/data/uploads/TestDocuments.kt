package whaleui.nightsky.ltd.data.uploads

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 测试里现场生成的最小合法文档（不用任何人的真实文件）。 */
internal object TestDocuments {

    fun tempDir(prefix: String = "whale-docs"): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    /** 只有一段文字的 docx：[Content_Types].xml + _rels + word/document.xml */
    fun docx(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                    """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                    """<Default Extension="xml" ContentType="application/xml"/>""" +
                    """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
                    """</Types>""",
            )
            entry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
                    """</Relationships>""",
            )
            entry(
                "word/document.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
                    """<w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>""",
            )
        }
        return out.toByteArray()
    }

    /** 一页、Helvetica、一行 ASCII 文字的 PDF，xref 偏移量按实际字节算 */
    fun pdf(text: String): ByteArray {
        val stream = "BT /F1 18 Tf 72 720 Td ($text) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R " +
                "/Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${stream.length} >>\nstream\n$stream\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val body = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { index, content ->
            offsets += body.length
            body.append("${index + 1} 0 obj\n$content\nendobj\n")
        }
        val xref = body.length
        body.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { body.append(String.format("%010d 00000 n \n", it)) }
        body.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return body.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
