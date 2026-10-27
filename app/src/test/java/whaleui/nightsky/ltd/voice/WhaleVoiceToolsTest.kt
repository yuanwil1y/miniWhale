package whaleui.nightsky.ltd.voice

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import whaleui.nightsky.ltd.minimax.MiniMaxException.Kind as MiniMaxKind
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import whaleui.nightsky.ltd.minimax.MiniMaxAudio
import whaleui.nightsky.ltd.minimax.MiniMaxImage
import whaleui.nightsky.ltd.minimax.WhaleMiniMaxClient
import java.io.File

/**
 * [WhaleVoiceTools] 的单测：注入假合成器 / 假作图器，验证部件结构、落盘、metadata、schema。
 *
 * 全部用例都不发真实网络请求、不用真实 Key；额度工具的成功路径也用 MockWebServer。
 */
class WhaleVoiceToolsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File

    private var capturedSynthText: String? = null
    private var capturedPrompt: String? = null
    private var capturedRatio: String? = null

    private val fakeSynthesize: (suspend (String) -> MiniMaxAudio) = { text ->
        capturedSynthText = text
        MiniMaxAudio(mp3 = FAKE_MP3, durationMs = FAKE_DURATION_MS)
    }

    private val fakeDraw: (suspend (String, String, String?) -> MiniMaxImage) = { prompt, ratio, _ ->
        capturedPrompt = prompt
        capturedRatio = ratio
        MiniMaxImage(png = FAKE_PNG)
    }

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        capturedSynthText = null
        capturedPrompt = null
        capturedRatio = null
    }

    // ---------------------------------------------------------------- 工具装配

    @Test
    fun createReturnsThreeToolsWithExpectedSchemas() {
        val tools = voiceTools().create()

        assertEquals(listOf("text_to_speech", "draw", "check_voice_quota"), tools.map { it.name })

        val textSchema = tools[0].parameters() as InputSchema.Obj
        assertEquals(setOf("text"), textSchema.properties.keys)
        assertEquals(listOf("text"), textSchema.required)
        assertEquals(
            "string",
            textSchema.properties.getValue("text").jsonObject.getValue("type").jsonPrimitive.content,
        )

        // 19 个语气标签都写进了 text_to_speech 的描述（提示词第 4 批）
        listOf("(laughs)", "(chuckle)", "(clear-throat)", "(lip-smacking)", "(emm)", "(sneezes)").forEach {
            assertTrue(it, tools[0].description.contains(it))
        }
        assertTrue(tools[0].description.contains("直接发给对方"))

        val draw = tools[1]
        val drawSchema = draw.parameters() as InputSchema.Obj
        // 逐字照抄 Hermes DRAW_SCHEMA
        assertEquals("画一张图。用文字描述想画的内容和风格，画好后图片会直接发给对方。画的是你自己时把 self 填 true，会按你的头像保持形象。", draw.description)
        assertEquals(setOf("prompt", "aspect_ratio", "self"), drawSchema.properties.keys)
        assertEquals(
            "画的是你自己时填 true",
            drawSchema.properties.getValue("self").jsonObject.getValue("description").jsonPrimitive.content,
        )
        assertEquals("string", drawSchema.properties.getValue("prompt").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            "想画什么，越具体越好，不超过 500 字",
            drawSchema.properties.getValue("prompt").jsonObject.getValue("description").jsonPrimitive.content,
        )
        assertEquals(
            "画面比例，比如 1:1、9:16、16:9，默认 1:1",
            drawSchema.properties.getValue("aspect_ratio").jsonObject.getValue("description").jsonPrimitive.content,
        )
        assertEquals(listOf("prompt"), drawSchema.required)

        val quota = tools[2]
        // 逐字照抄 Hermes QUOTA_SCHEMA
        assertEquals("看看语音和画画的额度还剩多少。", quota.description)
        val quotaSchema = quota.parameters() as InputSchema.Obj
        assertTrue(quotaSchema.properties.isEmpty())
        assertNull(quotaSchema.required)
    }

    // ---------------------------------------------------------------- 语音

    @Test
    fun textToSpeechReturnsSentPayloadAndAudioPart() {
        val parts = call(tool("text_to_speech"), """{"text":"(sighs) 行吧 等你"}""")

        assertEquals(2, parts.size)
        assertEquals("""{"sent":true}""", (parts[0] as UIMessagePart.Text).text)

        val audio = parts[1] as UIMessagePart.Audio
        assertTrue("url=${audio.url}", audio.url.startsWith("file://"))
        val file = File(audio.url.removePrefix("file://"))
        assertTrue("音频文件应该真的落盘：${file.absolutePath}", file.isFile)
        assertArrayEquals(FAKE_MP3, file.readBytes())

        val metadata = audio.metadata!!
        assertEquals("行吧 等你", metadata.getValue("text").jsonPrimitive.content)
        assertEquals(FAKE_DURATION_MS, metadata.getValue("durationMs").jsonPrimitive.content.toLong())
        val waveform = metadata.getValue("waveform").jsonArray
        assertTrue("waveform 柱数=${waveform.size}", waveform.size in 1..64)
        waveform.forEach { bar ->
            val value = bar.jsonPrimitive.content.toFloat()
            assertTrue("bar=$value", value in 0.06f..0.96f)
        }

        // 情绪标签原样交给 MiniMax（只有 metadata 里的展示文字才剥标签）
        assertEquals("(sighs) 行吧 等你", capturedSynthText)
    }

    @Test
    fun textToSpeechWritesANewFileEachTime() {
        val textToSpeech = tool("text_to_speech")

        call(textToSpeech, """{"text":"第一句"}""")
        call(textToSpeech, """{"text":"第二句"}""")

        val directory = File(File(filesDir, "whale"), "voice")
        val files = directory.listFiles()?.filter { it.extension == "mp3" }.orEmpty()
        assertEquals(2, files.size)
    }

    @Test
    fun textToSpeechRejectsBlankText() {
        assertTrue(errorMessage(call(tool("text_to_speech"), """{"text":"   "}""")).contains("text 不能为空"))
        assertTrue(errorMessage(call(tool("text_to_speech"), "{}")).contains("text 不能为空"))
    }

    @Test
    fun textToSpeechFallsBackToClientAndReportsMissingKey() {
        val tools = voiceTools(synthesize = null)

        val message = errorMessage(call(tool("text_to_speech", tools), """{"text":"你好"}"""))

        assertTrue(message, message.contains("还没设置 MiniMax 的 Key，这要对方去设置里处理"))
    }

    @Test
    fun textToSpeechTurnsSynthExceptionIntoErrorJson() {
        val tools = voiceTools(synthesize = { throw IllegalStateException("合成炸了") })

        val message = errorMessage(call(tool("text_to_speech", tools), """{"text":"你好"}"""))

        // 异常原文不交给她，只说「暂时不行」（提示词第 10 批方案 A）
        assertEquals("语音暂时发不了，等会儿再试，先打字", message)
    }

    @Test
    fun miniMaxFailuresAreToldFromHerSide() {
        fun messageFor(kind: whaleui.nightsky.ltd.minimax.MiniMaxException.Kind): String {
            val tools = voiceTools(synthesize = {
                throw whaleui.nightsky.ltd.minimax.MiniMaxException("MiniMax 余额不足，请充值后再试", kind = kind)
            })
            return errorMessage(call(tool("text_to_speech", tools), """{"text":"你好"}"""))
        }
        assertEquals("语音暂时发不了：服务那边余额不足，这要对方去设置里处理", messageFor(MiniMaxKind.NoBalance))
        assertEquals("语音暂时发不了：MiniMax 的 Key 不对，这要对方去设置里处理", messageFor(MiniMaxKind.Auth))
        assertEquals("语音暂时发不了，等会儿再试，先打字", messageFor(MiniMaxKind.Temporary))
        assertTrue(messageFor(MiniMaxKind.Rejected).contains("换个说法"))
        // 写给设置页的口气不交给她
        MiniMaxKind.entries.forEach { assertFalse(messageFor(it).contains("请充值")) }
    }

    // ---------------------------------------------------------------- 作图

    @Test
    fun drawReturnsSuccessTextAndImagePart() {
        val parts = call(tool("draw"), """{"prompt":"一只鲸鱼","aspect_ratio":"9:16"}""")

        assertEquals(2, parts.size)
        assertEquals("画好了，已经发给对方了", (parts[0] as UIMessagePart.Text).text)

        val image = parts[1] as UIMessagePart.Image
        assertTrue(image.url.startsWith("file://"))
        val file = File(image.url.removePrefix("file://"))
        assertTrue("图片文件应该真的落盘：${file.absolutePath}", file.isFile)
        assertArrayEquals(FAKE_PNG, file.readBytes())
        assertEquals("一只鲸鱼", image.metadata!!.getValue("prompt").jsonPrimitive.content)

        assertEquals("一只鲸鱼", capturedPrompt)
        assertEquals("9:16", capturedRatio)
    }

    @Test
    fun drawFallsBackToSquareOnInvalidRatio() {
        val draw = tool("draw")

        call(draw, """{"prompt":"猫","aspect_ratio":"5:4"}""")
        assertEquals("1:1", capturedRatio)

        call(draw, """{"prompt":"猫"}""")
        assertEquals("1:1", capturedRatio)

        call(draw, """{"prompt":"猫","aspect_ratio":"  "}""")
        assertEquals("1:1", capturedRatio)
    }

    @Test
    fun drawTruncatesPromptTo500Chars() {
        val longPrompt = "画".repeat(600)

        val parts = call(tool("draw"), """{"prompt":"$longPrompt"}""")

        assertEquals(500, capturedPrompt!!.length)
        val image = parts[1] as UIMessagePart.Image
        assertEquals(500, image.metadata!!.getValue("prompt").jsonPrimitive.content.length)
    }

    @Test
    fun drawRejectsBlankPrompt() {
        assertTrue(errorMessage(call(tool("draw"), """{"prompt":"  "}""")).contains("prompt 不能为空"))
        assertTrue(errorMessage(call(tool("draw"), "{}")).contains("prompt 不能为空"))
    }

    @Test
    fun drawTurnsImageExceptionIntoErrorJson() {
        val tools = voiceTools(drawImage = { _, _, _ -> throw IllegalStateException("画图炸了") })

        val message = errorMessage(call(tool("draw", tools), """{"prompt":"猫"}"""))

        assertEquals("画画暂时用不了，等会儿再试", message)
    }

    @Test
    fun drawFallsBackToClientAndReportsMissingKey() {
        val tools = voiceTools(drawImage = null)

        val message = errorMessage(call(tool("draw", tools), """{"prompt":"猫"}"""))

        assertTrue(message, message.contains("还没设置 MiniMax 的 Key，这要对方去设置里处理"))
    }

    // ---------------------------------------------------------------- 额度

    @Test
    fun quotaReturnsClientTextOnSuccess() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "model_remains": [
                            {"model_name": "speech-2.8-hd", "current_interval_remaining_percent": 72}
                          ],
                          "base_resp": {"status_code": 0, "status_msg": "success"}
                        }
                        """.trimIndent()
                    )
            )
            val client = WhaleMiniMaxClient(
                baseUrl = server.url("/").toString().trimEnd('/'),
                apiKeyProvider = { "test-key" },
                okHttpClient = OkHttpClient(),
            )

            val parts = call(tool("check_voice_quota", voiceTools(client = client)), "{}")

            val text = (parts.single() as UIMessagePart.Text).text
            assertTrue(text, text.contains("MiniMax 额度"))
            assertTrue(text, text.contains("speech-2.8-hd"))
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/token_plan/remains", request.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun quotaReturnsErrorJsonWithoutKey() {
        val message = errorMessage(call(tool("check_voice_quota"), "{}"))

        assertTrue(message, message.contains("还没设置 MiniMax 的 Key，这要对方去设置里处理"))
    }

    // ---------------------------------------------------------------- 标签剥离

    @Test
    fun stripEmotionTagsRemovesAllNineteenTags() {
        val tags = listOf(
            "laughs", "chuckle", "coughs", "clear-throat", "groans",
            "breath", "pant", "inhale", "exhale", "gasps",
            "sniffs", "sighs", "snorts", "burps", "lip-smacking",
            "humming", "hissing", "emm", "sneezes",
        )

        assertEquals(19, tags.size)
        tags.forEach { tag ->
            assertEquals("tag=$tag", "你好", stripEmotionTags("($tag) 你好"))
        }
    }

    @Test
    fun stripEmotionTagsHandlesCommonCases() {
        assertEquals("行吧 等你", stripEmotionTags("(sighs) 行吧 等你"))
        assertEquals("你回来啦", stripEmotionTags("(breath) 你回来啦"))
        assertEquals("消息", stripEmotionTags("消息 (laughs)"))
        assertEquals("", stripEmotionTags("(gasps)"))
        assertEquals("", stripEmotionTags("  (sniffs)  "))
    }

    @Test
    fun stripEmotionTagsHandlesConsecutiveNestedAndCase() {
        assertEquals("好", stripEmotionTags("(laughs)(sighs) 好"))
        assertEquals("好", stripEmotionTags("(laughs) (breath) 好"))
        assertEquals("好", stripEmotionTags("((laughs)) 好"))
        assertEquals("好", stripEmotionTags("( (sighs) ) 好"))
        assertEquals("好", stripEmotionTags("(Laughs) 好"))
        assertEquals("好", stripEmotionTags("（sighs）好"))
        assertEquals("好", stripEmotionTags("( sighs ) 好"))
        assertEquals("a b", stripEmotionTags("a (laughs)  b"))
    }

    @Test
    fun stripEmotionTagsLeavesOrdinaryTextAlone() {
        assertEquals("今天天气不错", stripEmotionTags("今天天气不错"))
        // 中文括号里的动作/状态不是 MiniMax 标签，不能动
        assertEquals("今天天气不错（真的）(困)", stripEmotionTags("今天天气不错（真的）(困)"))
        assertEquals("(真的) 好", stripEmotionTags("(真的) 好"))
    }

    // ---------------------------------------------------------------- 工具函数

    private fun noKeyClient(): WhaleMiniMaxClient = WhaleMiniMaxClient(
        baseUrl = "http://127.0.0.1:1",
        apiKeyProvider = { null },
        okHttpClient = OkHttpClient(),
    )

    private fun voiceTools(
        client: WhaleMiniMaxClient = noKeyClient(),
        synthesize: (suspend (String) -> MiniMaxAudio)? = fakeSynthesize,
        drawImage: (suspend (String, String, String?) -> MiniMaxImage)? = fakeDraw,
    ): WhaleVoiceTools = WhaleVoiceTools(
        client = client,
        filesDir = filesDir,
        synthesize = synthesize,
        drawImage = drawImage,
    )

    private fun tool(name: String, tools: WhaleVoiceTools = voiceTools()): Tool =
        tools.create().first { it.name == name }

    private fun call(tool: Tool, args: String = "{}"): List<UIMessagePart> =
        runBlocking { tool.execute(Json.parseToJsonElement(args)) }

    private fun errorMessage(parts: List<UIMessagePart>): String {
        val text = (parts.single() as UIMessagePart.Text).text
        return Json.parseToJsonElement(text).jsonObject.getValue("error").jsonPrimitive.content
    }

    private companion object {
        val FAKE_MP3 = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x7F, 0x22)
        val FAKE_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        const val FAKE_DURATION_MS = 2_345L
    }
}
