package whaleui.nightsky.ltd.data

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import whaleui.nightsky.ltd.domain.settings.HerProfile

class DataStoreHerProfileStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    private fun open(): DataStoreHerProfileStore {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val file = File(temp.root, "whale_settings.preferences_pb")
        return DataStoreHerProfileStore(
            PreferenceDataStoreFactory.create(
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file.toOkioPath() }),
                scope = scope,
            ),
        )
    }

    private fun close() = runBlocking {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        scopes.clear()
    }

    @After
    fun tearDown() = close()

    @Test
    fun `fresh store reads defaults`() = runBlocking {
        assertEquals(HerProfile(), open().profile.first())
    }

    @Test
    fun `saved values survive reopening`() {
        runBlocking { open().save("  小白  ", "/data/her_avatar/a.jpg") }
        close()
        val profile = runBlocking { open().profile.first() }
        assertEquals("小白", profile.nickname)
        assertEquals("/data/her_avatar/a.jpg", profile.avatarPath)
    }

    @Test
    fun `empty nickname saves as default and null avatar clears it`() = runBlocking {
        val store = open()
        store.save("名字", "/x.png")
        store.save("", null)
        val profile = store.profile.first()
        assertEquals(HerProfile.DEFAULT_NICKNAME, profile.nickname)
        assertNull(profile.avatarPath)
    }

    @Test
    fun `overlong nickname is clamped on save`() = runBlocking {
        val store = open()
        store.save("长".repeat(30), null)
        assertEquals("长".repeat(HerProfile.MAX_NICKNAME_LENGTH), store.profile.first().nickname)
    }
}
