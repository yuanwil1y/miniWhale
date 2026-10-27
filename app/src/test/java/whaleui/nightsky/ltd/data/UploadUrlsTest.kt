package whaleui.nightsky.ltd.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 图片 URL 是否需要先复制成本地文件。回归的是「选图后她只看到空消息」那个 bug。 */
class UploadUrlsTest {

    @Test
    fun `file and data and http urls are usable as is`() {
        assertTrue(isDirectlyEncodable("file:///data/user/0/pkg/files/whale/uploads/a.png"))
        assertTrue(isDirectlyEncodable("data:image/png;base64,AAAA"))
        assertTrue(isDirectlyEncodable("http://example.com/a.png"))
        assertTrue(isDirectlyEncodable("https://example.com/a.png"))
    }

    @Test
    fun `content uris from the pickers must be copied first`() {
        assertFalse(
            isDirectlyEncodable(
                "content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fa.png",
            ),
        )
        assertFalse(isDirectlyEncodable("content://media/picker/0/com.android.providers.media.photopicker/media/1000000034"))
    }

    @Test
    fun `file url helper always produces the double slash form`() {
        // 注意：这条测试在 JVM 上跑，绝对路径带盘符；这里只断言「HTTP 层认的形式」，
        // 不去断言具体路径（设备上是 POSIX 的 file:///data/...）。
        val url = fileUrlOf(java.io.File("whale/uploads/a.png"))
        assertTrue(url.startsWith("file://"))
        assertTrue(isDirectlyEncodable(url))
        // File.toURI() 会给 file:/xxx（单斜杠），encodeBase64 不认，所以不能用它
        assertTrue(url != java.io.File("whale/uploads/a.png").toURI().toString() || !url.startsWith("file:/"))
    }

    @Test
    fun `blank and unknown schemes are not directly encodable`() {
        assertFalse(isDirectlyEncodable(""))
        assertFalse(isDirectlyEncodable("/storage/emulated/0/Download/a.png"))
        assertFalse(isDirectlyEncodable("assets://a.png"))
    }
}