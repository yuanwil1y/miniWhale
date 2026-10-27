package whaleui.nightsky.ltd.minimax

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * [WhaleMiniMaxClient] 的 MockWebServer 单测。
 *
 * 只用 MockWebServer，绝不访问真实网络；每种请求都断言了 path / method / 请求体关键字段。
 */
class WhaleMiniMaxClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ---------------------------------------------------------------- 语音

    @Test
    fun synthesizeParsesHexAudioAndDuration() {
        enqueueJson(
            """
            {
              "data": {"audio": "494433", "status": 1},
              "extra_info": {"audio_length": 1234},
              "base_resp": {"status_code": 0, "status_msg": "success"}
            }
            """.trimIndent()
        )

        val result = runBlocking {
            newClient().synthesize(
                text = "你好呀",
                voiceId = "female-shaonv",
                speed = 1.2f,
                model = "speech-2.8-turbo",
            )
        }

        assertFalse(result.isFailure)
        val audio = result.getOrThrow()
        assertArrayEquals(byteArrayOf(0x49, 0x44, 0x33), audio.mp3)
        assertEquals(1234L, audio.durationMs)

        val recorded = server.takeRequest()
        assertEquals("/v1/t2a_v2", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals("Bearer $TEST_KEY", recorded.getHeader("Authorization"))

        val body = recordedBodyJson(recorded)
        assertEquals("speech-2.8-turbo", body.getValue("model").jsonPrimitive.content)
        assertEquals("你好呀", body.getValue("text").jsonPrimitive.content)
        assertFalse(body.getValue("stream").jsonPrimitive.boolean)
        assertEquals("hex", body.getValue("output_format").jsonPrimitive.content)

        val voiceSetting = body.getValue("voice_setting").jsonObject
        assertEquals("female-shaonv", voiceSetting.getValue("voice_id").jsonPrimitive.content)
        assertEquals(1.2, voiceSetting.getValue("speed").jsonPrimitive.double, 1e-6)
        assertEquals(1, voiceSetting.getValue("vol").jsonPrimitive.int)
        assertEquals(0, voiceSetting.getValue("pitch").jsonPrimitive.int)

        val audioSetting = body.getValue("audio_setting").jsonObject
        assertEquals("mp3", audioSetting.getValue("format").jsonPrimitive.content)
        assertEquals(32000, audioSetting.getValue("sample_rate").jsonPrimitive.int)
        assertEquals(128000, audioSetting.getValue("bitrate").jsonPrimitive.int)
        assertEquals(1, audioSetting.getValue("channel").jsonPrimitive.int)
    }

    @Test
    fun synthesizeUsesDefaultSpeechModel() {
        enqueueJson(
            """
            {
              "data": {"audio": "FF"},
              "extra_info": {"audio_length": 10},
              "base_resp": {"status_code": 0, "status_msg": "success"}
            }
            """.trimIndent()
        )

        val result = runBlocking { newClient().synthesize("hi", "voice-1", 1.0f) }

        assertTrue(result.getOrThrow().mp3.contentEquals(byteArrayOf(0xFF.toByte())))
        val body = recordedBodyJson(server.takeRequest())
        assertEquals("speech-2.8-hd", body.getValue("model").jsonPrimitive.content)
    }

    @Test
    fun synthesizeFailsOnBusinessErrorCodes() {
        val cases = listOf(
            1004 to "鉴权失败",
            1008 to "余额不足",
            1026 to "敏感信息",
            2013 to "参数错误",
        )
        cases.forEach { (code, expected) ->
            server.enqueue(businessErrorResponse(code, "mock-error-$code"))

            val result = runBlocking { newClient().synthesize("hello", "voice-1", 1.0f) }

            assertTrue("code=$code 应该是失败", result.isFailure)
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue("code=$code 消息里要有中文原因：$message", message.contains(expected))
            assertTrue("code=$code 消息里要带上 status_msg：$message", message.contains("mock-error-$code"))
        }
    }

    @Test
    fun synthesizeFailsOnUnknownBusinessErrorCode() {
        enqueueJson(businessErrorBody(2049, "weird-behavior"))

        val result = runBlocking { newClient().synthesize("hello", "voice-1", 1.0f) }

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("message=$message", message.contains("MiniMax 返回错误 2049"))
        assertTrue("message=$message", message.contains("weird-behavior"))
    }

    @Test
    fun synthesizeFailsOnHttpError() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val result500 = runBlocking { newClient().synthesize("hello", "voice-1", 1.0f) }
        assertTrue(result500.isFailure)
        assertTrue(result500.exceptionOrNull()?.message.orEmpty().contains("HTTP 500"))

        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        val result401 = runBlocking { newClient().synthesize("hello", "voice-1", 1.0f) }
        assertTrue(result401.isFailure)
        assertTrue(result401.exceptionOrNull()?.message.orEmpty().contains("HTTP 401"))
    }

    @Test
    fun synthesizeFailsOnTimeout() {
        val shortTimeoutClient = OkHttpClient.Builder()
            .connectTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .writeTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val startedAt = System.currentTimeMillis()
        val result = runBlocking { newClient(httpClient = shortTimeoutClient).synthesize("hello", "voice-1", 1.0f) }
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue(result.isFailure)
        assertTrue("超时不应超过 5 秒，实际 ${elapsed}ms", elapsed < 5_000)
        assertTrue("超时要包成 IOException：${result.exceptionOrNull()}", result.exceptionOrNull() is IOException)
    }

    @Test
    fun synthesizeFailsOnInvalidHex() {
        enqueueJson(
            """{"data": {"audio": "zz11"}, "base_resp": {"status_code": 0, "status_msg": "success"}}"""
        )
        val badChars = runBlocking { newClient().synthesize("hello", "voice-1", 1.0f) }
        assertTrue(badChars.isFailure)
        assertTrue(badChars.exceptionOrNull()?.message.orEmpty().contains("hex"))

        enqueueJson(
            """{"data": {"audio": "abc"}, "base_resp": {"status_code": 0, "status_msg": "success"}}"""
        )
        val oddLength = runBlocking { newClient().synthesize("hello", "voice-1", 1.0f) }
        assertTrue(oddLength.isFailure)
        assertTrue(oddLength.exceptionOrNull()?.message.orEmpty().contains("hex"))
    }

    @Test
    fun synthesizeFailsWhenAudioIsMissing() {
        enqueueJson("""{"data": {}, "base_resp": {"status_code": 0, "status_msg": "success"}}""")

        val result = runBlocking { newClient().synthesize("hello", "voice-1", 1.0f) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("没有返回语音数据"))
    }

    @Test
    fun synthesizeFailsOnNonJsonBody() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>gateway</html>"))

        val result = runBlocking { newClient().synthesize("hello", "voice-1", 1.0f) }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("JSON"))
    }

    // ---------------------------------------------------------------- 作图

    @Test
    fun generateImageDecodesBase64() {
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val base64 = Base64.getEncoder().encodeToString(pngBytes)
        enqueueJson(
            """
            {
              "data": {"image_base64": ["$base64"]},
              "metadata": {"success_count": 1, "failed_count": 0},
              "base_resp": {"status_code": 0, "status_msg": "success"}
            }
            """.trimIndent()
        )

        val result = runBlocking { newClient().generateImage(prompt = "一只鲸鱼", aspectRatio = "16:9") }

        assertTrue(result.isSuccess)
        assertArrayEquals(pngBytes, result.getOrThrow().png)

        val recorded = server.takeRequest()
        assertEquals("/v1/image_generation", recorded.path)
        assertEquals("POST", recorded.method)
        val body = recordedBodyJson(recorded)
        assertEquals("image-01", body.getValue("model").jsonPrimitive.content)
        assertEquals("一只鲸鱼", body.getValue("prompt").jsonPrimitive.content)
        assertEquals("16:9", body.getValue("aspect_ratio").jsonPrimitive.content)
        assertEquals("base64", body.getValue("response_format").jsonPrimitive.content)
        assertEquals(1, body.getValue("n").jsonPrimitive.int)
    }

    @Test
    fun generateImageFailsOnInvalidBase64() {
        enqueueJson(
            """{"data": {"image_base64": ["!!!not-base64!!!"]}, "base_resp": {"status_code": 0, "status_msg": "success"}}"""
        )

        val result = runBlocking { newClient().generateImage("一只鲸鱼", "1:1") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("base64"))
    }

    @Test
    fun generateImageFailsWhenImageIsMissing() {
        enqueueJson(
            """{"data": {"image_base64": []}, "base_resp": {"status_code": 0, "status_msg": "success"}}"""
        )

        val result = runBlocking { newClient().generateImage("一只鲸鱼", "1:1") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("没有返回图片数据"))
    }

    // ---------------------------------------------------------------- 音色

    @Test
    fun listVoicesParsesSystemVoice() {
        enqueueJson(
            """
            {
              "system_voice": [
                {"voice_id": "male-qn-qingse", "voice_name": "青涩青年音色", "description": ["中文"]},
                {"voice_id": "female-shaonv", "voice_name": "少女音色"}
              ],
              "base_resp": {"status_code": 0, "status_msg": "success"}
            }
            """.trimIndent()
        )

        val result = runBlocking { newClient().listVoices() }

        assertTrue(result.isSuccess)
        val voices = result.getOrThrow()
        assertEquals(2, voices.size)
        assertEquals("male-qn-qingse", voices[0].id)
        assertEquals("青涩青年音色", voices[0].name)
        assertEquals("中文", voices[0].description)
        assertEquals("female-shaonv", voices[1].id)
        assertEquals("少女音色", voices[1].name)

        val recorded = server.takeRequest()
        assertEquals("/v1/get_voice", recorded.path)
        assertEquals("system", recordedBodyJson(recorded).getValue("voice_type").jsonPrimitive.content)
    }

    @Test
    fun listVoicesAcceptsAlternativeFieldNames() {
        enqueueJson("""{"voice_list": [{"voiceId": "v1", "voiceName": "一号"}]}""")
        val listVariant = runBlocking { newClient().listVoices() }
        assertEquals(1, listVariant.getOrThrow().size)
        assertEquals("v1", listVariant.getOrThrow()[0].id)
        assertEquals("一号", listVariant.getOrThrow()[0].name)

        enqueueJson("""{"data": {"voices": [{"id": "v2", "name": "二号"}]}}""")
        val dataVariant = runBlocking { newClient().listVoices() }
        assertEquals("v2", dataVariant.getOrThrow().single().id)
        assertEquals("二号", dataVariant.getOrThrow().single().name)
    }

    @Test
    fun listVoicesReturnsEmptyListOnUnparsableBody() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>oops</html>"))

        val result = runBlocking { newClient().listVoices() }

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun listVoicesStillFailsOnBusinessError() {
        server.enqueue(businessErrorResponse(1008, "no-balance"))

        val result = runBlocking { newClient().listVoices() }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("余额不足"))
    }

    // ---------------------------------------------------------------- 额度

    @Test
    fun queryQuotaUsesTokenPlanForNormalKey() {
        enqueueJson(
            """
            {
              "model_remains": [
                {
                  "model_name": "speech-2.8-hd",
                  "current_interval_total_count": 100,
                  "current_interval_usage_count": 20,
                  "current_interval_remaining_percent": 80,
                  "end_time": 1762000000000
                },
                {
                  "model_name": "image-01",
                  "current_interval_total_count": 10,
                  "current_interval_usage_count": 1,
                  "current_interval_remaining_percent": 90,
                  "end_time": 1762000000000
                },
                {
                  "model_name": "video-01",
                  "current_interval_total_count": 5,
                  "current_interval_usage_count": 5,
                  "current_interval_remaining_percent": 0
                }
              ],
              "base_resp": {"status_code": 0, "status_msg": "success"}
            }
            """.trimIndent()
        )

        val result = runBlocking { newClient(apiKey = "sk-normal-key").queryQuota() }

        assertTrue(result.isSuccess)
        val text = result.getOrThrow()
        assertTrue("text=$text", text.contains("speech-2.8-hd"))
        assertTrue("text=$text", text.contains("剩余 80%"))
        assertTrue("text=$text", text.contains("已用 20/100"))
        assertTrue("text=$text", text.contains("image-01"))
        assertTrue("text=$text", text.contains("剩余 90%"))
        assertFalse("语音/图片以外的条目不该出现：text=$text", text.contains("video-01"))

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/token_plan/remains", recorded.path)
        assertEquals("Bearer sk-normal-key", recorded.getHeader("Authorization"))
    }

    @Test
    fun queryQuotaUsesBalanceForPayAsYouGoKey() {
        enqueueJson("""{"balance": 12.34, "base_resp": {"status_code": 0, "status_msg": "success"}}""")

        val result = runBlocking { newClient(apiKey = "sk-api-abcdef").queryQuota() }

        assertTrue(result.isSuccess)
        assertTrue("text=${result.getOrThrow()}", result.getOrThrow().contains("12.34"))

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/account/query_balance", recorded.path)
        assertEquals("Bearer sk-api-abcdef", recorded.getHeader("Authorization"))
    }

    @Test
    fun queryQuotaFailsOnBusinessError() {
        server.enqueue(businessErrorResponse(1008, "no-balance"))

        val result = runBlocking { newClient(apiKey = "sk-normal-key").queryQuota() }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("余额不足"))
    }

    @Test
    fun queryQuotaFailsOnHttpError() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = runBlocking { newClient(apiKey = "sk-normal-key").queryQuota() }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("HTTP 500"))
    }

    // ---------------------------------------------------------------- 无 Key

    @Test
    fun allMethodsFailWithoutApiKey() {
        val client = newClient(apiKey = null)

        val failures = listOf(
            runBlocking { client.synthesize("hello", "voice-1", 1.0f) }.exceptionOrNull(),
            runBlocking { client.generateImage("一只鲸鱼", "1:1") }.exceptionOrNull(),
            runBlocking { client.listVoices() }.exceptionOrNull(),
            runBlocking { client.queryQuota() }.exceptionOrNull(),
        )

        failures.forEach { error ->
            assertTrue("应该是 IllegalStateException：$error", error is IllegalStateException)
            assertEquals(WhaleMiniMaxClient.NO_API_KEY_MESSAGE, error?.message)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun blankApiKeyIsTreatedAsMissing() {
        listOf("", "   ").forEach { blankKey ->
            val result = runBlocking { newClient(apiKey = blankKey).synthesize("hello", "voice-1", 1.0f) }
            assertTrue(result.isFailure)
            assertEquals(WhaleMiniMaxClient.NO_API_KEY_MESSAGE, result.exceptionOrNull()?.message)
        }
        assertEquals(0, server.requestCount)
    }

    // ---------------------------------------------------------------- 工具

    private fun newClient(
        apiKey: String? = TEST_KEY,
        httpClient: OkHttpClient = OkHttpClient(),
    ): WhaleMiniMaxClient = WhaleMiniMaxClient(
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKeyProvider = { apiKey },
        okHttpClient = httpClient,
    )

    private fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body)
        )
    }

    private fun recordedBodyJson(recorded: RecordedRequest): JsonObject =
        Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject

    private fun businessErrorBody(code: Int, statusMsg: String): String =
        """
        {
          "data": {},
          "base_resp": {"status_code": $code, "status_msg": "$statusMsg"}
        }
        """.trimIndent()

    private fun businessErrorResponse(code: Int, statusMsg: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(businessErrorBody(code, statusMsg))

    private companion object {
        const val TEST_KEY = "test-key"
    }
}
