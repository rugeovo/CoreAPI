package org.ruge.coreapi.task

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 异步任务包装类
 * 将外部任务包装成可在主线程执行的异步任务
 *
 * ✅ Semaphore 泄漏修复：使用 AtomicBoolean 确保资源只被释放一次
 */
class AsyncTask<T>(
    private val task: Callable<T>,
    private val future: CompletableFuture<T>
) {
    // 任务状态标记：确保只执行一次 complete 操作
    private val completed = AtomicBoolean(false)

    /**
     * 执行任务并完成Future
     * @return true 如果成功执行并完成，false 如果任务已被取消/超时
     */
    fun execute(): Boolean {
        // 如果任务已经被取消/超时，不执行
        if (completed.get()) {
            return false
        }

        try {
            val result = task.call()
            // 使用 CAS 确保只完成一次
            if (completed.compareAndSet(false, true)) {
                future.complete(result)
                return true
            }
        } catch (e: Exception) {
            // 使用 CAS 确保只完成一次
            if (completed.compareAndSet(false, true)) {
                future.completeExceptionally(e)
                return true
            }
        }
        return false
    }

    /**
     * 取消任务（超时或队列满）
     * @return true 如果成功取消，false 如果任务已经开始执行
     */
    fun cancel(e: Exception): Boolean {
        // 使用 CAS 确保只完成一次
        if (completed.compareAndSet(false, true)) {
            future.completeExceptionally(e)
            return true
        }
        return false
    }

    /**
     * 任务失败（用于 processTick 中捕获的异常）
     */
    fun fail(e: Exception) {
        if (completed.compareAndSet(false, true)) {
            future.completeExceptionally(e)
        }
    }

    /**
     * 检查任务是否已完成
     */
    fun isDone(): Boolean = future.isDone
}
