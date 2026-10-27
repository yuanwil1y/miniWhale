package me.rerere.ai.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage

/**
 * 一次 agent 运行的输入。
 *
 * [messages] 的最后一条是助手消息时，从它继续：先处理它上面还没执行的工具调用（比如审批之后），
 * 再请求模型；否则新建一条助手消息。一次运行只产出这一条助手消息，其余消息只读，不会被改写。
 * [forceNewMessage] 为 true 时总是新建，最后一条助手消息只作为历史。
 */
data class AgentRequest(
    val provider: Provider<ProviderSetting>,
    val providerSetting: ProviderSetting,
    val messages: List<UIMessage>,
    val tools: List<Tool> = emptyList(),
    val stream: Boolean = true,
    /** 最多请求模型的次数 */
    val maxSteps: Int = 256,
    val retry: RetryPolicy = RetryPolicy.NONE,
    /** 不续写 [messages] 末尾的助手消息，总是新开一条（比如助手主动开口的一轮） */
    val forceNewMessage: Boolean = false,
)

/** 调用方在循环各个节点上的介入点 */
interface AgentHooks {
    /**
     * 每次请求模型之前调用，返回这一步实际发出的消息和参数，系统提示词、上下文裁剪、输入转换都在这里做。
     *
     * [history] 是本轮开始前就有的消息，整轮各步都是同一份；续写时被续写的那条也算历史（它本来就在会话里）。
     * [reply] 是本轮新开的助手消息（之前步骤的输出、工具调用和结果都在里面）；第一步还没有内容时，或者续写时，为 null。
     * 分开传是为了让调用方能把每轮注入、历史整理只用在历史上，本轮的输出原样接在后面，各步请求的结构才一致。
     * 返回的 [StepRequest.params] 里的 tools 会被换成 [AgentRequest.tools]，声明给模型的工具和能执行的工具始终是同一份。
     */
    suspend fun prepareStep(history: List<UIMessage>, reply: UIMessage?): StepRequest

    /**
     * 生成过程中每次发出更新之前调用，返回值只用于这一次 [AgentEvent.MessageUpdated]，不会写回循环内部。
     * 适合 think 标签这类要边生成边展示、等这一步结束才真正处理的转换；[newPartsFrom] 的含义同 [finishStep]。
     */
    suspend fun previewStep(message: UIMessage, newPartsFrom: Int): UIMessage = message

    /**
     * 每一步生成结束、执行工具之前调用，返回值作为这一步的结果。
     *
     * 下标 [newPartsFrom] 及之后的 part 是这一步新生成的；之前的来自更早的步骤，已经处理过一次。
     */
    suspend fun finishStep(message: UIMessage, newPartsFrom: Int): UIMessage = message
}

data class StepRequest(
    val messages: List<UIMessage>,
    val params: TextGenerationParams,
)

sealed interface AgentEvent {
    /** 本轮助手消息的最新内容，整次运行 id 不变 */
    data class MessageUpdated(val message: UIMessage) : AgentEvent

    /** 请求失败，即将重试；之后再收到 [MessageUpdated] 说明重试的请求已经返回内容 */
    data class Retrying(val attempt: RetryAttempt) : AgentEvent

    /** 运行结束，总是最后一个事件；出错时直接抛出异常，不会发出这个事件 */
    data class Finished(val message: UIMessage, val reason: AgentFinishReason) : AgentEvent
}

sealed interface AgentFinishReason {
    /** 模型最后一步没有再调用工具 */
    data object Completed : AgentFinishReason

    /** 有工具在等待用户审批；写入审批结果后，用这条消息再运行一次即可继续 */
    data class AwaitingApproval(val toolCallIds: Set<String>) : AgentFinishReason

    /** 达到 [AgentRequest.maxSteps]，最后一步调用的工具已经执行，但模型还没有回复 */
    data object StepLimitReached : AgentFinishReason
}

/**
 * 运行 agent 循环：请求模型 → 执行工具 → 再请求模型，直到模型不再调用工具、有工具需要审批或达到步数上限。
 *
 * 工具按调用顺序逐个执行，有任意一个需要审批时，同一批的工具都等审批后再一起执行。
 * 返回的 Flow 不切换调度器，Provider 请求和工具执行都在收集方的上下文里运行，需要时由调用方 flowOn。
 * 取消时直接抛出，最后一个 [AgentEvent.MessageUpdated] 就是取消前的状态，已经执行完的工具结果都在里面。
 */
fun runAgent(request: AgentRequest, hooks: AgentHooks): Flow<AgentEvent> = flow {
    require(request.maxSteps > 0) { "maxSteps must be positive" }
    AgentLoop(request, hooks, this).run()
}
