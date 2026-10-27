package whaleui.nightsky.ltd.data.uploads

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.util.UUID

/** 线上用的 [ChatUploads]：ContentResolver 读、Bitmap 压缩，落在 `files/whale/uploads/`。 */
fun androidChatUploads(context: Context): ChatUploads {
    val app = context.applicationContext
    return ChatUploads(
        dir = ChatUploads.uploadsDir(app.filesDir),
        source = AndroidUploadSource(app),
        shrinker = BitmapImageShrinker(),
    )
}

class AndroidUploadSource(private val context: Context) : UploadSource {
    override fun describe(uri: String): UploadInfo {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") {
            val file = File(parsed.path.orEmpty())
            return UploadInfo(name = file.name, size = file.length().takeIf { file.isFile })
        }
        var name: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(
                parsed,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    if (!cursor.isNull(0)) name = cursor.getString(0)
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        }
        val mime = runCatching { context.contentResolver.getType(parsed) }.getOrNull()
        return UploadInfo(name = name, size = size?.takeIf { it >= 0 }, mime = mime)
    }

    override fun open(uri: String): InputStream? {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") return File(parsed.path.orEmpty()).inputStream()
        return context.contentResolver.openInputStream(parsed)
    }
}

/**
 * 图片重新编码：先按 2 的幂采样解码，再精确缩放到长边 ≤ 2048，按 EXIF 方向转正。
 * 重新编码本身就把 EXIF（含 GPS 定位）全部去掉了；方向已经转进像素里，所以不需要保留 EXIF。
 */
class BitmapImageShrinker(private val maxSide: Int = ChatUploads.MAX_IMAGE_SIDE) : ImageShrinker {
    override fun shrink(source: File, mime: String, outputDir: File): ShrunkImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decoded = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = ChatUploads.sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSide)
            },
        ) ?: return null

        val orientation = runCatching {
            ExifInterface(source.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val (targetWidth, targetHeight) = ChatUploads.scaledSize(decoded.width, decoded.height, maxSide)
        val matrix = Matrix().apply {
            postScale(targetWidth.toFloat() / decoded.width, targetHeight.toFloat() / decoded.height)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(270f)
                    postScale(-1f, 1f)
                }
            }
        }
        val output = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val png = ChatUploads.keepsPng(mime, output.hasAlpha())
        val file = File(outputDir, "upload_${UUID.randomUUID()}.${if (png) "png" else "jpg"}")
        try {
            file.outputStream().use { stream ->
                val ok = output.compress(
                    if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                    if (png) 100 else JPEG_QUALITY,
                    stream,
                )
                if (!ok) {
                    file.delete()
                    return null
                }
            }
        } finally {
            if (output !== decoded) output.recycle()
            decoded.recycle()
        }
        return ShrunkImage(file, if (png) "image/png" else "image/jpeg")
    }

    private companion object {
        const val JPEG_QUALITY = 88
    }
}
