package whaleui.nightsky.ltd.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 固定对话的标题：导出聊天文件名 `chat/<日期>_<标题>.md` 用它，改名后要是 miniWhale。 */
class RikkaChatListRepositoryTest {

    @Test
    fun `legacy and blank titles become the app name`() {
        assertEquals("miniWhale", RikkaChatListRepository.displayTitle("小鲸鱼"))
        assertEquals("miniWhale", RikkaChatListRepository.displayTitle(" 小鲸鱼 "))
        assertEquals("miniWhale", RikkaChatListRepository.displayTitle(""))
        assertEquals("miniWhale", RikkaChatListRepository.displayTitle("   "))
    }

    @Test
    fun `other titles are kept as is`() {
        assertEquals("miniWhale", RikkaChatListRepository.displayTitle("miniWhale"))
        assertEquals("周末计划", RikkaChatListRepository.displayTitle("周末计划"))
    }
}
