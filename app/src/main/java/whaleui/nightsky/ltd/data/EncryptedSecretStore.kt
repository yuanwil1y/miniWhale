package whaleui.nightsky.ltd.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import whaleui.nightsky.ltd.domain.settings.SecretProvider
import whaleui.nightsky.ltd.domain.settings.SecretStore
import whaleui.nightsky.ltd.domain.settings.SecretWriteResult

/**
 * 本机加密的密钥存储。
 *
 * 明文只存在于内存里：落盘的是「随机 IV + AES-GCM 密文」的拼接，再整体 base64。
 * 密钥由 Android Keystore 保管（alias `whale_settings_secret_v1`），即使应用私有目录被拷走也解不开。
 *
 * 文件格式（UTF-8 文本，一行一项）：`<SecretProvider.id>=<base64(iv || ciphertext)>`。
 * 只有 [SecretProvider] 里的几项（两把 API Key、代理密码），不存别的东西。
 *
 * 注意：
 * - 不使用日志打印任何值，异常信息里也不带明文；
 * - Keystore 密钥被系统作废（例如改锁屏）时解密会失败，这里按「没设置过」返回 null，不崩溃；
 * - 写入失败（Keystore 加密不了、写盘出错）不抛异常，返回 [SecretWriteResult.Failed] 交给界面提示（W-045），
 *   原来的密文保持不变。
 *
 * 加解密和目录都可以注入，JVM 单测用假实现（`EncryptedSecretStoreTest`）。
 */
class EncryptedSecretStore internal constructor(
    private val dir: () -> File,
    private val cipher: SecretCipher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SecretStore {

    /** @param context 用于定位应用私有目录，只取 applicationContext，避免持有 Activity。 */
    constructor(context: Context) : this(
        dir = { File(context.applicationContext.filesDir, DirName) },
        cipher = KeystoreSecretCipher,
    )

    /** 读写都串行化：文件是整份改写的，并发写会互相覆盖。 */
    private val mutex = Mutex()

    override suspend fun get(provider: SecretProvider): String? = withContext(io) {
        mutex.withLock {
            val payload = readEntriesLocked()[provider.id] ?: return@withLock null
            cipher.decrypt(payload)
        }
    }

    override suspend fun set(provider: SecretProvider, value: String): SecretWriteResult = withContext(io) {
        mutex.withLock {
            val entries = readEntriesLocked()
            if (value.isBlank()) {
                // 约定：空串视同清除，不保留空密文。
                return@withLock if (entries.remove(provider.id) != null) writeEntriesLocked(entries) else SecretWriteResult.Saved
            }
            // 加密不了时不落盘，也不清空已有密文（W-045：以前这里静默返回，界面照样提示「已保存」）
            val encrypted = cipher.encrypt(value)
                ?: return@withLock SecretWriteResult.Failed("系统密钥库暂时不可用，无法加密")
            entries[provider.id] = encrypted
            writeEntriesLocked(entries)
        }
    }

    override suspend fun clear(provider: SecretProvider): SecretWriteResult = withContext(io) {
        mutex.withLock {
            val entries = readEntriesLocked()
            if (entries.remove(provider.id) != null) writeEntriesLocked(entries) else SecretWriteResult.Saved
        }
    }

    override suspend fun has(provider: SecretProvider): Boolean = withContext(io) {
        mutex.withLock { readEntriesLocked().containsKey(provider.id) }
    }

    // ---------------------------- 文件读写 -----------------------------------

    private fun secretsFile(): File = File(dir(), FileName)

    /** 读整份文件。文件不存在 / 内容损坏时都当作空表，界面看到的就是「没设置过」。 */
    private fun readEntriesLocked(): MutableMap<String, String> {
        val file = secretsFile()
        if (!file.isFile) return mutableMapOf()
        val lines = try {
            file.readText(Charsets.UTF_8).lineSequence()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return mutableMapOf()
        }
        val entries = mutableMapOf<String, String>()
        lines.forEach { line ->
            val separator = line.indexOf('=')
            if (separator > 0 && separator < line.length - 1) {
                entries[line.substring(0, separator)] = line.substring(separator + 1)
            }
        }
        return entries
    }

    /**
     * 整份改写：先写同目录临时文件、同步到磁盘，再 rename 覆盖，避免半个文件。
     * 写盘出错不抛异常（以前会一路抛到设置页的 viewModelScope 里让 App 崩掉，W-045），返回失败原因；
     * 原因里只有我们自己的说明和异常类型，不带路径以外的内容，更不会带明文。
     */
    private fun writeEntriesLocked(entries: Map<String, String>): SecretWriteResult {
        val dir = dir()
        val temp = File(dir, "$FileName.tmp")
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) throw SecretFileException("无法创建密钥目录")
            FileOutputStream(temp).use { out ->
                entries.forEach { (id, payload) ->
                    out.write((id + "=" + payload + "\n").toByteArray(Charsets.UTF_8))
                }
                out.flush()
                out.fd.sync()
            }
            val target = secretsFile()
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw SecretFileException("无法写入密钥文件")
            }
            SecretWriteResult.Saved
        } catch (e: CancellationException) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            // 系统异常的 message 可能带文件路径，只取类型名
            val detail = (e as? SecretFileException)?.message ?: e.javaClass.simpleName
            SecretWriteResult.Failed("写入本机存储失败（$detail）")
        }
    }

    private class SecretFileException(message: String) : IOException(message)

    private companion object {
        const val DirName = "secrets"
        const val FileName = "secrets.bin"
    }
}

/** 加解密一条记录（含 base64）；返回 null 表示做不了，不抛异常、不在异常里带明文或密文 */
internal interface SecretCipher {
    fun encrypt(plain: String): String?
    fun decrypt(encoded: String): String?
}

/** Android Keystore 里的 AES-256-GCM 密钥 */
internal object KeystoreSecretCipher : SecretCipher {

    /** Keystore 里的密钥别名，改名会让已存密钥读不到。 */
    private const val KeyAlias = "whale_settings_secret_v1"
    private const val AndroidKeyStore = "AndroidKeyStore"
    private const val Transformation = "AES/GCM/NoPadding"
    private const val KeyBits = 256

    /** GCM 推荐 96 位（12 字节）IV；每次写入重新随机，绝不重复使用。 */
    private const val IvBytes = 12
    private const val TagBits = 128

    /**
     * 加密一段明文。返回 null 表示 Keystore 暂时不可用（例如密钥被作废）。
     *
     * IV 由 Keystore 自己生成（setRandomizedEncryptionRequired(true) 的硬性要求：
     * 加密时禁止调用方传 nonce，否则 keystore2 会报 CALLER_NONCE_PROHIBITED）。
     * 加密完把 [Cipher.getIV] 的结果和密文拼在一起，解密时再传回去。
     */
    override fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(Transformation).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val iv = cipher.iv
        if (iv == null || iv.size != IvBytes) {
            // Keystore 默认给 12 字节；长度对不上就不落盘，避免写出解不开的密文。
            null
        } else {
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * 解密一条记录。IV 长度不对、密钥被作废、密文被改都会走到异常分支，
     * 一律返回 null（= 这条不存在），且不把任何密文内容带进异常信息。
     */
    override fun decrypt(encoded: String): String? = try {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        if (payload.size <= IvBytes) {
            null
        } else {
            val iv = payload.copyOfRange(0, IvBytes)
            val ciphertext = payload.copyOfRange(IvBytes, payload.size)
            val cipher = Cipher.getInstance(Transformation).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TagBits, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** 取已有密钥，没有就生成一把；只在 EncryptedSecretStore 的锁内调用。 */
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        val existing = (keyStore.getEntry(KeyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore)
        generator.init(
            KeyGenParameterSpec.Builder(
                KeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KeyBits)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
