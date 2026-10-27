package whaleui.nightsky.ltd.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import whaleui.nightsky.ltd.domain.settings.PersonaDocument
import whaleui.nightsky.ltd.domain.settings.PersonaImportError
import whaleui.nightsky.ltd.domain.settings.PersonaImportResult
import whaleui.nightsky.ltd.domain.settings.PersonaKind
import whaleui.nightsky.ltd.domain.settings.PersonaStore

/**
 * soul.md / user.md 的文件实现。
 *
 * 两份正文落在应用私有目录 `filesDir/persona/`，文件名固定（见 [PersonaKind.fileName]），
 * 不参与备份。界面只关心「有没有导入、多大、什么时候导入的」，所以 [observe] 只发元信息，
 * 正文按需通过 [readText] 读。
 *
 * 导入流程（顺序固定，任一环失败都不改动原有文件）：
 * 1. MIME 检查：非空且不是文本类 → [PersonaImportError.NotText]；
 * 2. 大小检查：最多读 [PersonaStore.MaxBytes] + 1 字节，超了 → [PersonaImportError.TooLarge]；
 * 3. 严格 UTF-8 解码：非法字节 → [PersonaImportError.InvalidEncoding]；
 * 4. 原子写入（同目录临时文件 + rename）；
 * 5. 打开 Uri / 读流失败等 IO 问题 → [PersonaImportError.NotReadable]。
 *
 * @param context 用于拿 filesDir 和 contentResolver，只保留 applicationContext。
 */
class FilePersonaStore(context: Context) : PersonaStore {

    private val appContext: Context = context.applicationContext

    /** 文件懒加载只做一次；并发订阅时用 [loadMutex] 兜住。 */
    private val loaded = AtomicBoolean(false)
    private val loadMutex = Mutex()

    /** 写文件的串行化锁，避免两次导入交叉写同一个临时文件。 */
    private val writeMutex = Mutex()

    private val states: Map<PersonaKind, MutableStateFlow<PersonaDocument?>> =
        PersonaKind.entries.associateWith { MutableStateFlow<PersonaDocument?>(null) }

    override fun observe(kind: PersonaKind): Flow<PersonaDocument?> = flow {
        // 首次订阅时才去读磁盘，构造函数里不做 IO。
        ensureLoaded()
        emitAll(states.getValue(kind))
    }

    override suspend fun import(kind: PersonaKind, uri: Uri): PersonaImportResult =
        // 打开 Uri、读流、写文件全部在 IO 线程。
        withContext(Dispatchers.IO) { importFromUri(kind, uri) }

    private suspend fun importFromUri(kind: PersonaKind, uri: Uri): PersonaImportResult {
        // MIME 检查：拿不到类型时按「未知」放行；zip 也放行（导出包整包导入人设）。
        val mimeType = try {
            appContext.contentResolver.getType(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PersonaImportResult.Failure(PersonaImportError.NotReadable)
        }
        if (mimeType != null && !isTextMimeType(mimeType) && !isZipMimeType(mimeType)) {
            return PersonaImportResult.Failure(PersonaImportError.NotText)
        }

        return try {
            val stream = appContext.contentResolver.openInputStream(uri)
                ?: return PersonaImportResult.Failure(PersonaImportError.NotReadable)
            stream.use { raw ->
                val input = BufferedInputStream(raw)
                input.mark(4)
                val head = ByteArray(4)
                val headRead = readUpTo(input, head)
                input.reset()
                if (headRead == 4 && isZipHeader(head)) {
                    // 导出包：按文件名在 zip 里找 soul.md / user.md。
                    val text = readPersonaTextFromZip(input, kind.fileName)
                        ?: return PersonaImportResult.Failure(PersonaImportError.NotReadable)
                    writeImported(kind, text)
                } else {
                    val bytes = readCapped(input)
                    writeImported(kind, decodeUtf8Strict(bytes))
                }
            }
        } catch (e: PersonaTooLarge) {
            PersonaImportResult.Failure(PersonaImportError.TooLarge)
        } catch (e: CharacterCodingException) {
            PersonaImportResult.Failure(PersonaImportError.InvalidEncoding)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PersonaImportResult.Failure(PersonaImportError.NotReadable)
        }
    }

    private suspend fun writeImported(kind: PersonaKind, text: String): PersonaImportResult {
        val document = writeDocument(kind, text.toByteArray(Charsets.UTF_8))
        states.getValue(kind).value = document
        return PersonaImportResult.Success(document)
    }

    /** 在 zip 里找和目标同名的文件（容忍带目录），读到上限为止后严格解码。 */
    private fun readPersonaTextFromZip(input: InputStream, fileName: String): String? {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/').substringAfterLast('/')
                if (!name.equals(fileName, ignoreCase = true)) continue
                return decodeUtf8Strict(readCapped(zip))
            }
        }
        return null
    }

    private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    private fun isZipHeader(head: ByteArray): Boolean =
        head.size >= 4 &&
            head[0] == 'P'.code.toByte() &&
            head[1] == 'K'.code.toByte() &&
            head[2] == 3.toByte() &&
            head[3] == 4.toByte()

    private fun isZipMimeType(mimeType: String): Boolean {
        val type = mimeType.substringBefore(';').trim().lowercase()
        return type == "application/zip" ||
            type == "application/x-zip-compressed" ||
            type == "application/x-compressed"
    }

    override suspend fun readText(kind: PersonaKind): String? = withContext(Dispatchers.IO) {
        val file = fileFor(kind)
        if (!file.isFile) return@withContext null
        try {
            file.readText(Charsets.UTF_8)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun remove(kind: PersonaKind) {
        withContext(Dispatchers.IO) {
            // 和导入 / 恢复共用一把锁，避免「正在导入时点移除」把文件写回来。
            writeMutex.withLock {
                val file = fileFor(kind)
                if (file.exists()) file.delete()
            }
        }
        states.getValue(kind).value = null
    }

    override suspend fun restore(kind: PersonaKind, text: String): PersonaDocument? {
        // 撤销移除：正文已经校验过，直接按 UTF-8 原子写回；导入时间记为现在。
        val document = try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            writeDocument(kind, bytes, importedAt = System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        states.getValue(kind).value = document
        return document
    }

    // ---------------------------- 加载 ---------------------------------------

    private suspend fun ensureLoaded() {
        if (loaded.get()) return
        loadMutex.withLock {
            if (loaded.get()) return
            val documents = withContext(Dispatchers.IO) {
                PersonaKind.entries.map { kind -> kind to readDocument(kind) }
            }
            documents.forEach { (kind, document) ->
                states.getValue(kind).value = document
            }
            loaded.set(true)
        }
    }

    private fun readDocument(kind: PersonaKind): PersonaDocument? {
        val file = fileFor(kind)
        if (!file.isFile) return null
        return try {
            PersonaDocument(
                kind = kind,
                sizeBytes = file.length(),
                importedAt = file.lastModified(),
            )
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------- 导入 / 写入 --------------------------------

    /**
     * 从 [input] 读原始字节，最多 [PersonaStore.MaxBytes] + 1 字节；
     * 多读 1 字节只为判断「超限」，不会把超限内容读进内存。
     */
    private fun readCapped(input: InputStream): ByteArray {
        val buffer = ByteArray(ReadBufferSize)
        val output = ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > PersonaStore.MaxBytes) throw PersonaTooLarge()
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    /** 严格解码：任何非法 / 不可映射字节都抛 [CharacterCodingException]，不做替换。 */
    private fun decodeUtf8Strict(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun isTextMimeType(mimeType: String): Boolean {
        val type = mimeType.substringBefore(';').trim().lowercase()
        return type.startsWith("text/") ||
            type == "application/octet-stream" ||
            type == "application/x-markdown"
    }

    /**
     * 原子写入并返回新的元信息。
     *
     * 先写 `soul.md.tmp` 再 rename 覆盖：任何一步失败都只留下临时文件（会被清掉），
     * 原来的 soul.md / user.md 保持不变。
     */
    private suspend fun writeDocument(
        kind: PersonaKind,
        bytes: ByteArray,
        importedAt: Long? = null,
    ): PersonaDocument = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val target = fileFor(kind)
            val dir = target.parentFile ?: throw IOException("人设目录不可用")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建人设目录")
            val temp = File(dir, target.name + ".tmp")
            try {
                FileOutputStream(temp).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                if (!temp.renameTo(target)) {
                    // 个别文件系统不覆盖已存在的目标文件，删掉再试一次。
                    target.delete()
                    if (!temp.renameTo(target)) throw IOException("无法保存人设文件")
                }
            } catch (e: CancellationException) {
                temp.delete()
                throw e
            } catch (e: Exception) {
                temp.delete()
                throw e
            }
            PersonaDocument(
                kind = kind,
                sizeBytes = target.length(),
                importedAt = importedAt ?: target.lastModified(),
            )
        }
    }

    private fun fileFor(kind: PersonaKind): File =
        File(File(appContext.filesDir, DirName), kind.fileName)

    /** 超过大小上限的内部信号，转成 [PersonaImportError.TooLarge]。 */
    private class PersonaTooLarge : Exception()

    private companion object {
        const val DirName = "persona"
        const val ReadBufferSize = 16 * 1024
    }
}
