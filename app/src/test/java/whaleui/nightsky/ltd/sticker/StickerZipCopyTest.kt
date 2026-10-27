package whaleui.nightsky.ltd.sticker

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** N-005：表情 zip 整包复制进缓存时按实际字节限流。 */
class StickerZipCopyTest {

    private fun tempFile(): File = File.createTempFile("sticker-zip", ".zip").apply { deleteOnExit() }

    @Test
    fun `copies a package within the limit`() {
        val target = tempFile()
        val bytes = ByteArray(10_000) { (it % 251).toByte() }
        assertEquals(10_000L, copyStickerZipLimited(ByteArrayInputStream(bytes), target, limit = 10_000))
        assertTrue(target.readBytes().contentEquals(bytes))
    }

    @Test
    fun `aborts past the limit and deletes the partial file`() {
        val target = tempFile()
        // 声明多大不重要：这个流一直有数据，靠实际读到的字节数截住
        val endless = object : InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { b.fill(1, off, off + len) }
        }
        try {
            copyStickerZipLimited(endless, target, limit = 1_000_000)
            fail("应该中止")
        } catch (_: StickerZipTooLargeException) {
        }
        assertFalse("半成品要删掉", target.exists())
    }

    @Test
    fun `read failure deletes the partial file and rethrows`() {
        val target = tempFile()
        val broken = object : InputStream() {
            var served = 0
            override fun read(): Int = throw IOException("broken")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served > 0) throw IOException("broken")
                served += len
                return len
            }
        }
        try {
            copyStickerZipLimited(broken, target)
            fail("应该抛出")
        } catch (e: IOException) {
            assertEquals("broken", e.message)
        }
        assertFalse(target.exists())
    }

    @Test
    fun `default limit is 200 MB`() {
        assertEquals(200L * 1024 * 1024, STICKER_ZIP_MAX_BYTES)
    }
}
