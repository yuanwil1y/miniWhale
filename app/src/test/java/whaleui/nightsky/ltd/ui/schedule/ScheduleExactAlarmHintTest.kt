package whaleui.nightsky.ltd.ui.schedule

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import whaleui.nightsky.ltd.alarm.ExactAlarmPermissionWatcher
import whaleui.nightsky.ltd.data.HerAlarmRepository
import whaleui.nightsky.ltd.data.db.HerAlarmEntity
import whaleui.nightsky.ltd.data.db.WhaleHerAlarmDao
import whaleui.nightsky.ltd.domain.settings.PermissionState

/** W-029：没有精确闹钟权限时定时任务页提示一行；W-018：权限状态变化才重排。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleExactAlarmHintTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `missing exact alarm permission shows the hint and granting it hides it`() {
        var exact = false
        val viewModel = ScheduleViewModel(
            FakeScheduleRepository(),
            FakeScheduleEngine(),
            HerAlarmRepository(EmptyHerAlarmDao()),
            canScheduleExact = { exact },
        )
        viewModel.enterPage()
        assertFalse(viewModel.uiState.value.exactAlarmAllowed)

        exact = true
        viewModel.enterPage()
        assertTrue(viewModel.uiState.value.exactAlarmAllowed)
    }

    @Test
    fun `watcher reports only real changes`() {
        val watcher = ExactAlarmPermissionWatcher()
        assertFalse("第一次只记下来", watcher.changed(PermissionState.Denied))
        assertFalse(watcher.changed(PermissionState.Denied))
        assertTrue("授权了", watcher.changed(PermissionState.Granted))
        assertFalse(watcher.changed(null))
        assertTrue("撤销了", watcher.changed(PermissionState.Denied))
        assertEquals(false, watcher.changed(PermissionState.Denied))
    }
}

/** 她一个闹钟都没定 */
private class EmptyHerAlarmDao : WhaleHerAlarmDao {
    override fun observeAll(): kotlinx.coroutines.flow.Flow<List<HerAlarmEntity>> = kotlinx.coroutines.flow.flowOf(emptyList())
    override suspend fun nextPending(now: Long): HerAlarmEntity? = null
    override suspend fun pendingBetween(start: Long, end: Long): List<HerAlarmEntity> = emptyList()
    override suspend fun upsert(alarm: HerAlarmEntity) = Unit
    override suspend fun markFired(id: String): Int = 0
    override suspend fun deleteById(id: String): Int = 0
}
