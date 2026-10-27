package whaleui.nightsky.ltd.voice

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import whaleui.nightsky.ltd.minimax.MiniMaxAudio
import whaleui.nightsky.ltd.minimax.MiniMaxException
import whaleui.nightsky.ltd.tools.WhaleToolErrors
import whaleui.nightsky.ltd.minimax.MiniMaxImage
import whaleui.nightsky.ltd.minimax.WhaleMiniMaxClient
import java.io.File

/**
 * 小鲸鱼的三个 MiniMax 工具：`text_to_speech`、`draw`、`check_voice_quota`（任务书 7.2–7.4）。
 *
 * 通过 RikkaHub 原生 [Tool] 扩展点接进去（`ChatToolFactory` 里仅当助手是小鲸鱼时加入）。
 *
 * 设计要点：
 * - 合成器 / 作图器可注入：[synthesize]、[drawImage] 为 null 时走 [WhaleMiniMaxClient]；
 *   传入假实现就能在没有 Key、没有网络的情况下验证"落盘 + 部件结构"（任务书 7.7 的检查点 H）。
 * - 语音/图片真的落盘到 App 私有目录 `filesDir/whale/voice|drawings/`，部件里放 `file://` URL；
 *   `text_to_speech` 返回 **两个** 部件（`{"sent":true}` 文本 + Audio），切片后会变成一条独立语音气泡。
 * - 失败时一律返回 `{"error":"<中文原因>"}` 文本部件，不让异常穿透到生成流程。
 * - 音色 / 语速从 [voiceIdProvider]、[speedProvider] 现取（设置页以后接这两个 lambda，默认值抄原生 MiniMax 设置）。
 *
 * @param client MiniMax 客户端；注入 [synthesize] / [drawImage] 时只在额度工具里用到。
 * @param filesDir App 私有目录根（`context.filesDir`，测试里传临时目录）。
 * @param synthesize 可注入的语音合成器；null 时用 [client]。
 * @param drawImage 可注入的作图器；null 时用 [client]。
 * @param voiceIdProvider 设置页音色 id，默认 `female-shaonv`（原生 `TTSProviderSetting.MiniMax` 的默认值）。
 * @param speedProvider 设置页语速，默认 1.0f。
 */
class WhaleVoiceTools(
    private val client: WhaleMiniMaxClient,
    private val filesDir: File,
    private val synthesize: (suspend (text: String) -> MiniMaxAudio)? = null,
    private val drawImage: (suspend (prompt: String, ratio: String, subjectReference: String?) -> MiniMaxImage)? = null,
    /** 她自己的头像 data URI（画她自己时当角色参考图）；读不到就返回 null（那就只当标记，不带参考图）。 */
    private val avatarDataUriProvider: suspend () -> String? = { null },
    private val voiceIdProvider: () -> String = { DEFAULT_VOICE_ID },
    private val speedProvider: () -> Float = { DEFAULT_SPEED },
) {

    /** 三个工具，顺序：发语音、画画、查额度。 */
    fun create(): List<Tool> = listOf(textToSpeechTool(), drawTool(), quotaTool())

    // ------------------------------------------------------------ text_to_speech

    private fun textToSpeechTool() = Tool(
        name = "text_to_speech",
        description = "发一条语音消息。text 是要说出口的话，用平常聊天的语气，一两句就好，语音会直接发给对方。" +
            "可以在话里加语气标签，会变成真的声音：" + EMOTION_TAGS.joinToString("、") { "($it)" } + "。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("description", "要说出口的话，用平常聊天的语气，一两句就好")
                    })
                },
                required = listOf("text"),
            )
        },
        execute = { args -> safely { textToSpeech(args) } },
    )

    private suspend fun textToSpeech(args: JsonElement): List<UIMessagePart> {
        val payload = args as? JsonObject ?: return errorParts("参数格式不对")
        val text = payload.stringOrNull("text")?.trim().orEmpty()
        if (text.isEmpty()) return errorParts("text 不能为空")

        val audio = capture {
            synthesize?.invoke(text)
                ?: client.synthesize(text, voiceIdProvider(), speedProvider()).getOrThrow()
        }.getOrElse { error ->
            return errorParts(herReason(error, WhaleToolErrors.Voice))
        }

        val file = capture {
            val directory = File(File(filesDir, "whale"), "voice").apply { mkdirs() }
            newFile(directory, "mp3").also { it.writeBytes(audio.mp3) }
        }.getOrElse {
            return errorParts(WhaleToolErrors.Voice.temporary())
        }

        // fromMp3 自己会把解码失败兜成平直波形；这里只是再保险一层。
        val waveform = runCatching { Waveform.fromMp3(file) }.getOrElse { Waveform.flat() }

        val metadata = buildJsonObject {
            put("text", stripEmotionTags(text))
            put("durationMs", audio.durationMs)
            put("waveform", buildJsonArray {
                waveform.forEach { add(JsonPrimitive(it)) }
            })
        }

        return listOf(
            UIMessagePart.Text(SENT_PAYLOAD),
            UIMessagePart.Audio(url = fileUrl(file), metadata = metadata),
        )
    }

    // ------------------------------------------------------------ draw

    private fun drawTool() = Tool(
        name = "draw",
        description = "画一张图。用文字描述想画的内容和风格，画好后图片会直接发给对方。" +
            "画的是你自己时把 self 填 true，会按你的头像保持形象。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "想画什么，越具体越好，不超过 500 字")
                    })
                    put("aspect_ratio", buildJsonObject {
                        put("type", "string")
                        put("description", "画面比例，比如 1:1、9:16、16:9，默认 1:1")
                    })
                    put("self", buildJsonObject {
                        put("type", "boolean")
                        put("description", "画的是你自己时填 true")
                    })
                },
                required = listOf("prompt"),
            )
        },
        execute = { args -> safely { draw(args) } },
    )

    private suspend fun draw(args: JsonElement): List<UIMessagePart> {
        val payload = args as? JsonObject ?: return errorParts("参数格式不对")
        val prompt = payload.stringOrNull("prompt")?.trim()?.take(MAX_PROMPT_CHARS).orEmpty()
        if (prompt.isEmpty()) return errorParts("prompt 不能为空")

        // 白名单之外的（含空值）一律回落到 1:1，跟 Hermes 的 draw() 一致。
        val ratio = payload.stringOrNull("aspect_ratio")
            ?.trim()
            ?.takeIf { it in ASPECT_RATIOS }
            ?: DEFAULT_ASPECT_RATIO

        // 决定 7：只有她画自己的时候才带角色参考图
        val wantSelf = (payload["self"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim() == "true"
        val reference = if (wantSelf) avatarDataUriProvider() else null

        val image = capture {
            drawImage?.invoke(prompt, ratio, reference)
                ?: client.generateImage(prompt, ratio, subjectReferenceDataUri = reference).getOrThrow()
        }.getOrElse { error ->
            return errorParts(herReason(error, WhaleToolErrors.Draw))
        }

        val file = capture {
            val directory = File(File(filesDir, "whale"), "drawings").apply { mkdirs() }
            newFile(directory, "png").also { it.writeBytes(image.png) }
        }.getOrElse {
            return errorParts(WhaleToolErrors.Draw.temporary())
        }

        val metadata = buildJsonObject { put("prompt", prompt) }
        return listOf(
            UIMessagePart.Text(DRAW_SUCCESS_TEXT),
            UIMessagePart.Image(url = fileUrl(file), metadata = metadata),
        )
    }

    // ------------------------------------------------------------ check_voice_quota

    private fun quotaTool() = Tool(
        name = "check_voice_quota",
        description = "看看语音和画画的额度还剩多少。",
        parameters = {
            InputSchema.Obj(properties = JsonObject(emptyMap()))
        },
        execute = { args -> safely { checkVoiceQuota(args) } },
    )

    private suspend fun checkVoiceQuota(@Suppress("UNUSED_PARAMETER") args: JsonElement): List<UIMessagePart> {
        val result = client.queryQuota()
        return result.fold(
            onSuccess = { quota -> listOf(UIMessagePart.Text(quota)) },
            onFailure = { error -> errorParts(herReason(error, WhaleToolErrors.Quota)) },
        )
    }

    /**
     * 客户端的报错是写给设置页的人看的（「请充值后再试」），交给她之前换成她视角的实情（提示词第 10 批方案 A）。
     */
    private fun herReason(error: Throwable, errors: WhaleToolErrors): String = when {
        error.message == WhaleMiniMaxClient.NO_API_KEY_MESSAGE -> errors.noKey("MiniMax")
        error is MiniMaxException -> when (error.kind) {
            MiniMaxException.Kind.Auth -> errors.badKey("MiniMax")
            MiniMaxException.Kind.NoBalance -> errors.noBalance()
            MiniMaxException.Kind.Rejected -> REJECTED_TEXT
            MiniMaxException.Kind.Temporary, MiniMaxException.Kind.Other -> errors.temporary()
        }
        else -> errors.temporary()
    }

    // ------------------------------------------------------------ 工具函数

    /** 工具执行的最外层兜底：除了协程取消，任何异常都变成中文错误 JSON。 */
    private suspend fun safely(block: suspend () -> List<UIMessagePart>): List<UIMessagePart> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // 异常原文（类名、堆栈信息）不交给她
        errorParts(null)
    }

    /** 可注入实现抛异常时也一样兜住；协程取消要原样抛出。 */
    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private fun errorParts(reason: String?): List<UIMessagePart> {
        val message = reason?.trim()?.takeIf { it.isNotEmpty() } ?: "出错了，等会儿再试"
        return listOf(UIMessagePart.Text(buildJsonObject { put("error", message) }.toString()))
    }

    private fun newFile(directory: File, extension: String): File {
        val stamp = System.currentTimeMillis()
        var candidate = File(directory, "$stamp.$extension")
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$stamp-$index.$extension")
            index++
        }
        return candidate
    }

    private fun fileUrl(file: File): String = "file://" + file.absolutePath

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val SENT_PAYLOAD = """{"sent":true}"""
        const val DRAW_SUCCESS_TEXT = "画好了，已经发给对方了"
        const val REJECTED_TEXT = "这次没生成：内容被服务那边拦下了，换个说法试试"
        const val DEFAULT_ASPECT_RATIO = "1:1"
        const val MAX_PROMPT_CHARS = 500

        /** 原生 `TTSProviderSetting.MiniMax` 的默认音色 / 语速。 */
        const val DEFAULT_VOICE_ID = "female-shaonv"
        const val DEFAULT_SPEED = 1.0f

        /** Hermes `minimax_tools/__init__.py` 的 RATIOS，逐字一致。 */
        val ASPECT_RATIOS = setOf("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3")
    }
}

/** speech-2.8 系列支持的全部 19 个语气标签（调研文档第三节）。 */
private val EMOTION_TAGS = listOf(
    "laughs", "chuckle", "coughs", "clear-throat", "groans",
    "breath", "pant", "inhale", "exhale", "gasps",
    "sniffs", "sighs", "snorts", "burps", "lip-smacking",
    "humming", "hissing", "emm", "sneezes",
)

private val EMOTION_TAG_REGEX = Regex(
    "[（(]\\s*(?:" + EMOTION_TAGS.joinToString("|") { Regex.escape(it) } + ")\\s*[)）]",
    RegexOption.IGNORE_CASE,
)

/** 标签剥掉后可能留下空括号（`((laughs))` → `()`），一并清掉。 */
private val EMPTY_PARENS_REGEX = Regex("[（(]\\s*[)）]")

private val SPACE_RUN_REGEX = Regex("[\\t ]{2,}")

/**
 * 去掉 MiniMax 的语气标签，得到语音条下面显示的文字。
 *
 * - 支持 speech-2.8 的 19 个标签全集（`(laughs)` `(sighs)` `(breath)` `(clear-throat)` …）；
 * - 半角/全角括号、大小写、标签内侧空格、连续标签、嵌套括号都能处理；
 * - 剥完清掉残留空括号、把多余空格压成一个、最后 trim；普通括号里的中文（如「（困）」）不动。
 *
 * 纯函数，给 [WhaleVoiceTools] 的 Audio metadata 用，也单独有单测。
 */
fun stripEmotionTags(text: String): String {
    var result = text
    while (true) {
        val replaced = EMOTION_TAG_REGEX.replace(result, "")
        if (replaced == result) break
        result = replaced
    }
    while (true) {
        val replaced = EMPTY_PARENS_REGEX.replace(result, "")
        if (replaced == result) break
        result = replaced
    }
    return SPACE_RUN_REGEX.replace(result, " ").trim()
}
