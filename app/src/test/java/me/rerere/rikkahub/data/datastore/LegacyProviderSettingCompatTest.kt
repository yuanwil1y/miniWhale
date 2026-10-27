package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2 删掉了 Claude / Google / ChatGPT / Grok 的协议实现，但设置类型保留：
 * 旧设置里这几类供应商必须照常读入、原样写回（设置用的就是 [JsonInstant]）。
 */
class LegacyProviderSettingCompatTest {

    private fun decode(json: String) = JsonInstant.decodeFromString<List<ProviderSetting>>(json)

    private fun encode(providers: List<ProviderSetting>) =
        JsonInstant.parseToJsonElement(JsonInstant.encodeToString(providers)).jsonArray

    @Test
    fun `legacy provider types are read with every field`() {
        val (deepSeek, google, claude, chatGpt, grok) = decode(LEGACY_PROVIDERS_JSON)

        assertEquals("https://api.deepseek.com", (deepSeek as ProviderSetting.OpenAI).baseUrl)

        google as ProviderSetting.Google
        assertEquals("旧 Gemini", google.name)
        assertEquals("g-key", google.apiKey)
        assertTrue(google.vertexAI && google.useServiceAccount && google.useInteractionsApi)
        assertEquals("-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n", google.privateKey)
        assertEquals("sa@proj.iam.gserviceaccount.com", google.serviceAccountEmail)
        assertEquals("asia-east1", google.location)
        assertEquals("proj", google.projectId)
        assertEquals("gemini-2.5-pro", google.models.single().modelId)

        claude as ProviderSetting.Claude
        assertTrue(claude.promptCaching)
        assertEquals(ClaudePromptCacheTtl.ONE_HOUR, claude.promptCacheTtl)
        assertEquals("x-api-key", claude.quotaQuery.headers.single().name)
        assertEquals("anthropic-beta", claude.customHeaders.single().name)

        chatGpt as ProviderSetting.ChatGPT
        val credential = requireNotNull(chatGpt.credential)
        assertEquals("cg-access", credential.accessToken)
        assertEquals("cg-refresh", credential.refreshToken)
        assertEquals(1_900_000_000_000L, credential.expiresAt)
        assertEquals("acct-1", credential.accountId)
        assertEquals("me@example.com", credential.email)
        assertEquals("plus", credential.plan)
        assertEquals(false, chatGpt.enabled)

        grok as ProviderSetting.Grok
        assertEquals(null, grok.credential)
    }

    @Test
    fun `legacy provider types are written back unchanged`() {
        val original = JsonInstant.parseToJsonElement(LEGACY_PROVIDERS_JSON).jsonArray

        val written = encode(decode(LEGACY_PROVIDERS_JSON))

        assertEquals(original.size, written.size)
        original.zip(written).forEach { (before, after) ->
            assertJsonObjectContains(before.jsonObject, after.jsonObject)
        }
        // 字段写全了的三项（Claude / ChatGPT / Grok）写回后完全一样
        listOf(2, 3, 4).forEach { assertEquals(original[it], written[it]) }
        // 第二轮读写之后完全一样（写出来的就是稳定形态）
        assertEquals(written, encode(decode(written.toString())))
    }

    @Test
    fun `renaming a legacy provider keeps its other fields`() {
        val claude = decode(LEGACY_PROVIDERS_JSON)[2]

        val renamed = claude.copyProvider(name = "改名后的 Claude")

        val before = JsonInstant.parseToJsonElement(LEGACY_PROVIDERS_JSON).jsonArray[2].jsonObject
        val after = encode(listOf(renamed)).single().jsonObject
        assertEquals("改名后的 Claude", after["name"]?.jsonPrimitive?.content)
        assertEquals(JsonObject(before - "name"), JsonObject(after - "name"))
    }

    companion object {
        /**
         * [expected] 里的每个键在 [actual] 里都在，值也一样；对象和数组逐层比较。
         * [actual] 可以多出键：旧 JSON 里省略、读入时取默认值的字段写回时会被补上，这不算改动。
         */
        fun assertJsonObjectContains(expected: JsonObject, actual: JsonObject, path: String = "") {
            expected.forEach { (key, value) ->
                assertJsonContains(value, actual[key], "$path/$key")
            }
        }

        private fun assertJsonContains(expected: JsonElement, actual: JsonElement?, path: String) {
            when (expected) {
                is JsonObject -> {
                    assertTrue("$path should be an object, was $actual", actual is JsonObject)
                    assertJsonObjectContains(expected, actual as JsonObject, path)
                }

                is JsonArray -> {
                    assertTrue("$path should be an array, was $actual", actual is JsonArray)
                    actual as JsonArray
                    assertEquals("$path size", expected.size, actual.size)
                    expected.forEachIndexed { i, e -> assertJsonContains(e, actual[i], "$path[$i]") }
                }

                else -> assertEquals(path, expected, actual)
            }
        }

        /**
         * 旧版本写出的 providers JSON：DeepSeek（OpenAI 兼容）+ 四类已删除实现的供应商。
         * 除 DeepSeek 的模型外都是完整字段（旧版本用 encodeDefaults 写盘，就是这个形态）。
         */
        val LEGACY_PROVIDERS_JSON = """
            [
              {"type":"openai","id":"7d1b0d1e-0000-4000-8000-00000000d5e1","enabled":true,"name":"DeepSeek",
               "models":[],"apiKey":"","baseUrl":"https://api.deepseek.com"},
              {"type":"google","id":"7d1b0d1e-0000-4000-8000-000000000001","enabled":true,"name":"旧 Gemini",
               "models":[{"modelId":"gemini-2.5-pro","displayName":"Gemini 2.5 Pro"}],
               "quotaQuery":{"enabled":false,"url":"{{baseUrl}}/credits",
                 "headers":[{"name":"x-goog-api-key","value":"{{apiKey}}"}],"resultPath":"data.total_usage"},
               "customHeaders":[],"apiKey":"g-key","baseUrl":"https://generativelanguage.googleapis.com/v1beta",
               "vertexAI":true,"useServiceAccount":true,
               "privateKey":"-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n",
               "serviceAccountEmail":"sa@proj.iam.gserviceaccount.com","location":"asia-east1","projectId":"proj",
               "useInteractionsApi":true},
              {"type":"claude","id":"7d1b0d1e-0000-4000-8000-000000000002","enabled":true,"name":"旧 Claude",
               "models":[],
               "quotaQuery":{"enabled":true,"url":"https://billing.example.com/credits",
                 "headers":[{"name":"x-api-key","value":"{{apiKey}}"}],"resultPath":"total"},
               "customHeaders":[{"name":"anthropic-beta","value":"tools-2024"}],
               "apiKey":"c-key","baseUrl":"https://api.anthropic.com/v1","promptCaching":true,"promptCacheTtl":"1h"},
              {"type":"chatgpt","id":"7d1b0d1e-0000-4000-8000-000000000003","enabled":false,"name":"ChatGPT",
               "models":[],"customHeaders":[],
               "credential":{"accessToken":"cg-access","refreshToken":"cg-refresh","expiresAt":1900000000000,
                 "accountId":"acct-1","email":"me@example.com","plan":"plus"}},
              {"type":"grok","id":"7d1b0d1e-0000-4000-8000-000000000004","enabled":true,"name":"Grok",
               "models":[],"customHeaders":[],"credential":null}
            ]
        """.trimIndent()

        val LEGACY_PROVIDER_IDS: List<String> =
            (JsonInstant.parseToJsonElement(LEGACY_PROVIDERS_JSON) as JsonArray)
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }
}
