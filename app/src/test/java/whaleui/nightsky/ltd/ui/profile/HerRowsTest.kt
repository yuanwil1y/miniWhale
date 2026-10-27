package whaleui.nightsky.ltd.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.domain.settings.PermissionState
import whaleui.nightsky.ltd.domain.settings.RequiredPermission

/** 资料页「她」分组（B）：行名、运行中 / 未运行的判定、可点的行。 */
class HerRowsTest {

    private val allGranted = RequiredPermission.entries.associateWith { PermissionState.Granted }

    private fun ready(state: ProfileUiState = ProfileUiState(soulImported = true, goutouInstalled = true, permissions = allGranted)) =
        herRows(state, keyReady = true, assistantReady = true)

    @Test
    fun rowNamesAndOrder() {
        assertEquals(
            listOf("人设", "Persona Guard", "狗头军师", "热点新闻", "Proactive Agent"),
            ready().map { it.title },
        )
    }

    @Test
    fun everythingRunningWhenPrerequisitesMet() {
        val rows = ready()
        assertEquals(listOf("已导入", RowRunning, RowRunning, RowRunning, RowCanReach), rows.map { it.value })
        assertTrue(rows.all { it.active })
        assertEquals("🟢 运行中", RowRunning)
        assertEquals("🟢 能按时找你", RowCanReach)
    }

    @Test
    fun missingKeyStopsTheTurnFeaturesOnly() {
        val rows = herRows(
            ProfileUiState(soulImported = true, goutouInstalled = true, permissions = allGranted),
            keyReady = false,
            assistantReady = true,
        )
        assertEquals(listOf("已导入", RowStopped, RowStopped, RowStopped, RowCanReach), rows.map { it.value })
        assertEquals("🚧 未运行", RowStopped)
        assertEquals("🚧 不能", RowCannotReach)
    }

    @Test
    fun goutouNeedsSkillFile() {
        val rows = ready(ProfileUiState(goutouInstalled = false, permissions = allGranted))
        assertEquals(RowStopped, rows[2].value)
        assertEquals(RowRunning, rows[1].value)
        assertEquals("未导入", rows[0].value)
        assertFalse(rows[0].active)
    }

    @Test
    fun proactiveNeedsSwitchAndThreePermissions() {
        assertEquals(RowCannotReach, ready(ProfileUiState(proactiveEnabled = false, permissions = allGranted))[4].value)
        val noAlarm = allGranted + (RequiredPermission.ExactAlarm to PermissionState.Denied)
        assertEquals(RowCannotReach, ready(ProfileUiState(permissions = noAlarm))[4].value)
        // 开机自启系统查不到（Unknown），不影响判定
        val bootUnknown = allGranted + (RequiredPermission.BootStart to PermissionState.Unknown) +
            (RequiredPermission.KeepAlive to PermissionState.Denied)
        assertEquals(RowCanReach, ready(ProfileUiState(permissions = bootUnknown))[4].value)
        // 还没查过权限：不说能
        assertEquals(RowCannotReach, ready(ProfileUiState(permissions = emptyMap()))[4].value)
    }

    @Test
    fun onlyPersonaAndProactiveAreClickable() {
        assertEquals(
            listOf(ProfileTarget.Settings, null, null, null, ProfileTarget.Permissions),
            ready().map { it.target },
        )
    }
}
