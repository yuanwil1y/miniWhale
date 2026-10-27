package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2 删掉了全部原生本地工具（含屏幕使用时间、日历），但老设置里的助手可能还存着这些开关。
 * 读进来不能崩，写回去要原样保留。
 */
class LocalToolOptionCompatTest {

    @Test
    fun `old assistant json with screen time and calendar still loads and round trips`() {
        val old = """
            {"id":"6f1c2a52-6a8e-4c43-9a0c-3c4a4c3c1f0e","name":"旧助手",
             "localTools":[{"type":"screen_time"},{"type":"calendar"},{"type":"javascript_engine"},
                           {"type":"time_info"},{"type":"clipboard"},{"type":"ask_user"},
                           {"type":"chart_display"},{"type":"tts"}]}
        """.trimIndent()

        val assistant = JsonInstant.decodeFromString<Assistant>(old)

        assertEquals(
            listOf(
                LocalToolOption.ScreenTime,
                LocalToolOption.Calendar,
                LocalToolOption.JavascriptEngine,
                LocalToolOption.TimeInfo,
                LocalToolOption.Clipboard,
                LocalToolOption.AskUser,
                LocalToolOption.ChartDisplay,
                LocalToolOption.Tts,
            ),
            assistant.localTools,
        )
        val written = JsonInstant.encodeToString(assistant)
        assertTrue(written.contains("\"screen_time\""))
        assertTrue(written.contains("\"calendar\""))
        assertEquals(assistant, JsonInstant.decodeFromString<Assistant>(written))
    }
}
