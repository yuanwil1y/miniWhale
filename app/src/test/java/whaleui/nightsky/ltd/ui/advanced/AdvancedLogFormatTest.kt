package whaleui.nightsky.ltd.ui.advanced

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.android.LogEntry
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.whale.WhaleBootstrap

/** 日志脱敏、代理草稿、Key 后 4 位、启动时不覆盖已选模型（S9a）。 */
class AdvancedLogFormatTest {

    private val key = "sk-abcdefghijklmnop1234"

    @Test
    fun `sensitive headers, url keys, bearer tokens and known secrets are masked`() {
        val format = AdvancedLogFormat(listOf("custom-secret-value-99", "short"))
        assertEquals(AdvancedLogFormat.MASK, format.redactHeader("Authorization", "Bearer $key"))
        assertEquals(AdvancedLogFormat.MASK, format.redactHeader("x-goog-api-key", "AIza-whatever"))
        assertEquals("application/json", format.redactHeader("Content-Type", "application/json"))

        val url = format.redactUrl("https://g.example/v1/models?key=AIzaSECRET&alt=sse")
        assertFalse(url.contains("AIzaSECRET"))
        assertTrue(url.contains("alt=sse"))

        val text = format.redactText("""auth Bearer abc.def-123 {"api_key": "plain-value"} $key custom-secret-value-99 short""")
        assertFalse(text.contains("abc.def-123"))
        assertFalse(text.contains("plain-value"))
        assertFalse(text.contains(key))
        assertFalse(text.contains("custom-secret-value-99"))
        assertTrue("太短的串不当 Key 替换", text.contains("short"))
    }

    @Test
    fun `request detail is redacted and shows the model from the body`() {
        val format = AdvancedLogFormat(listOf(key))
        val entry = LogEntry.RequestLog(
            tag = "HTTP",
            url = "https://api.deepseek.com/v1/chat/completions",
            method = "POST",
            requestHeaders = mapOf("Authorization" to "Bearer $key"),
            requestBody = """{"model": "deepseek-flash", "messages": []}""",
            responseCode = 401,
            durationMs = 12,
        )
        val item = format.item(entry)
        assertEquals("deepseek-flash", item.model)
        assertTrue(item.failed)
        assertEquals("POST https://api.deepseek.com/v1/chat/completions", item.title)
        val detail = format.detail(entry)
        assertFalse(detail.contains(key))
        assertTrue(detail.contains("Authorization: ${AdvancedLogFormat.MASK}"))
        assertTrue(detail.contains("\"deepseek-flash\""))
    }

    @Test
    fun `huge request bodies are truncated in the detail`() {
        val body = "x".repeat(AdvancedLogFormat.MAX_BODY_CHARS + 500)
        val detail = AdvancedLogFormat().detail(
            LogEntry.RequestLog(tag = "HTTP", url = "https://a.example", method = "POST", requestBody = body),
        )
        assertTrue(detail.length < body.length)
        assertTrue(detail.contains("只显示前"))
    }

    @Test
    fun `key status never shows any part of the key`() {
        assertEquals(KeyStatus(), KeyStatus.of(null))
        assertEquals(KeyStatus(), KeyStatus.of("  "))
        assertEquals(KeyStatus(saved = true), KeyStatus.of(key))
        assertEquals(KeyStatus(saved = true), KeyStatus.of("abc"))
        assertEquals("已填写", KeyStatus.of(key).label)
        assertEquals("未填写", KeyStatus.of(null).label)
        assertFalse(KeyStatus.of(key).toString().contains(key.takeLast(4)))
    }

    @Test
    fun `proxy draft builds and parses proxy urls`() {
        assertEquals("", ProxyDraft(enabled = false, host = "127.0.0.1", port = "7890").toProxyUrl())
        assertEquals("http://127.0.0.1:7890", ProxyDraft(enabled = true, host = "127.0.0.1", port = "7890").toProxyUrl())
        assertEquals(
            "socks5://proxy.lan:1080",
            ProxyDraft(enabled = true, type = ProxyType.Socks, host = " proxy.lan ", port = "1080").toProxyUrl(),
        )
        assertEquals("http://[::1]:8080", ProxyDraft(enabled = true, host = "::1", port = "8080").toProxyUrl())
        assertTrue(ProxyDraft(enabled = true, host = "127.0.0.1", port = "70000").incomplete)
        assertTrue(ProxyDraft(enabled = true, host = "http://x", port = "80").incomplete)
        assertTrue(ProxyDraft(enabled = true, host = "", port = "80").incomplete)

        assertEquals(ProxyDraft(), ProxyDraft.from("", "", ""))
        assertEquals(
            ProxyDraft(enabled = true, type = ProxyType.Http, host = "127.0.0.1", port = "7890", username = "u"),
            ProxyDraft.from("127.0.0.1:7890", "u", ""),
        )
        assertEquals(
            ProxyDraft(enabled = true, type = ProxyType.Socks, host = "::1", port = "1080"),
            ProxyDraft.from("socks5://[::1]:1080", "", ""),
        )
        assertFalse("坏地址当作没开", ProxyDraft.from("ftp://x:1", "", "").enabled)
    }

    @Test
    fun `bootstrap keeps the model chosen in advanced settings and falls back to flash only when it is gone`() {
        val flash = Model(modelId = WhaleBootstrap.DEEPSEEK_FLASH_MODEL_ID)
        val chosen = Model(modelId = "deepseek-chosen")
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(id = WhaleBootstrap.DEEPSEEK_PROVIDER_ID, models = listOf(flash, chosen))),
        )
        val whale = Assistant(chatModelId = chosen.id, reasoningLevel = ReasoningLevel.HIGH)
        assertEquals(chosen.id, WhaleBootstrap.whaleChatModelId(whale, flash.id, settings))
        assertEquals("新建助手用 flash", flash.id, WhaleBootstrap.whaleChatModelId(null, flash.id, settings))
        val orphan = whale.copy(chatModelId = Model().id)
        assertEquals("选中的模型被删了退回 flash", flash.id, WhaleBootstrap.whaleChatModelId(orphan, flash.id, settings))
        assertNull(WhaleBootstrap.whaleChatModelId(null, null, settings))
    }
}
