package me.rerere.rikkahub.di

import okhttp3.logging.HttpLoggingInterceptor

/**
 * OkHttp 的 logcat 请求日志（W-013）。
 *
 * - release：返回 null，根本不挂拦截器（调用处传 `BuildConfig.DEBUG`，R8 会把这段连同 logging-interceptor 一起剥掉）。
 * - debug：只打请求 / 响应头，凡是可能装着 Key 或会话凭据的头一律抹掉；Gemini 走 Vertex 时 Key 在 `?key=`，也抹掉。
 */
internal fun buildHttpLoggingInterceptor(
    debug: Boolean,
    logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
): HttpLoggingInterceptor? {
    if (!debug) return null
    return HttpLoggingInterceptor(logger).apply {
        REDACTED_HEADERS.forEach { redactHeader(it) }
        redactQueryParams(*REDACTED_QUERY_PARAMS)
        level = HttpLoggingInterceptor.Level.HEADERS
    }
}

internal val REDACTED_HEADERS = listOf(
    "Authorization",
    "Proxy-Authorization",
    "x-api-key",
    "x-goog-api-key",
    "api-key",
    "Cookie",
    "Set-Cookie",
)

internal val REDACTED_QUERY_PARAMS = arrayOf("key")
