package whaleui.nightsky.ltd.ui.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import whaleui.nightsky.ltd.domain.settings.AvatarImport
import whaleui.nightsky.ltd.domain.settings.HerAvatarStorage
import whaleui.nightsky.ltd.domain.settings.HerProfile
import whaleui.nightsky.ltd.domain.settings.HerProfileStore

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditViewModelTest {

    private class MemoryStore(initial: HerProfile = HerProfile()) : HerProfileStore {
        val state = MutableStateFlow(initial)
        var saves = 0
        override val profile: Flow<HerProfile> = state
        override suspend fun save(nickname: String, avatarPath: String?) {
            saves++
            state.value = HerProfile(HerProfile.normalizeNickname(nickname), avatarPath)
        }
    }

    private class MemoryAvatars : HerAvatarStorage {
        var next: AvatarImport = AvatarImport.Ok("/avatars/new.jpg")
        val retained = mutableListOf<String?>()
        override fun import(uri: String): AvatarImport = next
        override fun retainOnly(keep: String?) {
            retained += keep
        }
    }

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var store: MemoryStore
    private lateinit var avatars: MemoryAvatars

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = MemoryStore()
        avatars = MemoryAvatars()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ProfileEditViewModel(store, avatars, io = dispatcher).also { it.start() }

    @Test
    fun `default nickname shows as empty field with placeholder`() {
        val state = viewModel().uiState.value
        assertTrue(state.loaded)
        assertEquals("", state.nickname)
        assertNull(state.avatarPath)
        assertFalse(state.dirty)
        assertNull(state.remaining)
    }

    @Test
    fun `nickname is saved only on save`() {
        val vm = viewModel()
        vm.setNickname("小白")
        assertTrue(vm.uiState.value.dirty)
        assertEquals(0, store.saves)

        var done = false
        vm.save { done = true }
        assertTrue(done)
        assertEquals("小白", store.state.value.nickname)
    }

    @Test
    fun `empty nickname saves as default`() {
        store.state.value = HerProfile("小白", null)
        val vm = viewModel()
        assertEquals("小白", vm.uiState.value.nickname)
        vm.setNickname("")
        vm.save {}
        assertEquals(HerProfile.DEFAULT_NICKNAME, store.state.value.nickname)
    }

    @Test
    fun `input is clamped and counter appears near the limit`() {
        val vm = viewModel()
        vm.setNickname("字".repeat(14))
        assertNull(vm.uiState.value.remaining)
        vm.setNickname("字".repeat(15))
        assertEquals(5, vm.uiState.value.remaining)
        vm.setNickname("字".repeat(30))
        assertEquals(HerProfile.MAX_NICKNAME_LENGTH, HerProfile.lengthOf(vm.uiState.value.nickname))
        assertEquals(0, vm.uiState.value.remaining)
    }

    @Test
    fun `back without changes leaves immediately`() {
        val vm = viewModel()
        var left = false
        vm.requestBack { left = true }
        assertTrue(left)
        assertFalse(vm.uiState.value.confirmDiscard)
    }

    @Test
    fun `back with changes asks first and discard drops the picked file`() {
        val vm = viewModel()
        vm.pickAvatar("content://picked")
        assertEquals("/avatars/new.jpg", vm.uiState.value.avatarPath)

        var left = false
        vm.requestBack { left = true }
        assertFalse(left)
        assertTrue(vm.uiState.value.confirmDiscard)

        vm.dismissDiscard()
        assertFalse(vm.uiState.value.confirmDiscard)

        vm.requestBack { left = true }
        vm.discard { left = true }
        assertTrue(left)
        assertEquals(0, store.saves)
        assertEquals(listOf<String?>(null), avatars.retained)
    }

    @Test
    fun `saving a picked avatar keeps only that file`() {
        val vm = viewModel()
        vm.pickAvatar("content://picked")
        vm.save {}
        assertEquals("/avatars/new.jpg", store.state.value.avatarPath)
        assertEquals(listOf<String?>("/avatars/new.jpg"), avatars.retained)
    }

    @Test
    fun `reset avatar returns to the white default`() {
        store.state.value = HerProfile("小白", "/avatars/old.jpg")
        val vm = viewModel()
        vm.resetAvatar()
        assertTrue(vm.uiState.value.dirty)
        vm.save {}
        assertNull(store.state.value.avatarPath)
        assertEquals(listOf<String?>(null), avatars.retained)
    }

    @Test
    fun `rejected image shows a notice and keeps the draft`() {
        avatars.next = AvatarImport.Rejected("头像只能用 JPG、PNG 或 WebP 图片")
        val vm = viewModel()
        vm.pickAvatar("content://gif")
        assertEquals("头像只能用 JPG、PNG 或 WebP 图片", vm.uiState.value.notice)
        assertNull(vm.uiState.value.avatarPath)
        assertFalse(vm.uiState.value.busy)
        vm.consumeNotice()
        assertNull(vm.uiState.value.notice)
    }
}
