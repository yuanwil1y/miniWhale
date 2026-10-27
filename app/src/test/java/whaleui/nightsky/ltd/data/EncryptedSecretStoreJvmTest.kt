package whaleui.nightsky.ltd.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import whaleui.nightsky.ltd.domain.settings.SecretProvider
import whaleui.nightsky.ltd.domain.settings.SecretWriteResult
import java.io.File

/**
 * W-045：[EncryptedSecretStore] 存不进去时要如实返回失败，不能静默不写，也不能把写盘异常抛给设置页。
 * 加解密换成假的（真 Keystore 的往返在 androidTest 的 `EncryptedSecretStoreTest`），目录用临时目录。
 */
class EncryptedSecretStoreJvmTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** 「加密」= 加个前缀；[available] 为 false 时像 Keystore 不可用一样返回 null */
    private class FakeCipher : SecretCipher {
        var available = true
        override fun encrypt(plain: String): String? = if (available) "enc:" + plain.reversed() else null
        override fun decrypt(encoded: String): String? = encoded.removePrefix("enc:").reversed()
    }

    private val cipher = FakeCipher()

    private fun store(dir: File) = EncryptedSecretStore({ dir }, cipher, Dispatchers.Unconfined)

    @Test
    fun `a saved key reads back and never hits the disk in plain text`() = runBlocking {
        val dir = File(temp.root, "secrets")
        val store = store(dir)
        assertEquals(SecretWriteResult.Saved, store.set(SecretProvider.DeepSeek, "sk-fake-0000"))
        assertEquals("sk-fake-0000", store.get(SecretProvider.DeepSeek))
        assertFalse(File(dir, "secrets.bin").readText().contains("sk-fake-0000"))
        assertEquals(SecretWriteResult.Saved, store.clear(SecretProvider.DeepSeek))
        assertNull(store.get(SecretProvider.DeepSeek))
    }

    @Test
    fun `keystore failure is reported and keeps the old key`() = runBlocking {
        val store = store(File(temp.root, "secrets"))
        store.set(SecretProvider.MiniMax, "mm-old-1111")
        cipher.available = false

        val result = store.set(SecretProvider.MiniMax, "mm-new-2222")

        assertTrue("加密不了不能当成已保存：$result", result is SecretWriteResult.Failed)
        assertFalse((result as SecretWriteResult.Failed).reason.contains("mm-new-2222"))
        cipher.available = true
        assertEquals("原来的 Key 不能被清掉", "mm-old-1111", store.get(SecretProvider.MiniMax))
    }

    @Test
    fun `disk write failure is reported instead of thrown`() = runBlocking {
        // 目录的上一级是个普通文件：建目录失败，相当于写盘出错
        val blocker = temp.newFile("not-a-dir")
        val store = store(File(blocker, "secrets"))

        val saved = store.set(SecretProvider.DeepSeek, "sk-fake-3333")
        assertTrue("写盘失败要返回失败，不能抛出去让设置页崩：$saved", saved is SecretWriteResult.Failed)
        assertFalse((saved as SecretWriteResult.Failed).reason.contains("sk-fake-3333"))
        assertFalse(store.has(SecretProvider.DeepSeek))
    }

    @Test
    fun `failing to replace the secrets file is reported and leaves no temp file`() = runBlocking {
        val dir = File(temp.root, "secrets")
        val store = store(dir)
        // 目标文件的位置被一个非空目录占住：临时文件改名覆盖失败
        val target = File(dir, "secrets.bin")
        target.mkdirs()
        File(target, "x").writeText("x")

        val result = store.set(SecretProvider.DeepSeek, "sk-fake-5555")
        assertTrue("$result", result is SecretWriteResult.Failed)
        assertFalse(File(dir, "secrets.bin.tmp").exists())
    }
}
