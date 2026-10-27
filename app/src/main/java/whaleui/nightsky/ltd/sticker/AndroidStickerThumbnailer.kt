package whaleui.nightsky.ltd.sticker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 缩略图生成（任务书 3.1：首帧，长边 256，PNG）。
 *
 * 只解第一帧（`BitmapFactory` 对动态 WebP 就是解第一帧），所以 6 个系列 144 张
 * 也只是一次小图解码；缩放后存 PNG，透明区域保留透明（面板背景透出来）。
 *
 * 生成失败不阻断导入：面板会退回用原图（Coil 也能显示 webp 首帧）。
 */
class AndroidStickerThumbnailer(
    /** App 的 `filesDir`：缩略图只允许写进 `filesDir/stickers/_thumbs`（W-014）。 */
    private val filesDir: File,
) : StickerThumbnailer {

    override fun generate(source: File, destFile: File): Boolean = runCatching {
        val thumbsDir = File(stickersRoot(filesDir), STICKER_THUMB_DIR_NAME)
        if (!isInsideDir(destFile, thumbsDir)) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, STICKER_THUMB_MAX_EDGE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, options) ?: return false
        val target = scaleToMaxEdge(bitmap, STICKER_THUMB_MAX_EDGE)
        destFile.parentFile?.mkdirs()
        destFile.outputStream().use { out ->
            target.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (target !== bitmap) target.recycle()
        bitmap.recycle()
        destFile.length() > 0L
    }.getOrDefault(false)

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxEdge && height / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return sample
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
