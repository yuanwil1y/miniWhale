package me.rerere.rikkahub.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

class SettingsStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    // 这些测试靠卡住读写来排时序，出问题时表现为一直挂着
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private var opened: OpenedStore? = null

    @After
    fun tearDown() {
        runBlocking { opened?.close() }
    }

    // 同一个文件同一时间只能有一个 DataStore，重新打开前先关掉上一个
    private suspend fun open(loadGate: CompletableDeferred<Unit>? = null): OpenedStore {
        opened?.close()
        return OpenedStore(File(temp.root, "settings.preferences_pb"), loadGate).also { opened = it }
    }

    private suspend fun reopened(): Settings = open().store.awaitLoaded()

    @Test
    fun `typing faster than the disk can write loses no keystroke`() = runBlocking {
        val opened = open()
        val store = opened.store
        store.update { it.copy(titlePrompt = "") }
        opened.disk.writeDelayMillis = 20

        val typing = "hello world".map { char ->
            // 和输入框一样：在当前显示的值后面追加，不等上一次写盘
            val shown = store.settingsFlow.value.titlePrompt
            launch(start = CoroutineStart.UNDISPATCHED) {
                store.update { it.copy(titlePrompt = shown + char) }
            }.also { delay(5) }
        }
        typing.joinAll()

        assertEquals("hello world", store.settingsFlow.value.titlePrompt)
        assertEquals("hello world", reopened().titlePrompt)
    }

    @Test
    fun `concurrent updates are all applied`() = runBlocking {
        val store = open().store
        store.update { it.copy(webServerPort = 0) }

        (1..8).map {
            launch(Dispatchers.Default) {
                repeat(50) { store.update { it.copy(webServerPort = it.webServerPort + 1) } }
            }
        }.joinAll()

        assertEquals(400, store.settingsFlow.value.webServerPort)
        assertEquals(400, reopened().webServerPort)
    }

    @Test
    fun `changes made during a slow write are flushed in a single write`() = runBlocking {
        val opened = open()
        val store = opened.store
        store.awaitLoaded()
        val gate = CompletableDeferred<Unit>()
        opened.disk.writeGate = gate

        val first = launch(start = CoroutineStart.UNDISPATCHED) { store.update { it.copy(webServerPort = 9001) } }
        while (opened.disk.writes.get() == 0) delay(1)
        val rest = (9002..9050).map { port ->
            launch(start = CoroutineStart.UNDISPATCHED) { store.update { it.copy(webServerPort = port) } }
        }
        gate.complete(Unit)
        (rest + first).joinAll()

        assertEquals(2, opened.disk.writes.get())
        assertEquals(9050, store.settingsFlow.value.webServerPort)
        assertEquals(9050, reopened().webServerPort)
    }

    @Test
    fun `fields that did not change are not rewritten`() = runBlocking {
        val opened = open()
        val store = opened.store
        store.update { it.copy(titlePrompt = "first") }
        // 绕过 store 直接改盘上的值：之后的写入如果把没变的字段也重写一遍，它就会被内存里的值盖回去
        opened.disk.edit { it[SettingsStore.OCR_PROMPT] = "on disk" }

        store.update { it.copy(titlePrompt = "second") }

        val stored = opened.disk.data.first()
        assertEquals("second", stored[SettingsStore.TITLE_PROMPT])
        assertEquals("on disk", stored[SettingsStore.OCR_PROMPT])
    }

    @Test
    fun `values repaired while loading are written back by the next update`() = runBlocking {
        // 盘上留着一个越界的下标，读盘时会被收回 0。它必须跟着下一次写入落盘，
        // 否则以后搜索服务变多、这个下标重新落在范围内时，重启后选中的服务会悄悄变掉
        open().disk.edit { it[SettingsStore.SEARCH_SELECTED] = 3 }
        val opened = open()
        val store = opened.store
        assertEquals(0, store.awaitLoaded().searchServiceSelected)

        store.update { it.copy(titlePrompt = "anything") }

        assertEquals(0, opened.disk.data.first()[SettingsStore.SEARCH_SELECTED])
    }

    @Test
    fun `legacy provider types survive loading and writing back`() = runBlocking {
        // B2：Claude / Google / ChatGPT / Grok 的协议实现删了，旧设置里的这类供应商必须读得进来、写回去不变
        open().disk.edit { it[SettingsStore.PROVIDERS] = LegacyProviderSettingCompatTest.LEGACY_PROVIDERS_JSON }
        val opened = open()
        val store = opened.store
        val loaded = store.awaitLoaded().providers
        assertEquals(
            LegacyProviderSettingCompatTest.LEGACY_PROVIDER_IDS,
            loaded.take(5).map { it.id.toString() },
        )

        store.update { it.copy(titlePrompt = "anything") }

        val original = JsonInstant.parseToJsonElement(LegacyProviderSettingCompatTest.LEGACY_PROVIDERS_JSON).jsonArray
        val onDisk = JsonInstant.parseToJsonElement(opened.disk.data.first()[SettingsStore.PROVIDERS]!!).jsonArray
        // 读盘时补上了内置供应商，所以这次确实整份重写过
        assertTrue(onDisk.size > original.size)
        original.forEach { before ->
            val id = before.jsonObject["id"]
            val after = onDisk.single { it.jsonObject["id"] == id }.jsonObject
            LegacyProviderSettingCompatTest.assertJsonObjectContains(before.jsonObject, after)
        }
        assertEquals(loaded.take(5), reopened().providers.take(5))
    }

    @Test
    fun `new install has no built-in Gemini or Claude-type providers`() = runBlocking {
        // B5：这两项的协议实现 B2 已删，新装不再出现
        val providers = open().store.awaitLoaded().providers
        assertTrue(providers.none { it.id.toString() in REMOVED_DEFAULT_IDS })
        assertTrue(providers.none { it is me.rerere.ai.provider.ProviderSetting.Google })
        assertTrue(providers.none { it is me.rerere.ai.provider.ProviderSetting.Claude })
        assertTrue("其它内置供应商照旧", providers.any { it.name == "DeepSeek" })
    }

    @Test
    fun `old built-in Gemini and Claude-type entries are kept and written back unchanged`() = runBlocking {
        // 老版本按当时的默认值写进盘里的两项（用户还填过 Key、加过模型）
        val oldEntries = listOf<me.rerere.ai.provider.ProviderSetting>(
            me.rerere.ai.provider.ProviderSetting.Google(
                id = Uuid.parse(REMOVED_DEFAULT_IDS[0]),
                name = "Gemini",
                apiKey = "gemini-user-key",
                enabled = true,
                models = listOf(me.rerere.ai.provider.Model(modelId = "gemini-2.5-flash", displayName = "Gemini 2.5 Flash")),
            ),
            me.rerere.ai.provider.ProviderSetting.Claude(
                id = Uuid.parse(REMOVED_DEFAULT_IDS[1]),
                name = "MiniMax",
                baseUrl = "https://api.minimaxi.com/anthropic/v1",
                apiKey = "",
                enabled = false,
            ),
        )
        val oldJson = JsonInstant.encodeToString(oldEntries)
        open().disk.edit { it[SettingsStore.PROVIDERS] = oldJson }
        val opened = open()
        val store = opened.store
        val loaded = store.awaitLoaded().providers
        assertEquals("读进来还在原来的位置", REMOVED_DEFAULT_IDS, loaded.take(2).map { it.id.toString() })
        assertEquals(oldJson, JsonInstant.encodeToString(loaded.take(2)))

        store.update { it.copy(titlePrompt = "anything") }

        val original = JsonInstant.parseToJsonElement(oldJson).jsonArray
        val onDisk = JsonInstant.parseToJsonElement(opened.disk.data.first()[SettingsStore.PROVIDERS]!!).jsonArray
        assertTrue("确实整份重写过（补上了内置供应商）", onDisk.size > original.size)
        original.forEachIndexed { index, before ->
            assertEquals("写回去逐字段不变", before, onDisk[index])
        }
        assertEquals(oldJson, JsonInstant.encodeToString(reopened().providers.take(2)))
    }

    @Test
    fun `objects that an update did not touch keep their identity`() = runBlocking {
        val store = open().store
        val before = store.awaitLoaded()

        store.update { it.copy(titlePrompt = "anything") }

        val after = store.settingsFlow.value
        assertSame(before.providers, after.providers)
        assertSame(before.assistants, after.assistants)
    }

    @Test
    fun `update issued before settings are loaded is applied on top of the stored settings`() = runBlocking {
        open().store.update { it.copy(titlePrompt = "custom") }

        val gate = CompletableDeferred<Unit>()
        val store = open(loadGate = gate).store
        val assistantId = DEFAULT_ASSISTANTS.last().id
        val update = launch(start = CoroutineStart.UNDISPATCHED) { store.selectAssistant(assistantId) }
        val loadedBeforeGate = !store.settingsFlow.value.init
        gate.complete(Unit)
        update.join()

        assertFalse(loadedBeforeGate)
        assertEquals("custom", store.settingsFlow.value.titlePrompt)
        assertEquals(assistantId, store.settingsFlow.value.assistantId)
        val stored = reopened()
        assertEquals("custom", stored.titlePrompt)
        assertEquals(assistantId, stored.assistantId)
    }

    @Test
    fun `first launch offers the built-in mode injection until the user removes it`() = runBlocking {
        val store = open().store
        assertEquals(DEFAULT_MODE_INJECTIONS, store.awaitLoaded().modeInjections)

        store.update { it.copy(modeInjections = emptyList()) }

        assertEquals(emptyList<Any>(), reopened().modeInjections)
    }

    @Test
    fun `settings are normalized as soon as they are updated`() = runBlocking {
        val store = open().store

        store.update {
            it.copy(providers = emptyList(), favoriteModels = listOf(Uuid.random()), searchServiceSelected = 5)
        }

        val settings = store.settingsFlow.value
        assertEquals(DEFAULT_PROVIDERS.map { it.id }, settings.providers.map { it.id })
        assertEquals(emptyList<Uuid>(), settings.favoriteModels)
        assertEquals(0, settings.searchServiceSelected)
    }

    @Test
    fun `change is still written when its caller is cancelled`() = runBlocking {
        val opened = open()
        val store = opened.store
        store.awaitLoaded()
        val gate = CompletableDeferred<Unit>()
        opened.disk.writeGate = gate

        // 页面关闭时 viewModelScope 里还没写完的修改
        val update = launch(start = CoroutineStart.UNDISPATCHED) { store.update { it.copy(titlePrompt = "kept") } }
        update.cancel()
        gate.complete(Unit)
        update.join()

        assertEquals("kept", reopened().titlePrompt)
    }
}

private class OpenedStore(file: File, loadGate: CompletableDeferred<Unit>?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // 显式走 OkioStorage：Okio 的 atomicMove 用 Files.move(REPLACE_EXISTING)，与 JDK 无关。
    // 不能用 create { file }（也不能用 createWithPath——它在 Android 变体里会转回 File 存储）：
    // JVM 单测里 SDK_INT == 0，DataStore 的 File 存储退回 File.renameTo，JDK 17 在 Windows 上
    // 目标已存在时 renameTo 返回 false →「File rename failed」（findings 附 2）。
    val disk = GatedDataStore(
        PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file.toOkioPath() }),
            scope = scope,
        ),
        loadGate,
    )
    val store = SettingsStore(disk, scope)

    suspend fun close() = scope.coroutineContext.job.cancelAndJoin()
}

// 可以卡住或拖慢读写的磁盘，用来确定地制造「内存已经改了、盘还没写完」的时刻
/** B5 从内置供应商里去掉的两项：Google 类型的 Gemini、Claude 类型的 MiniMax */
private val REMOVED_DEFAULT_IDS = listOf("6ab18148-c138-4394-a46f-1cd8c8ceaa6d", "b4deabea-20fb-4101-a74c-65679c7e4754")

private class GatedDataStore(
    private val delegate: DataStore<Preferences>,
    private val loadGate: CompletableDeferred<Unit>?,
) : DataStore<Preferences> {
    val writes = AtomicInteger()

    @Volatile
    var writeGate: CompletableDeferred<Unit>? = null

    @Volatile
    var writeDelayMillis = 0L

    override val data: Flow<Preferences> = flow {
        loadGate?.await()
        emitAll(delegate.data)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        writes.incrementAndGet()
        writeGate?.await()
        delay(writeDelayMillis)
        return delegate.updateData(transform)
    }
}
