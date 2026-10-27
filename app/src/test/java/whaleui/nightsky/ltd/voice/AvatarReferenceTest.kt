package whaleui.nightsky.ltd.voice

import java.io.File
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AvatarReferenceTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `default white avatar sends no reference image`() {
        // 没设自定义头像（avatarPath = null）时不能拿一张白图当角色参考
        assertNull(AvatarReference.dataUriOf(null))
    }

    @Test
    fun `missing or empty avatar file sends no reference image`() {
        assertNull(AvatarReference.dataUriOf(File(temp.root, "gone.jpg").absolutePath))
        val empty = temp.newFile("empty.jpg")
        assertNull(AvatarReference.dataUriOf(empty.absolutePath))
    }
}
