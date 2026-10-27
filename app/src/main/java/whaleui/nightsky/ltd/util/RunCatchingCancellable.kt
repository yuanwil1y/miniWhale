package whaleui.nightsky.ltd.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * 和 [runCatching] 一样把异常装进 [Result]，但**协程取消（[CancellationException]）原样抛出**（W-052）。
 *
 * 在挂起上下文里用 `runCatching { 挂起调用 }` 会把取消也当成普通失败吞掉：协程已经被取消了，
 * 后面的代码却照样往下跑（弹「失败」提示、回滚状态、继续写库……）。挂起场景一律用这个；
 * 只包纯同步代码（解析字符串、打日志）的 `runCatching` 不用换。
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
