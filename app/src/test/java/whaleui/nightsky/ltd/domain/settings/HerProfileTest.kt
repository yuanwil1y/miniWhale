package whaleui.nightsky.ltd.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import whaleui.nightsky.ltd.data.HerAvatarFiles

class HerProfileTest {

    @Test
    fun `defaults are white avatar and nickname default`() {
        val profile = HerProfile()
        assertEquals("default", profile.nickname)
        assertEquals(HerProfile.DEFAULT_NICKNAME, profile.nickname)
        assertNull(profile.avatarPath)
        assertFalse(profile.hasCustomAvatar)
    }

    @Test
    fun `empty or blank nickname normalizes to default`() {
        assertEquals("default", HerProfile.normalizeNickname(""))
        assertEquals("default", HerProfile.normalizeNickname("   "))
        assertEquals("default", HerProfile.normalizeNickname("\n\r\n"))
    }

    @Test
    fun `nickname is trimmed and newlines become spaces`() {
        assertEquals("阿 B", HerProfile.normalizeNickname("  阿\nB  "))
    }

    @Test
    fun `nickname is clamped to 20 code points without splitting emoji`() {
        val long = "字".repeat(25)
        assertEquals("字".repeat(20), HerProfile.normalizeNickname(long))

        val emoji = "🐳".repeat(21) // 21 个鲸鱼 emoji，每个两个 char
        val clamped = HerProfile.clampInput(emoji)
        assertEquals(20, HerProfile.lengthOf(clamped))
        assertEquals(40, clamped.length)
    }

    @Test
    fun `clampInput keeps short text unchanged`() {
        assertEquals("abc", HerProfile.clampInput("abc"))
        assertEquals(3, HerProfile.lengthOf("a🐳b"))
    }

    @Test
    fun `centerSquare crops the middle`() {
        assertEquals(Triple(100, 0, 300), HerAvatarFiles.centerSquare(500, 300))
        assertEquals(Triple(0, 50, 200), HerAvatarFiles.centerSquare(200, 300))
        assertEquals(Triple(0, 0, 64), HerAvatarFiles.centerSquare(64, 64))
    }
}
