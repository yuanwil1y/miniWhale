package whaleui.nightsky.ltd.sticker

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream

/**
 * 系统文件选择器（SAF）目录源：用户在「导入表情库」里挑一个目录后，用它读那一棵树。
 *
 * [StickerParser] 总是先 `list` 父目录再读里面的文件，所以这里在 `list` 时把子项
 * 的 document uri 缓存下来，后面的 `readText` / `open` 直接取缓存，不再重复查数据库。
 */
class SafStickerSource(
    private val context: Context,
    private val treeUri: Uri,
) : StickerSource {

    /** 相对路径 → 子项 document uri（root 是 `""`）。 */
    private val uriByPath: MutableMap<String, Uri> = mutableMapOf(
        "" to DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        ),
    )

    private val lengthByPath: MutableMap<String, Long> = mutableMapOf()

    override fun list(path: String): List<StickerSource.Entry> {
        val parentUri = uriByPath[path] ?: return emptyList()
        val parentDocId = DocumentsContract.getDocumentId(parentUri)
        val queryUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val prefix = if (path.isBlank()) "" else path.trimEnd('/') + "/"
        val result = mutableListOf<StickerSource.Entry>()
        runCatching {
            context.contentResolver.query(
                queryUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val childPath = prefix + name
                    uriByPath[childPath] = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    lengthByPath[childPath] = size
                    result.add(
                        StickerSource.Entry(
                            name = name,
                            isDirectory = isDirectory,
                            length = if (isDirectory) 0L else size,
                        ),
                    )
                }
            }
        }
        return result.sortedBy { it.name }
    }

    /**
     * 直接复用 [list] 时建好的缓存：父目录列出来过、里面有这个名字就是存在（W-016），
     * 不再每次单独发一次 ContentResolver 查询。
     */
    override fun exists(path: String): Boolean = runCatching { resolve(path) != null }.getOrDefault(false)

    override fun length(path: String): Long = lengthByPath[path] ?: -1L

    /** provider 读失败（IOException / SecurityException）返回 null，由解析器记成这个系列的跳过原因。 */
    override fun readText(path: String): String? = runCatching {
        open(path)?.use { readTextLimited(it) }
    }.getOrNull()

    override fun readHead(path: String, count: Int): ByteArray? = runCatching {
        open(path)?.use { readAtMost(it, count) }
    }.getOrNull()

    override fun open(path: String): InputStream? {
        val uri = resolve(path) ?: return null
        return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    /**
     * 拿某个相对路径对应的 document uri。
     *
     * SAF 只能按「父目录的子项」拿到子文档 id，所以缓存里没有时逐级往下 `list` 一遍
     * （[StickerParser] 有时会直接用 `index.json` 里的文件夹名读 `series.json`，
     * 中间没有先列那个目录，这里必须能自己补上）。
     */
    private fun resolve(path: String): Uri? {
        uriByPath[path]?.let { return it }
        if (path.isBlank()) return null
        val parent = if ('/' in path) path.substringBeforeLast('/') else ""
        if (uriByPath[parent] == null && resolve(parent) == null) return null
        list(parent)
        return uriByPath[path]
    }
}
