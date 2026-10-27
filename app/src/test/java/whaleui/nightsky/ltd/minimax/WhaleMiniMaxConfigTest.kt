package whaleui.nightsky.ltd.minimax

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** 任务书 6-1：默认值不变，可被设置覆盖。 */
class WhaleMiniMaxConfigTest {

    @After
    fun tearDown() = WhaleMiniMaxConfig.reset()

    @Test
    fun `defaults are unchanged`() {
        WhaleMiniMaxConfig.reset()
        assertEquals("https://api.minimax.cn", WhaleMiniMaxConfig.baseUrl)
        assertEquals("speech-2.8-hd", WhaleMiniMaxConfig.speechModel)
        assertEquals("image-01", WhaleMiniMaxConfig.imageModel)
    }

    @Test
    fun `overrides are applied and blanks fall back to defaults`() {
        WhaleMiniMaxConfig.apply(
            baseUrl = "https://api.minimaxi.com/",
            speechModel = "speech-2.6",
            imageModel = " ",
        )
        assertEquals("https://api.minimaxi.com", WhaleMiniMaxConfig.baseUrl)
        assertEquals("speech-2.6", WhaleMiniMaxConfig.speechModel)
        assertEquals("image-01", WhaleMiniMaxConfig.imageModel)
    }

    @Test
    fun `apply swaps all three values in one snapshot`() {
        val before = WhaleMiniMaxConfig.current
        WhaleMiniMaxConfig.apply("https://api.minimaxi.com", "speech-2.6", "image-02")
        // 之前取到的快照不受影响；新快照三个值一起换（W-058）
        assertEquals(WhaleMiniMaxConfig.Values(), before)
        assertEquals(
            WhaleMiniMaxConfig.Values("https://api.minimaxi.com", "speech-2.6", "image-02"),
            WhaleMiniMaxConfig.current,
        )
    }
}