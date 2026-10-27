package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class DefaultProvidersTest {
    @Test
    fun `default providers only contain deepseek with balance query`() {
        assertEquals(1, DEFAULT_PROVIDERS.size)
        val provider = DEFAULT_PROVIDERS.single() as ProviderSetting.OpenAI
        // id 不能变：老设置里的 DeepSeek 条目（Key、模型）靠它对上
        assertEquals(Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b"), provider.id)
        assertEquals("DeepSeek", provider.name)
        assertEquals("https://api.deepseek.com/v1", provider.baseUrl)
        assertEquals("", provider.apiKey)
        assertTrue(provider.builtIn)
        assertTrue(provider.quotaQuery.enabled)
        assertEquals("{{baseUrl}}/user/balance", provider.quotaQuery.url)
        assertEquals("balance_infos[0].total_balance", provider.quotaQuery.resultPath)
    }
}
