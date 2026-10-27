package whaleui.nightsky.ltd.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import whaleui.nightsky.ltd.domain.settings.SecretProvider
import whaleui.nightsky.ltd.domain.settings.SecretStore
import whaleui.nightsky.ltd.domain.settings.SecretWriteResult

/**
 * `SecretStore` 在**真实设备/模拟器**上的往返测试（任务书 2.2 的硬要求）。
 *
 * 之前只有代码审查，没有真正跑过解密路径（见 `_contracts/settings-data-layer.md` 的「未验证项」）。
 * 这个测试用**假字符串**，不涉及任何真实 Key；跑完会把两个 provider 都清掉。
 */
@RunWith(AndroidJUnit4::class)
class EncryptedSecretStoreTest {

    private lateinit var store: EncryptedSecretStore

    @Before
    fun setUp() {
        store = EncryptedSecretStore(ApplicationProvider.getApplicationContext())
        runBlocking {
            store.clear(SecretProvider.DeepSeek)
            store.clear(SecretProvider.MiniMax)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            store.clear(SecretProvider.DeepSeek)
            store.clear(SecretProvider.MiniMax)
        }
    }

    @Test
    fun setThenGetReturnsTheSameValue() = runBlocking {
        val fake = "test-key-not-real-0123456789"
        assertEquals(SecretWriteResult.Saved, store.set(SecretProvider.DeepSeek, fake))
        assertEquals(fake, store.get(SecretProvider.DeepSeek))
        assertTrue(store.has(SecretProvider.DeepSeek))
    }

    @Test
    fun clearThenGetReturnsNull() = runBlocking {
        store.set(SecretProvider.MiniMax, "another-fake-value")
        assertEquals("another-fake-value", store.get(SecretProvider.MiniMax))
        store.clear(SecretProvider.MiniMax)
        assertNull(store.get(SecretProvider.MiniMax))
        assertFalse(store.has(SecretProvider.MiniMax))
    }

    @Test
    fun getWithoutSetReturnsNull() = runBlocking {
        assertNull(store.get(SecretProvider.DeepSeek))
        assertFalse(store.has(SecretProvider.DeepSeek))
    }

    @Test
    fun providersAreIndependent() = runBlocking {
        store.set(SecretProvider.DeepSeek, "deepseek-fake")
        store.set(SecretProvider.MiniMax, "minimax-fake")
        assertEquals("deepseek-fake", store.get(SecretProvider.DeepSeek))
        assertEquals("minimax-fake", store.get(SecretProvider.MiniMax))
    }

    @Test
    fun emptyStringClearsTheValue() = runBlocking {
        store.set(SecretProvider.DeepSeek, "will-be-cleared")
        store.set(SecretProvider.DeepSeek, "")
        assertNull(store.get(SecretProvider.DeepSeek))
    }

    @Test
    fun unicodeAndLongValuesSurviveTheRoundTrip() = runBlocking {
        val chinese = "测试用的假密钥，不是真的-😄-" + "x".repeat(512)
        store.set(SecretProvider.MiniMax, chinese)
        assertEquals(chinese, store.get(SecretProvider.MiniMax))
    }

    @Test
    fun valueSurvivesANewStoreInstance() = runBlocking {
        val fake = "persisted-fake-key"
        store.set(SecretProvider.DeepSeek, fake)
        // 换一个实例读，走的是真正的「落盘 + 重新解密」路径
        val reopened = EncryptedSecretStore(ApplicationProvider.getApplicationContext())
        assertEquals(fake, reopened.get(SecretProvider.DeepSeek))
    }
}
