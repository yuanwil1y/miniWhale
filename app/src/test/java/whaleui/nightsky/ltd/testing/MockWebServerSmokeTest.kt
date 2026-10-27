package whaleui.nightsky.ltd.testing

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/** 只用来确认 testImplementation 里的 MockWebServer 能在本工程的 JVM 单测里跑起来。 */
class MockWebServerSmokeTest {
    @Test
    fun servesOneResponse() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start()
        try {
            val client = OkHttpClient()
            val response = client.newCall(Request.Builder().url(server.url("/ping")).build()).execute()
            response.use { assertEquals("ok", it.body?.string()) }
        } finally {
            server.shutdown()
        }
    }
}