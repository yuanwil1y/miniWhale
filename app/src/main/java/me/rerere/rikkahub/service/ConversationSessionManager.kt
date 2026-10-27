package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/** Owns runtime sessions; loading and message dispatch belong to ChatService, persistence goes through [ConversationStore]. */
class ConversationSessionManager(
    private val scope: CoroutineScope,
    private val store: ConversationStore,
    private val createInitialConversation: (Uuid) -> Conversation,
    private val onGenerationFinished: (ConversationSession, Throwable?) -> Unit,
    // 会话没有页面引用也不在生成，被回收了
    private val onSessionRemoved: (Uuid) -> Unit = {},
    /** 没有引用、也不在生成时仍不能回收的会话（他还有待发批次，W-022） */
    private val isRetained: (Uuid) -> Boolean = { false },
) {
    private val lock = Any()
    private val sessions = MutableStateFlow<Map<Uuid, ConversationSession>>(emptyMap())

    fun get(conversationId: Uuid): ConversationSession? = sessions.value[conversationId]

    fun snapshot(): List<ConversationSession> = sessions.value.values.toList()

    fun getOrCreate(conversationId: Uuid): ConversationSession = synchronized(lock) {
        get(conversationId)?.let { return it }
        lateinit var session: ConversationSession
        session = ConversationSession(
            id = conversationId,
            initial = createInitialConversation(conversationId),
            scope = scope,
            store = store,
            onIdle = { removeIfIdle(session) },
            isRetained = { isRetained(conversationId) },
            onGenerationFinished = { id, cause ->
                if (get(id) === session) onGenerationFinished(session, cause)
            },
        )
        // Publish the actual registry, so collectors never observe a version before insertion.
        sessions.value = sessions.value + (conversationId to session)
        session
    }

    fun acquire(conversationId: Uuid): ConversationSession = synchronized(lock) {
        getOrCreate(conversationId).also { it.acquire() }
    }

    fun release(conversationId: Uuid) {
        get(conversationId)?.release()
    }

    suspend fun <T> withSession(
        conversationId: Uuid,
        block: suspend (ConversationSession) -> T,
    ): T {
        val session = acquire(conversationId)
        try {
            return block(session)
        } finally {
            session.release()
        }
    }

    internal fun removeIfIdle(session: ConversationSession) {
        val removed = synchronized(lock) {
            if (get(session.id) !== session || session.isInUse) return
            sessions.value = sessions.value - session.id
            session
        }
        // Job completion can call back into the registry; never hold its lock during cleanup.
        removed.cleanup()
        onSessionRemoved(removed.id)
    }

    fun cleanup() {
        val removed = synchronized(lock) {
            snapshot().also { sessions.value = emptyMap() }
        }
        removed.forEach { it.cleanup() }
    }

    /**
     * 按会话 id 订阅内容，跨会话对象稳定（W-022）：会话被空闲回收、之后又新建一个对象时，跟着注册表换到新对象上，
     * 不会一直盯着已经回收的旧对象。只发加载完成之后的内容；还没有会话对象时什么都不发，也不替调用方新建
     * （以前在这里 getOrCreate，建出来的对象没人持有也没人加载，不会排空闲回收）。
     */
    fun getConversationFlow(conversationId: Uuid): Flow<Conversation> =
        sessions.map { it[conversationId] }.distinctUntilChanged().flatMapLatest { session ->
            session?.loadedState ?: emptyFlow()
        }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> =
        sessions.map { it[conversationId] }.distinctUntilChanged().flatMapLatest { session ->
            session?.generationJob ?: flowOf(null)
        }
}
