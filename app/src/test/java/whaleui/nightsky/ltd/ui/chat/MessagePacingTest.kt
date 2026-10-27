package whaleui.nightsky.ltd.ui.chat

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.domain.chat.ChatMessage
import whaleui.nightsky.ltd.domain.chat.MessageStatus
import whaleui.nightsky.ltd.domain.chat.StickerMessage

/** 第三点五节：节奏参数的边界。 */
class MessagePacingTest {

    private fun message(text: String? = null, voiceSeconds: Int? = null, image: Boolean = false) = ChatMessage(
        id = 1L,
        fromHer = true,
        text = text,
        imageUris = if (image) listOf("file:///a.png") else emptyList(),
        voiceSeconds = voiceSeconds,
        voiceUrl = if (voiceSeconds != null) "file:///a.mp3" else null,
        time = "14:00",
        dayLabel = "今天",
        status = MessageStatus.Read,
    )

    @Test
    fun `text delay is clamped between 700 and 2800`() {
        assertEquals(700L, MessagePacing.delayForText(""))
        assertEquals(700L, MessagePacing.delayForText("嗯"))          // 500 + 70 = 570 -> 700
        assertEquals(1_200L, MessagePacing.delayForText("一二三四五六七八九十")) // 500 + 10×70
        assertEquals(2_800L, MessagePacing.delayForText("字".repeat(100))) // 7500 -> 2800
    }

    @Test
    fun `voice delay is clamped between 800 and 2500`() {
        assertEquals(800L, MessagePacing.delayForVoice(0))
        assertEquals(900L, MessagePacing.delayForVoice(3))
        assertEquals(2_500L, MessagePacing.delayForVoice(60))
    }

    @Test
    fun `image delay is fixed`() {
        assertEquals(800L, MessagePacing.delayForImage())
    }

    @Test
    fun `sticker delay is fixed at about 900ms regardless of text`() {
        val sticker = ChatMessage(
            id = 2L,
            fromHer = false,
            time = "14:00",
            status = MessageStatus.Read,
            sticker = StickerMessage(id = "01-1000001", url = "file:///a.webp", description = "抱在一起"),
        )
        val random = Random(7)
        // 固定 900ms + ±15% 抖动 → 落在 765..1035
        repeat(50) {
            val delay = MessagePacing.delayFor(sticker, isFirstInTurn = false, random = random)
            assertTrue("delay=$delay", delay in 765L..1_035L)
        }
        assertEquals(MessagePacing.SENDING_STICKER_TEXT, MessagePacing.statusFor(sticker))
    }

    @Test
    fun `jitter stays within 15 percent and never reaches zero`() {
        val random = Random(42)
        repeat(200) {
            val value = MessagePacing.withJitter(2_000L, random)
            assertTrue("value=$value", value in 1_700L..2_300L)
        }
        assertEquals(0L, MessagePacing.withJitter(0L))
    }

    @Test
    fun `first bubble of a turn is immediate`() {
        assertEquals(0L, MessagePacing.delayFor(message("随便一句"), isFirstInTurn = true))
    }

    @Test
    fun `status text differs by bubble kind`() {
        assertEquals(MessagePacing.TYPING_TEXT, MessagePacing.statusFor(message("你好")))
        assertEquals(MessagePacing.RECORDING_TEXT, MessagePacing.statusFor(message(voiceSeconds = 5)))
        assertEquals(MessagePacing.SENDING_IMAGE_TEXT, MessagePacing.statusFor(message(image = true)))
    }
}