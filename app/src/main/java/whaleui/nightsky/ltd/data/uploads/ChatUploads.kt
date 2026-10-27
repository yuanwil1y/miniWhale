package whaleui.nightsky.ltd.data.uploads

import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import whaleui.nightsky.ltd.domain.chat.PendingAttachment

/**
 * 他在聊天里附的图片 / 文件：选中时就复制进 App 私有目录 `files/whale/uploads/`，之后只用 `file://`（F5，W-033 / W-039）。
 *
 * 为什么选中时就复制：
 * - `content://` 的临时授权活不过重启，AI 层也只认 `file://`；
 * - 太大、格式不支持、读不出来，在选的那一刻就能提示他，而不是发出去以后静默丢掉；
 * - 发送那一步不再有慢操作，连按两次发送时不容易出事（F3 遗留）。
 *
 * 规则：
 * - 文件名一律用随机 UUID：同一个 URI 背后换了内容（文件管理器类 provider 常见）不会复用旧文件；
 * - 图片、文档都限 [MAX_BYTES]（20 MB），超过直接拒绝并给出原因；复制时按实际字节数再卡一次（provider 报的大小不可信）；
 * - 图片交给 [ImageShrinker]：长边压到 [MAX_IMAGE_SIDE]、按 EXIF 方向转正后重新编码（EXIF 里的定位一起去掉），
 *   带透明的 PNG / WebP 仍存 PNG，其它存 JPEG；
 * - 文档只收能解析成文字的格式（[UploadMime.isDocument]），mime 取不到或是 octet-stream 时按扩展名推断。
 */
class ChatUploads(
    private val dir: File,
    private val source: UploadSource,
    private val shrinker: ImageShrinker,
    private val maxBytes: Long = MAX_BYTES,
) {

    sealed interface Result {
        data class Ok(val attachment: PendingAttachment) : Result
        data class Rejected(val reason: String) : Result
    }

    fun import(uri: String): Result {
        val info = runCatching { source.describe(uri) }.getOrDefault(UploadInfo())
        val name = info.name?.takeIf { it.isNotBlank() } ?: uri.substringAfterLast('/').ifBlank { "附件" }
        val mime = UploadMime.resolve(info.mime, name)
        val kind = UploadMime.kindOf(mime)
        if (kind == UploadKind.Unsupported) {
            return Result.Rejected("暂时只能发图片、PDF、Word（docx）和文本文件：$name")
        }
        val declared = info.size
        if (declared != null && declared > maxBytes) {
            return Result.Rejected("「$name」有 ${formatMb(declared)}，最大只能 ${formatMb(maxBytes)}")
        }
        dir.mkdirs()
        val raw = File(dir, "upload_${UUID.randomUUID()}.${UploadMime.extensionOf(mime, name)}")
        val copied = try {
            val input = source.open(uri) ?: return Result.Rejected("「$name」读不出来")
            input.use { copyLimited(it, raw, maxBytes) }
        } catch (e: Exception) {
            raw.delete()
            return Result.Rejected("「$name」读不出来")
        }
        if (!copied) {
            raw.delete()
            return Result.Rejected("「$name」超过 ${formatMb(maxBytes)}，发不了")
        }
        if (raw.length() == 0L) {
            raw.delete()
            return Result.Rejected("「$name」是空的")
        }
        if (kind == UploadKind.Document) {
            return Result.Ok(attachment(raw, name, mime))
        }
        // 图片：重新编码（转正、缩小、去掉 EXIF）；GIF 动图原样保留（GIF 没有 EXIF）
        if (mime == "image/gif") return Result.Ok(attachment(raw, name, mime))
        val shrunk = runCatching { shrinker.shrink(raw, mime, dir) }.getOrNull()
        raw.delete()
        if (shrunk == null) return Result.Rejected("「$name」这张图读不出来")
        return Result.Ok(attachment(shrunk.file, name, shrunk.mime))
    }

    /** 选了又移除的附件：删掉复制出来的文件（只删 uploads 目录里的） */
    fun discard(uri: String) {
        val file = fileOfUrl(uri) ?: return
        val inside = runCatching { file.canonicalPath.startsWith(dir.canonicalPath + File.separator) }.getOrDefault(false)
        if (inside) file.delete()
    }

    private fun attachment(file: File, name: String, mime: String): PendingAttachment {
        val url = "file://" + file.absolutePath
        return PendingAttachment(id = url, uri = url, name = name, mimeType = mime, size = file.length())
    }

    companion object {
        const val MAX_BYTES: Long = 20L * 1024 * 1024
        const val MAX_IMAGE_SIDE: Int = 2048

        fun uploadsDir(filesDir: File): File = File(filesDir, "whale/uploads")

        /** 最多复制 [limit] 字节；超过时返回 false（调用方删掉半截文件）。 */
        internal fun copyLimited(input: InputStream, target: File, limit: Long): Boolean {
            var total = 0L
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > limit) return false
                    output.write(buffer, 0, read)
                }
            }
            return true
        }

        internal fun formatMb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)

        /**
         * 解码时的采样率：2 的幂，采样后长边仍不小于 [maxSide]（最后一步再精确缩放），
         * 这样 12 MP 照片解码时不用把整张原图读进内存。
         */
        fun sampleSizeFor(width: Int, height: Int, maxSide: Int = MAX_IMAGE_SIDE): Int {
            var sample = 1
            val longest = maxOf(width, height)
            while (longest / (sample * 2) >= maxSide) sample *= 2
            return sample
        }

        /** 等比缩放后的尺寸：长边不超过 [maxSide]，本来就小的不放大。 */
        fun scaledSize(width: Int, height: Int, maxSide: Int = MAX_IMAGE_SIDE): Pair<Int, Int> {
            val longest = maxOf(width, height)
            if (longest <= maxSide) return width to height
            val scale = maxSide.toDouble() / longest
            return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
        }

        /** 重新编码时用 PNG 还是 JPEG：带透明通道的 PNG / WebP 保持 PNG，其余 JPEG。 */
        fun keepsPng(mime: String, hasAlpha: Boolean): Boolean =
            hasAlpha && (mime == "image/png" || mime == "image/webp")
    }
}

/** 选中的附件的描述（文件名、大小、mime），都可能取不到。 */
data class UploadInfo(val name: String? = null, val size: Long? = null, val mime: String? = null)

/** 读附件的来源；线上是 ContentResolver（[AndroidUploadSource]），测试里是内存。 */
interface UploadSource {
    fun describe(uri: String): UploadInfo
    fun open(uri: String): InputStream?
}

/** 压缩结果：新文件和它的 mime */
data class ShrunkImage(val file: File, val mime: String)

/** 图片重新编码；线上是 Bitmap（[BitmapImageShrinker]），JVM 测试里换成假实现。读不出来返回 null。 */
fun interface ImageShrinker {
    fun shrink(source: File, mime: String, outputDir: File): ShrunkImage?
}

enum class UploadKind { Image, Document, Unsupported }

/** 附件 mime 的推断与分类：上传时、拼请求时（老消息的 mime 是 octet-stream）共用。 */
object UploadMime {
    const val PDF = "application/pdf"
    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    const val EPUB = "application/epub+zip"

    private val byExtension = mapOf(
        "pdf" to PDF,
        "docx" to DOCX,
        "pptx" to PPTX,
        "epub" to EPUB,
        "txt" to "text/plain",
        "text" to "text/plain",
        "log" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "json" to "application/json",
        "xml" to "text/xml",
        "html" to "text/html",
        "htm" to "text/html",
        "yaml" to "text/yaml",
        "yml" to "text/yaml",
        "srt" to "text/plain",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "bmp" to "image/bmp",
    )

    private val textLikeApplications = setOf(
        "application/json",
        "application/xml",
        "application/x-yaml",
        "application/yaml",
        "application/x-subrip",
    )

    /** 能解析成文字的格式（`:document` 的解析器 + 纯文本类） */
    private val parsed = setOf(PDF, DOCX, PPTX, EPUB)

    /** provider 报的 mime 可信就用它；取不到、是 octet-stream 或泛型（`*` / `*`）时按扩展名推断。 */
    fun resolve(reported: String?, name: String): String {
        val clean = reported?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val generic = clean.isNullOrEmpty() || clean == "application/octet-stream" || clean.endsWith("/*")
        if (!generic && clean != null) return clean
        return byExtension[extension(name)] ?: clean?.takeUnless { it.isEmpty() } ?: "application/octet-stream"
    }

    fun kindOf(mime: String): UploadKind = when {
        mime.startsWith("image/") -> UploadKind.Image
        isDocument(mime) -> UploadKind.Document
        else -> UploadKind.Unsupported
    }

    fun isDocument(mime: String): Boolean =
        mime in parsed || mime.startsWith("text/") || mime in textLikeApplications

    fun extensionOf(mime: String, name: String): String {
        val fromName = extension(name)
        if (fromName.isNotEmpty() && byExtension[fromName] == mime) return fromName
        return byExtension.entries.firstOrNull { it.value == mime }?.key ?: fromName.ifEmpty { "bin" }
    }

    private fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it.length in 1..8 }.orEmpty()
}

/** `file://` URL → 文件；其它形式返回 null。兼容百分号编码过的（RikkaHub 的 `Uri.fromFile`）和没编码的（我们自己的）。 */
fun fileOfUrl(url: String): File? {
    if (!url.startsWith("file://")) return null
    val encoded = runCatching { java.net.URI(url).path }.getOrNull()
    val raw = url.removePrefix("file://")
    return when {
        File(raw).exists() -> File(raw)
        encoded != null -> File(encoded)
        else -> File(raw)
    }
}
