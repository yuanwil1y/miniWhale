package me.rerere.ai.provider

import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** B2：只剩 OpenAI 兼容一种实现；旧设置里其它类型的供应商要给出说明性的错误，而不是崩溃。 */
class ProviderManagerTest {
    private val manager = ProviderManager(OkHttpClient(), context = null)

    @Test
    fun `openai compatible settings get the openai provider`() {
        assertTrue(manager.getProviderByType(ProviderSetting.OpenAI()) is OpenAIProvider)
    }

    @Test
    fun `legacy provider types are rejected with a readable error`() {
        listOf(
            ProviderSetting.Google(name = "旧 Gemini"),
            ProviderSetting.Claude(name = "旧 Claude"),
            ProviderSetting.ChatGPT(name = "旧 ChatGPT"),
            ProviderSetting.Grok(name = "旧 Grok"),
        ).forEach { setting ->
            try {
                manager.getProviderByType(setting)
                fail("${setting.name} should be rejected")
            } catch (e: UnsupportedProviderException) {
                assertTrue(e.message!!, e.message!!.contains(setting.name))
            }
        }
    }
}
