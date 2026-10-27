package whaleui.nightsky.ltd.minimax

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * 小鲸鱼自己的 MiniMax 客户端。
 *
 * 覆盖四个接口（同一个 base_url、同一个 Bearer 鉴权、同一套错误翻译）：
 * - [synthesize]      POST /v1/t2a_v2            非流式语音合成，hex 音频 → mp3 字节
 * - [generateImage]   POST /v1/image_generation  文生图，base64 → 图片字节
 * - [listVoices]      POST /v1/get_voice         系统音色列表
 * - [queryQuota]      GET  /v1/token_plan/remains（普通 Key）或 /account/query_balance（`sk-api-` 开头的按量 Key）
 *
 * 几个设计约定：
 * - MiniMax 的 HTTP 200 也可能是业务失败，错误码在 `base_resp.status_code` 里，统一检查并翻成中文；
 * - 任何失败（没配 Key、网络异常、HTTP 非 200、JSON/hex/base64 解析失败、20 秒超时）都包成
 *   [Result.failure]，**不向外抛异常**，让调用方（工具层）只需要 `onFailure` 就能给出中文提示；
 * - 20 秒超时用 [withTimeout] 实现，覆盖整个"发请求 + 读响应 + 解析"过程，不会依赖调用方 OkHttpClient 的默认超时；
 * - 不修改传进来的 [OkHttpClient]（连接池、拦截器和调用方共享）。
 *
 * @param baseUrl 接口根地址，默认国内站 `https://api.minimax.cn`；末尾斜杠会被忽略。
 * @param apiKeyProvider 每次请求时现取 Key，返回 null / 空白串视为"还没有配置"。
 * @param okHttpClient 复用的 OkHttp 客户端（测试里也可以传短超时的实例）。
 */
class WhaleMiniMaxClient(
    /** 传 null 表示用 [WhaleMiniMaxConfig] 里的值（默认 `https://api.minimax.cn`） */
    private val baseUrl: String? = null,
    private val apiKeyProvider: () -> String?,
    private val okHttpClient: OkHttpClient,
) {

    // ------------------------------------------------------------------ 语音

    /**
     * 合成一段语音，返回 mp3 字节和时长（毫秒，直接用服务端 `extra_info.audio_length`）。
     *
     * 非流式 + `output_format:"hex"`：服务端把整段 mp3 用 hex 编码放进 `data.audio`，
     * 音色/语速/音量/音调走 `voice_setting`，输出格式走 `audio_setting`。
     */
    suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        /** null = 用设置里的语音模型 */
        model: String? = null,
    ): Result<MiniMaxAudio> = withApiKey { apiKey ->
        // 模型和地址取自同一份快照（W-058）
        val config = WhaleMiniMaxConfig.current
        val payload = buildJsonObject {
            put("model", model ?: config.speechModel)
            put("text", text)
            put("stream", false)
            put("output_format", "hex")
            put("voice_setting", buildJsonObject {
                put("voice_id", voiceId)
                put("speed", speed)
                put("vol", DEFAULT_VOLUME)
                put("pitch", DEFAULT_PITCH)
            })
            put("audio_setting", buildJsonObject {
                put("format", "mp3")
                put("sample_rate", DEFAULT_SAMPLE_RATE)
                put("bitrate", DEFAULT_BITRATE)
                put("channel", DEFAULT_CHANNEL)
            })
        }

        val root = postJson(apiKey, "/v1/t2a_v2", payload, config)
        val audioHex = root.jsonObjectOrNull("data")?.jsonStringOrNull("audio")
        if (audioHex.isNullOrBlank()) {
            throw MiniMaxException("MiniMax 没有返回语音数据（data.audio 为空）")
        }
        val mp3 = decodeHex(audioHex)
        if (mp3.isEmpty()) {
            throw MiniMaxException("MiniMax 返回的语音数据为空")
        }
        val durationMs = root.jsonObjectOrNull("extra_info")?.jsonLongOrNull("audio_length") ?: 0L
        MiniMaxAudio(mp3 = mp3, durationMs = durationMs)
    }

    // ------------------------------------------------------------------ 作图

    /** 文生图，返回图片字节（`response_format=base64`，取 `data.image_base64[0]`）。 */
    suspend fun generateImage(
        prompt: String,
        aspectRatio: String,
        /** null = 用设置里的作图模型 */
        model: String? = null,
        /**
         * 角色参考图（data URI）。画**她自己**时传头像，画别的东西时传 null。
         *
         * 字段格式照官方 CLI（`MiniMax-AI/cli` 的 `src/commands/image/generate.ts`）：
         * `subject_reference: [{ type: "character", image_file: "data:image/png;base64,..." }]`
         * （http 地址用 `image_url`，本地文件用 `image_file`）。
         */
        subjectReferenceDataUri: String? = null,
    ): Result<MiniMaxImage> = withApiKey { apiKey ->
        val config = WhaleMiniMaxConfig.current
        val payload = buildJsonObject {
            put("model", model ?: config.imageModel)
            put("prompt", prompt)
            put("aspect_ratio", aspectRatio)
            put("response_format", "base64")
            put("n", 1)
            if (!subjectReferenceDataUri.isNullOrBlank()) {
                put(
                    "subject_reference",
                    buildJsonArray {
                        add(buildJsonObject {
                            put("type", "character")
                            put("image_file", subjectReferenceDataUri)
                        })
                    },
                )
            }
        }

        if (me.rerere.rikkahub.BuildConfig.DEBUG) {
            runCatching {
                android.util.Log.i(
                    "WhaleDraw",
                    "image_generation subject_reference=${payload["subject_reference"] != null}" +
                        " (self=${!subjectReferenceDataUri.isNullOrBlank()})",
                )
            }
        }
        val root = postJson(apiKey, "/v1/image_generation", payload, config)
        val data = root.jsonObjectOrNull("data")
        // 官方是数组，这里也兼容直接给单个字符串的实现。
        val base64 = data?.jsonStringArrayOrNull("image_base64")?.firstOrNull { it.isNotBlank() }
            ?: data?.jsonStringOrNull("image_base64")?.takeIf { it.isNotBlank() }
        if (base64.isNullOrBlank()) {
            throw MiniMaxException("MiniMax 没有返回图片数据（data.image_base64 为空）")
        }
        val png = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            throw MiniMaxException("MiniMax 返回的图片数据不是合法的 base64", e)
        }
        if (png.isEmpty()) {
            throw MiniMaxException("MiniMax 返回的图片数据为空")
        }
        MiniMaxImage(png = png)
    }

    // ------------------------------------------------------------------ 音色

    /**
     * 系统音色列表。
     *
     * 字段名不同版本略有差异（`system_voice` / `voice_list`、`voice_id` / `voiceId`），
     * 所以这里尽量宽容：解析不出来时返回空列表而不是失败；只有 base_resp 业务错误才报错。
     */
    suspend fun listVoices(): Result<List<MiniMaxVoice>> = withApiKey { apiKey ->
        val body = execute(
            postRequest(
                apiKey = apiKey,
                path = "/v1/get_voice",
                payload = buildJsonObject { put("voice_type", "system") },
            )
        )
        // HTTP 成功但内容不是 JSON（例如被网关替换成 HTML）时按"没有音色"处理，不崩。
        val root = runCatching { parseObject(body) }.getOrNull() ?: return@withApiKey emptyList()
        throwIfBusinessError(root)
        runCatching { parseVoices(root) }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------ 额度

    /**
     * 查额度，返回一小段人类可读的中文。
     *
     * `sk-api-` 开头的是按量付费 Key，走 `GET /account/query_balance`；
     * 其余（Token Plan / M Plan）走 `GET /v1/token_plan/remains`。
     * 优先挑语音、图片相关的条目（剩余百分比 + 重置时间），挑不到就把原始 JSON 截断返回。
     */
    suspend fun queryQuota(): Result<String> = withApiKey { apiKey ->
        val path = if (apiKey.startsWith(PAY_AS_YOU_GO_KEY_PREFIX)) {
            "/account/query_balance"
        } else {
            "/v1/token_plan/remains"
        }
        formatQuota(getJson(apiKey, path))
    }

    // ------------------------------------------------------------ 请求执行

    private suspend fun <T> withApiKey(block: suspend (String) -> T): Result<T> {
        val apiKey = apiKeyProvider()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Result.failure(IllegalStateException(NO_API_KEY_MESSAGE))
        return runRequest { block(apiKey) }
    }

    /** 整个请求过程的统一兜底：超时、网络异常、解析异常都转成 [Result.failure]。 */
    private suspend fun <T> runRequest(block: suspend () -> T): Result<T> = try {
        Result.success(withTimeout(REQUEST_TIMEOUT_MS) { block() })
    } catch (e: TimeoutCancellationException) {
        Result.failure(MiniMaxException("MiniMax 请求超时（20 秒）", e, MiniMaxException.Kind.Temporary))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private suspend fun postJson(
        apiKey: String,
        path: String,
        payload: JsonObject,
        config: WhaleMiniMaxConfig.Values = WhaleMiniMaxConfig.current,
    ): JsonObject = parseChecked(execute(postRequest(apiKey, path, payload, config)))

    private suspend fun getJson(apiKey: String, path: String): JsonObject =
        parseChecked(execute(getRequest(apiKey, path)))

    private fun postRequest(
        apiKey: String,
        path: String,
        payload: JsonObject,
        config: WhaleMiniMaxConfig.Values = WhaleMiniMaxConfig.current,
    ): Request =
        Request.Builder()
            .url(endpoint(path, config))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

    private fun getRequest(apiKey: String, path: String): Request =
        Request.Builder()
            .url(endpoint(path))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

    // 每次调用都读一次，这样设置里改了不用重启（默认值仍是 https://api.minimax.cn）
    private fun endpoint(path: String, config: WhaleMiniMaxConfig.Values = WhaleMiniMaxConfig.current): String =
        (baseUrl ?: config.baseUrl).trimEnd('/') + path

    /**
     * 异步执行 OkHttp 调用；协程取消（含 withTimeout）时顺带取消 HTTP 调用。
     * 非 2xx 会带着响应体抛 [MiniMaxException]。
     */
    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = okHttpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use { r ->
                        val body = r.body?.string().orEmpty()
                        if (r.isSuccessful) body else throw httpError(r.code, body)
                    }
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }

    // ------------------------------------------------------------ JSON 校验

    private fun parseChecked(body: String): JsonObject =
        parseObject(body).also { throwIfBusinessError(it) }

    private fun parseObject(body: String): JsonObject {
        val element = try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw MiniMaxException("MiniMax 返回内容不是合法 JSON", e)
        }
        return element as? JsonObject ?: throw MiniMaxException("MiniMax 返回内容不是 JSON 对象")
    }

    /** HTTP 200 也可能业务失败：`base_resp.status_code != 0` 时抛出翻译好的中文异常。 */
    private fun throwIfBusinessError(root: JsonObject) {
        val base = root.jsonObjectOrNull("base_resp") ?: return
        val code = base.jsonLongOrNull("status_code")?.toInt() ?: return
        if (code == 0) return
        throw businessException(code, base.jsonStringOrNull("status_msg").orEmpty())
    }

    private fun businessException(code: Int, statusMsg: String): MiniMaxException {
        val detail = if (statusMsg.isBlank()) "" else "（服务端提示：$statusMsg）"
        val message = when (code) {
            CODE_AUTH_FAILED -> "MiniMax 鉴权失败，请检查 MiniMax Key$detail"
            CODE_NO_BALANCE -> "MiniMax 余额不足，请充值后再试$detail"
            CODE_SENSITIVE_CONTENT -> "MiniMax 拒绝生成：内容包含敏感信息$detail"
            CODE_INVALID_PARAMETER -> "MiniMax 参数错误，请检查请求参数$detail"
            else -> "MiniMax 返回错误 $code：${statusMsg.ifBlank { "未知错误" }}"
        }
        val kind = when (code) {
            CODE_AUTH_FAILED -> MiniMaxException.Kind.Auth
            CODE_NO_BALANCE -> MiniMaxException.Kind.NoBalance
            CODE_SENSITIVE_CONTENT -> MiniMaxException.Kind.Rejected
            CODE_INVALID_PARAMETER -> MiniMaxException.Kind.Rejected
            else -> MiniMaxException.Kind.Other
        }
        return MiniMaxException(message, kind = kind)
    }

    /** 非 2xx：能从中读出 base_resp 错误码就用中文业务错误，否则给出 HTTP 状态码 + 响应体片段。 */
    private fun httpError(statusCode: Int, body: String): MiniMaxException {
        val business = runCatching {
            val base = parseObject(body).jsonObjectOrNull("base_resp")
            val code = base?.jsonLongOrNull("status_code")?.toInt() ?: 0
            if (code != 0) businessException(code, base?.jsonStringOrNull("status_msg").orEmpty()) else null
        }.getOrNull()
        if (business != null) {
            return MiniMaxException("${business.message}（HTTP $statusCode）", business, business.kind)
        }
        val snippet = body.trim().take(HTTP_BODY_SNIPPET_CHARS)
        val suffix = if (snippet.isBlank()) "" else "：$snippet"
        val kind = when (statusCode) {
            401, 403 -> MiniMaxException.Kind.Auth
            402 -> MiniMaxException.Kind.NoBalance
            408, 429, in 500..599 -> MiniMaxException.Kind.Temporary
            else -> MiniMaxException.Kind.Other
        }
        return MiniMaxException("MiniMax 请求失败：HTTP $statusCode$suffix", kind = kind)
    }

    // ------------------------------------------------------------ hex 解码

    private fun decodeHex(hex: String): ByteArray {
        val clean = hex.filterNot { it.isWhitespace() }
        if (clean.length % 2 != 0) {
            throw MiniMaxException("MiniMax 返回的音频数据不是合法的 hex（长度为奇数）")
        }
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) {
            val high = Character.digit(clean[i * 2], 16)
            val low = Character.digit(clean[i * 2 + 1], 16)
            if (high < 0 || low < 0) {
                throw MiniMaxException("MiniMax 返回的音频数据不是合法的 hex")
            }
            bytes[i] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    // ------------------------------------------------------------ 音色解析

    private fun parseVoices(root: JsonObject): List<MiniMaxVoice> {
        val containers = buildList {
            add(root)
            root.jsonObjectOrNull("data")?.let { add(it) }
        }
        val candidates = buildList {
            containers.forEach { container ->
                VOICE_LIST_KEYS.forEach { key -> container[key]?.let { add(it) } }
            }
            (root["data"] as? JsonArray)?.let { add(it) }
        }

        val items = candidates.flatMap { element ->
            when (element) {
                is JsonArray -> element.mapNotNull { it as? JsonObject }
                is JsonObject -> listOf(element)
                else -> emptyList()
            }
        }

        return items.mapNotNull { item ->
            val id = item.stringAnyOf("voice_id", "voiceId", "id") ?: return@mapNotNull null
            MiniMaxVoice(
                id = id,
                name = item.stringAnyOf("voice_name", "voiceName", "name") ?: id,
                description = item.descriptionOrNull(),
            )
        }.distinctBy { it.id }
    }

    private fun JsonObject.descriptionOrNull(): String? {
        val element = this["description"] ?: this["desc"] ?: return null
        return when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .joinToString("；")
                .ifBlank { null }
            else -> null
        }
    }

    // ------------------------------------------------------------ 额度文案

    private fun formatQuota(root: JsonObject): String {
        val remains = root.jsonArrayOrNull("model_remains")
            ?: root.jsonObjectOrNull("data")?.jsonArrayOrNull("model_remains")
        if (remains != null) {
            val models = remains.mapNotNull { it as? JsonObject }
            if (models.isNotEmpty()) {
                val relevant = models.filter {
                    isSpeechOrImageModel(it.jsonStringOrNull("model_name") ?: it.jsonStringOrNull("model"))
                }
                val lines = relevant.ifEmpty { models }.mapNotNull { formatModelRemain(it) }
                if (lines.isNotEmpty()) {
                    return "MiniMax 额度：" + lines.joinToString("；")
                }
            }
        }
        return compactRaw(root)
    }

    private fun formatModelRemain(item: JsonObject): String? {
        val name = item.jsonStringOrNull("model_name") ?: item.jsonStringOrNull("model") ?: return null
        val parts = mutableListOf<String>()

        val intervalPercent = item.jsonDoubleOrNull("current_interval_remaining_percent")
            ?: item.jsonDoubleOrNull("interval_remaining_percent")
            ?: item.jsonDoubleOrNull("remaining_percent")
        if (intervalPercent != null) {
            parts += "剩余 ${formatPercent(intervalPercent)}"
        }

        val weeklyPercent = item.jsonDoubleOrNull("current_weekly_remaining_percent")
            ?: item.jsonDoubleOrNull("weekly_remaining_percent")
        if (weeklyPercent != null) {
            parts += "本周剩余 ${formatPercent(weeklyPercent)}"
        }

        val total = item.jsonLongOrNull("current_interval_total_count")
            ?: item.jsonLongOrNull("interval_total_count")
        val used = item.jsonLongOrNull("current_interval_usage_count")
            ?: item.jsonLongOrNull("interval_usage_count")
        if (total != null && used != null) {
            parts += "已用 $used/$total"
        }

        resetTimeOf(item)?.let { parts += "重置 $it" }

        if (parts.isEmpty()) return null
        return "$name ${parts.joinToString("，")}"
    }

    private fun formatPercent(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "-"
        val rounded = kotlin.math.round(value * 10) / 10
        val text = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
        return "$text%"
    }

    private fun resetTimeOf(item: JsonObject): String? =
        RESET_TIME_KEYS.firstNotNullOfOrNull { key -> formatResetValue(item[key]) }

    private fun formatResetValue(element: JsonElement?): String? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        primitive.longOrNull?.takeIf { it > 0 }?.let { return formatEpoch(it) }
        primitive.doubleOrNull?.toLong()?.takeIf { it > 0 }?.let { return formatEpoch(it) }
        return primitive.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun formatEpoch(value: Long): String {
        val millis = if (value < EPOCH_MILLIS_THRESHOLD) value * 1000L else value
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
        } catch (e: Exception) {
            value.toString()
        }
    }

    private fun isSpeechOrImageModel(modelName: String?): Boolean {
        val name = modelName?.lowercase() ?: return false
        return SPEECH_IMAGE_MODEL_KEYWORDS.any { name.contains(it) }
    }

    private fun compactRaw(root: JsonObject): String {
        val visible = JsonObject(root.filterKeys { it != "base_resp" })
        val text = (if (visible.isNotEmpty()) visible else root).toString()
        return if (text.length <= MAX_QUOTA_RAW_CHARS) text else text.take(MAX_QUOTA_RAW_CHARS) + "…"
    }

    // ------------------------------------------------------------ 小工具

    private fun JsonObject.jsonObjectOrNull(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.jsonArrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.jsonStringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.jsonLongOrNull(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        primitive.longOrNull?.let { return it }
        return primitive.doubleOrNull?.toLong()
    }

    private fun JsonObject.jsonDoubleOrNull(key: String): Double? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        primitive.doubleOrNull?.let { return it }
        return primitive.contentOrNull?.trim()?.removeSuffix("%")?.toDoubleOrNull()
    }

    private fun JsonObject.jsonStringArrayOrNull(key: String): List<String>? =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.stringAnyOf(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { jsonStringOrNull(it)?.takeIf { value -> value.isNotBlank() } }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.minimax.cn"
        const val DEFAULT_SPEECH_MODEL: String = "speech-2.8-hd"
        const val DEFAULT_IMAGE_MODEL: String = "image-01"
        const val PAY_AS_YOU_GO_KEY_PREFIX: String = "sk-api-"
        const val NO_API_KEY_MESSAGE: String = "还没有配置 MiniMax Key"

        private const val REQUEST_TIMEOUT_MS = 20_000L
        private const val CODE_AUTH_FAILED = 1004
        private const val CODE_NO_BALANCE = 1008
        private const val CODE_SENSITIVE_CONTENT = 1026
        private const val CODE_INVALID_PARAMETER = 2013

        private const val DEFAULT_VOLUME = 1
        private const val DEFAULT_PITCH = 0
        private const val DEFAULT_SAMPLE_RATE = 32_000
        private const val DEFAULT_BITRATE = 128_000
        private const val DEFAULT_CHANNEL = 1

        private const val HTTP_BODY_SNIPPET_CHARS = 200
        private const val MAX_QUOTA_RAW_CHARS = 400
        private const val EPOCH_MILLIS_THRESHOLD = 10_000_000_000L

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private val VOICE_LIST_KEYS = listOf(
            "system_voice",
            "voice_list",
            "voices",
            "voice_cloning",
            "voice_generation",
        )

        private val RESET_TIME_KEYS = listOf(
            "end_time",
            "current_interval_end_time",
            "current_weekly_end_time",
            "reset_time",
            "next_reset_time",
            "expire_time",
        )

        private val SPEECH_IMAGE_MODEL_KEYWORDS = listOf("speech", "t2a", "voice", "audio", "image")
    }
}

/**
 * MiniMax 客户端统一抛出的异常类型；所有对外方法都会把它包进 [Result.failure]。
 *
 * [message] 是写给设置页的人看的；[kind] 让工具层把它换成她视角的说法（提示词第 10 批方案 A）。
 */
class MiniMaxException(
    message: String,
    cause: Throwable? = null,
    val kind: Kind = Kind.Other,
) : Exception(message, cause) {
    enum class Kind {
        /** Key 无效 / 鉴权失败：要对方去设置里处理 */
        Auth,

        /** 余额不足：要对方去设置里处理 */
        NoBalance,

        /** 内容被拒（敏感 / 参数不对）：换个说法 */
        Rejected,

        /** 超时、限流、服务端错误：等会儿再试 */
        Temporary,

        /** 其它（返回格式不对等） */
        Other,
    }
}

/** 非流式合成的结果：mp3 字节 + 服务端给的时长（毫秒）。 */
data class MiniMaxAudio(
    val mp3: ByteArray,
    val durationMs: Long,
)

/** 文生图的结果：图片字节（默认 PNG）。 */
data class MiniMaxImage(
    val png: ByteArray,
)

/** 音色列表的一项。 */
data class MiniMaxVoice(
    val id: String,
    val name: String,
    val description: String? = null,
)
