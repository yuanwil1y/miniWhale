package me.rerere.ai.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.http.await
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.internal.http.RealResponseBody

fun List<CustomHeader>.toHeaders(): Headers {
    return Headers.Builder().apply {
        this@toHeaders
            .filter { it.name.isNotBlank() }
            .forEach {
                add(it.name, it.value)
            }
    }.build()
}

// 提供商级别的请求头优先级最低，会被请求级别(助手/模型)的同名请求头覆盖
fun ProviderSetting.mergeCustomHeaders(headers: List<CustomHeader> = emptyList()): Headers {
    val providerHeaders = customHeaders.filter { header ->
        headers.none { it.name.equals(header.name, ignoreCase = true) }
    }
    return (providerHeaders + headers).toHeaders()
}

fun Request.Builder.configureReferHeaders(url: String): Request.Builder {
    val httpUrl = url.toHttpUrl()
    return when (httpUrl.host) {
        // OpenRouter 用这两个头在它的应用排行里标明请求来源
        "openrouter.ai" -> {
            this
                .addHeader("X-Title", "miniWhale")
                .addHeader("HTTP-Referer", "https://github.com/yuanwil1y/miniWhale")
        }

        else -> this
    }
}

fun Request.Builder.configureSessionHeaders(url: String, sessionId: String?): Request.Builder = apply {
    if (sessionId != null) {
        header("X-Session-ID", sessionId)
        if (url.toHttpUrl().host == "opencode.ai") {
            header("x-opencode-session", sessionId)
        }
    }
}

/** 执行请求并读出响应体；不是 2xx 时报错，带上路径、状态码和响应体（不带 query，里面可能有 key） */
internal suspend fun OkHttpClient.fetchBody(request: Request): String = withContext(Dispatchers.IO) {
    newCall(request).await().use { response ->
        val body = response.body.string()
        if (!response.isSuccessful) {
            error("Request to ${request.url.encodedPath} failed: ${response.code} $body")
        }
        body
    }
}

fun ResponseBody.stringSafe(): String? {
    return when (this) {
        is RealResponseBody -> string()
        else -> null
    }
}

fun JsonObject.mergeCustomBody(bodies: List<CustomBody>): JsonObject {
    if (bodies.isEmpty()) return this

    val content = toMutableMap()
    bodies.forEach { body ->
        if (body.key.isNotBlank()) {
            // 如果已存在相同键且两者都是JsonObject，则需要递归合并
            val existingValue = content[body.key]
            val newValue = body.value

            if (existingValue is JsonObject && newValue is JsonObject) {
                // 递归合并两个JsonObject
                content[body.key] = mergeJsonObjects(existingValue, newValue)
            } else {
                // 直接替换或添加
                content[body.key] = newValue
            }
        }
    }
    return JsonObject(content)
}

/**
 * 递归合并两个JsonObject
 */
private fun mergeJsonObjects(base: JsonObject, overlay: JsonObject): JsonObject {
    val result = base.toMutableMap()

    for ((key, value) in overlay) {
        val baseValue = result[key]

        result[key] = if (baseValue is JsonObject && value is JsonObject) {
            // 如果两者都是JsonObject，递归合并
            mergeJsonObjects(baseValue, value)
        } else {
            // 否则使用新值替换旧值
            value
        }
    }

    return JsonObject(result)
}
