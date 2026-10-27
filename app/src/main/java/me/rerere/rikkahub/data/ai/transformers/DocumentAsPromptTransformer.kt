package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import whaleui.nightsky.ltd.data.uploads.UploadMime
import whaleui.nightsky.ltd.data.uploads.fileOfUrl
import java.io.File

/**
 * 把消息里的文档部件换成文字：DeepSeek 只收文字，所以文档内容以 `<UploadFile>` 块的形式放进同一条 user 消息（F5，W-033）。
 *
 * - 解析器按 mime 选；老消息的 mime 是 `application/octet-stream`，按文件名推断（[UploadMime.resolve]）；
 * - 原来给附件加的 `path="/upload/..."` 是给已删除的工作区工具用的（T1 发现），已去掉；
 * - 内容超过 [MAX_DOCUMENT_CHARS] 字截断并注明，避免一份长文档把上下文撑爆（历史里每轮都会重发）。
 */
object DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = withContext(Dispatchers.IO) { documentsAsPrompt(messages) }
}

/**
 * 纯函数部分（JVM 可测）：文档部件换成 `<UploadFile>` 文字，放在这条消息最前面，再和后面的文字合成一段
 * （请求层遇到多个文字部件会发成数组形式的 content，DeepSeek 只认字符串；只改请求，不改库里的消息）。
 */
internal fun documentsAsPrompt(messages: List<UIMessage>): List<UIMessage> = messages.map { message ->
    val documents = message.parts.filterIsInstance<UIMessagePart.Document>()
    if (documents.isEmpty()) return@map message
    val prompts = documents.map { UIMessagePart.Text(documentPrompt(it)) }
    message.copy(
        parts = whaleui.nightsky.ltd.reply.WhaleMessageSlicer.mergeAdjacentTextParts(
            prompts + message.parts.filterNot { it is UIMessagePart.Document },
        ),
    )
}

internal fun documentPrompt(document: UIMessagePart.Document): String {
    val content = readDocumentContent(document)
    return "<UploadFile name=\"${document.fileName.replace("\"", "'")}\">\n$content\n</UploadFile>"
}

internal const val MAX_DOCUMENT_CHARS = 60_000

private fun readDocumentContent(document: UIMessagePart.Document): String {
    val file = fileOfUrl(document.url)
        ?: return "[文件读不出来：${document.fileName}]"
    if (!file.isFile) return "[文件已经不在了：${document.fileName}]"
    val text = runCatching { parse(file, UploadMime.resolve(document.mime, document.fileName)) }
        .getOrElse { return "[文件解析失败：${document.fileName}]" }
        .trim()
    if (text.isEmpty()) return "[文件里没有读到文字：${document.fileName}]"
    if (text.length <= MAX_DOCUMENT_CHARS) return text
    return text.take(MAX_DOCUMENT_CHARS) + "\n[文件太长，后面还有 ${text.length - MAX_DOCUMENT_CHARS} 字没有带上]"
}

private fun parse(file: File, mime: String): String = when (mime) {
    UploadMime.PDF -> PdfParser.parserPdf(file)
    UploadMime.DOCX -> DocxParser.parse(file)
    UploadMime.PPTX -> PptxParser.parse(file)
    UploadMime.EPUB -> EpubParser.parse(file)
    else -> if (UploadMime.isDocument(mime)) file.readText() else error("unsupported $mime")
}
