package me.rerere.rikkahub.di

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W-013：release 不挂 logcat 请求日志；debug 只打头，带 Key 的头和 `?key=` 都抹掉。 */
class HttpLoggingTest {
    @Test
    fun releaseHasNoLoggingInterceptor() {
        assertNull(buildHttpLoggingInterceptor(debug = false))
    }

    @Test
    fun debugLogsHeadersOnly() {
        val interceptor = buildHttpLoggingInterceptor(debug = true)
        assertNotNull(interceptor)
        assertEquals(HttpLoggingInterceptor.Level.HEADERS, interceptor!!.level)
    }

    @Test
    fun debugRedactsAllSecretHeadersAndKeyQuery() {
        val lines = mutableListOf<String>()
        val interceptor = buildHttpLoggingInterceptor(debug = true) { lines += it }!!
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Set-Cookie", "sid=SECRET-SET-COOKIE").setBody("ok"))
        server.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
            val request = Request.Builder()
                .url(server.url("/v1/models:generate?key=SECRET-QUERY&alt=sse"))
                .header("Authorization", "Bearer SECRET-AUTH")
                .header("Proxy-Authorization", "Basic SECRET-PROXY")
                .header("x-api-key", "SECRET-X-API")
                .header("x-goog-api-key", "SECRET-GOOG")
                .header("api-key", "SECRET-AZURE")
                .header("Cookie", "sid=SECRET-COOKIE")
                .header("X-Visible", "visible-value")
                .build()
            client.newCall(request).execute().use { it.body.string() }
        } finally {
            server.shutdown()
        }
        val log = lines.joinToString("\n")
        assertFalse(log, log.contains("SECRET"))
        assertTrue(log, log.contains("X-Visible: visible-value"))
        assertTrue(log, log.contains("x-goog-api-key: ██"))
        assertTrue(log, log.contains("alt=sse"))
        assertFalse(log, log.contains("ok\n"))
    }
}
