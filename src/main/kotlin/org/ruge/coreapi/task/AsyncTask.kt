package org.ruge.coreapi.task

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Callable

/**
 * 异步任务包装类
 * 将外部任务包装成可在主线程执行的异步任务
 */
class AsyncTask<T>(
    private val task: Callable<T>,
    private val future: CompletableFuture<T>
) {
    /**
     * 执行任务并完成Future
     */
    fun execute() {
        try {
            val result = task.call()
            future.complete(result)
        } catch (e: Exception) {
            future.completeExceptionally(e)
        }
    }

    /**
     * 任务失败
     */
    fun fail(e: Exception) {
        future.completeExceptionally(e)
    }

    /**
     * 检查任务是否已完成
     */
    fun isDone(): Boolean = future.isDone
}
