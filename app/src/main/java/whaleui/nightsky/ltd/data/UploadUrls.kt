package whaleui.nightsky.ltd.data

/**
 * AI 层 `encodeBase64` 能直接处理的图片 URL 形式：`file://` / `data:` / `http(s)`。
 *
 * 其它形式（`content://`，也就是相册和文件选择器给的）必须先复制成 `file://`，
 * 否则 `FileEncoder` 会抛 `IllegalArgumentException` 且被静默吃掉，图片根本上不了。
 * 抽成纯函数是为了能单测（复制那一步要真机/模拟器的 ContentResolver）。
 */
internal fun isDirectlyEncodable(uri: String): Boolean =
    uri.startsWith("file://") || uri.startsWith("data:") || uri.startsWith("http://") || uri.startsWith("https://")
/**
 * 生成本地文件 URL。
 *
 * 注意不能用 `File.toURI().toString()`：它给的是 `file:/data/...`（单斜杠），
 * 而 AI 层的判断是 `startsWith("file://")`，单斜杠会掉进 else 分支抛异常。
 */
internal fun fileUrlOf(file: java.io.File): String = "file://" + file.absolutePath
/** 从 SAF 的 Uri 取显示文件名（用于导入日记识别 `YYYY-MM-DD.md` / `.zip`）。 */
internal fun displayName(context: android.content.Context, uri: android.net.Uri): String {
    if (uri.scheme == "file") return uri.lastPathSegment.orEmpty()
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
}