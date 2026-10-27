package whaleui.nightsky.ltd.sticker

import java.io.File
import java.io.IOException
import java.io.InputStream

/** 表情 zip 整包复制进缓存时的上限（N-005）；单张图另有 [STICKER_MAX_BYTES]，这里只防一个巨大的包把存储写满。 */
const val STICKER_ZIP_MAX_BYTES: Long = 200L * 1024 * 1024

/** 整包超过 [STICKER_ZIP_MAX_BYTES]（按实际读到的字节算，不信文件管理器报的大小） */
class StickerZipTooLargeException : IOException("表情包超过 200 MB，没有导入")

/**
 * 把选中的 zip 复制到 [target]，边读边累计字节数，超过 [limit] 立刻中止并删掉 [target]（N-005）。
 * 其它读写失败同样删掉半成品后把异常抛给调用方。
 *
 * @return 复制的字节数
 */
fun copyStickerZipLimited(input: InputStream, target: File, limit: Long = STICKER_ZIP_MAX_BYTES): Long {
    var total = 0L
    try {
        target.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw StickerZipTooLargeException()
                out.write(buffer, 0, read)
            }
        }
    } catch (e: Exception) {
        target.delete()
        throw e
    }
    return total
}
