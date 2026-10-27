package whaleui.nightsky.ltd.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.UUID
import whaleui.nightsky.ltd.data.uploads.BitmapImageShrinker
import whaleui.nightsky.ltd.data.uploads.ChatUploads
import whaleui.nightsky.ltd.data.uploads.ImageShrinker
import whaleui.nightsky.ltd.data.uploads.ShrunkImage
import whaleui.nightsky.ltd.data.uploads.UploadSource
import whaleui.nightsky.ltd.data.uploads.fileOfUrl
import whaleui.nightsky.ltd.domain.settings.AvatarImport
import whaleui.nightsky.ltd.domain.settings.HerAvatarStorage

/**
 * 她的自定义头像文件：`files/whale/her_avatar/` 下最多留一张（当前用的那张）+ 编辑中还没保存的那张。
 *
 * 选图走和聊天附件同一套 [ChatUploads]（同样的 20 MB 上限、读不出 / 太大的提示、EXIF 转正并去掉定位），
 * 只是重新编码那一步换成 [SquareAvatarShrinker]：居中裁成正方形、边长不超过 [AVATAR_SIDE]。
 */
class HerAvatarFiles(
    filesDir: File,
    source: UploadSource,
    shrinker: ImageShrinker = SquareAvatarShrinker(),
) : HerAvatarStorage {
    val dir: File = File(filesDir, "whale/her_avatar")
    private val uploads = ChatUploads(dir = dir, source = source, shrinker = shrinker)

    /** 把系统相册选中的图片处理好存进私有目录，返回文件路径。 */
    override fun import(uri: String): AvatarImport = when (val result = uploads.import(uri)) {
        is ChatUploads.Result.Rejected -> AvatarImport.Rejected(result.reason)
        is ChatUploads.Result.Ok -> {
            val attachment = result.attachment
            val file = fileOfUrl(attachment.uri)
            val mime = attachment.mimeType.orEmpty()
            when {
                file == null -> AvatarImport.Rejected("这张图读不出来")
                // 头像只收能静态显示的图片；GIF 按 ChatUploads 的规则原样保留，这里不收
                !mime.startsWith("image/") || mime == "image/gif" -> {
                    file.delete()
                    AvatarImport.Rejected("头像只能用 JPG、PNG 或 WebP 图片")
                }
                else -> AvatarImport.Ok(file.absolutePath)
            }
        }
    }

    /** 删掉这个目录里除 [keep] 以外的所有文件（保存或放弃编辑之后调用，不留孤儿文件）。 */
    override fun retainOnly(keep: String?) {
        val keepPath = keep?.let { runCatching { File(it).canonicalPath }.getOrNull() }
        dir.listFiles()?.forEach { file ->
            if (file.isFile && runCatching { file.canonicalPath }.getOrNull() != keepPath) file.delete()
        }
    }

    companion object {
        /** 头像边长上限（像素）。显示最大 96dp，xxxhdpi 下约 384px，留一点余量。 */
        const val AVATAR_SIDE = 512

        /** 居中裁正方形：返回 (left, top, side)。 */
        fun centerSquare(width: Int, height: Int): Triple<Int, Int, Int> {
            val side = minOf(width, height)
            return Triple((width - side) / 2, (height - side) / 2, side)
        }
    }
}

/**
 * 先用聊天附件的 [BitmapImageShrinker] 按 EXIF 转正、缩小，再居中裁成正方形、缩到 [side] 以内，存 JPEG
 * （带透明的 PNG / WebP 存 PNG，透明区域在圆形头像里看不出差别，但不会变黑）。
 */
class SquareAvatarShrinker(private val side: Int = HerAvatarFiles.AVATAR_SIDE) : ImageShrinker {
    override fun shrink(source: File, mime: String, outputDir: File): ShrunkImage? {
        // 短边缩到 side 时长边最长是 side 的几倍不确定，这里先把长边压到 4×side，内存够用又不至于糊
        val oriented = BitmapImageShrinker(maxSide = side * 4).shrink(source, mime, outputDir) ?: return null
        try {
            val decoded = BitmapFactory.decodeFile(oriented.file.absolutePath) ?: return null
            val (left, top, square) = HerAvatarFiles.centerSquare(decoded.width, decoded.height)
            val target = minOf(square, side)
            val cropped = Bitmap.createBitmap(decoded, left, top, square, square)
            val scaled = if (target == square) cropped else Bitmap.createScaledBitmap(cropped, target, target, true)
            val png = oriented.mime == "image/png"
            val file = File(outputDir, "avatar_${UUID.randomUUID()}.${if (png) "png" else "jpg"}")
            try {
                val ok = file.outputStream().use { stream ->
                    scaled.compress(
                        if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                        if (png) 100 else JPEG_QUALITY,
                        stream,
                    )
                }
                if (!ok) {
                    file.delete()
                    return null
                }
            } finally {
                if (scaled !== cropped) scaled.recycle()
                if (cropped !== decoded) cropped.recycle()
                decoded.recycle()
            }
            return ShrunkImage(file, if (png) "image/png" else "image/jpeg")
        } finally {
            oriented.file.delete()
        }
    }

    private companion object {
        const val JPEG_QUALITY = 90
    }
}
