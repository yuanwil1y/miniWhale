package whaleui.nightsky.ltd.alarm

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * F5：精确闹钟权限的声明方式（主代理决定）。
 *
 * - Android 13+：`USE_EXACT_ALARM`，安装即授予、用户不能撤销（本 App 不上 Play，不受 Play 的用途审核限制）；
 * - Android 12 / 12L：没有 `USE_EXACT_ALARM`，仍用 `SCHEDULE_EXACT_ALARM`，并按官方写法加 `maxSdkVersion="32"`，
 *   13+ 上就不会再出现在「闹钟和提醒」特殊权限页里。
 */
class ExactAlarmManifestTest {

    private val manifest: List<Element> by lazy {
        val file = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .first { it.isFile }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("uses-permission")
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun permission(name: String): Element? =
        manifest.firstOrNull { it.getAttributeNS(ANDROID_NS, "name") == name }

    @Test
    fun `use exact alarm is declared for android 13 and later`() {
        val element = permission("android.permission.USE_EXACT_ALARM")
        assertTrue("要声明 USE_EXACT_ALARM", element != null)
        assertEquals("USE_EXACT_ALARM 不限版本", "", element!!.getAttributeNS(ANDROID_NS, "maxSdkVersion"))
    }

    @Test
    fun `schedule exact alarm is kept only for android 12`() {
        val element = permission("android.permission.SCHEDULE_EXACT_ALARM")
        assertTrue("Android 12 还要 SCHEDULE_EXACT_ALARM", element != null)
        assertEquals("32", element!!.getAttributeNS(ANDROID_NS, "maxSdkVersion"))
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
