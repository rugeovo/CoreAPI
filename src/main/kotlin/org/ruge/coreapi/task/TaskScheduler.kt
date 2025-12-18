package org.ruge.coreapi.task

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.ruge.coreapi.util.TPSMonitor
import org.ruge.coreapi.lang.LanguageManager
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * 智能任务调度器 (Linus Redesigned)
 *
 * 核心哲学：
 * 1. 简单优于复杂：移除无效的"时间预算"计算。
 * 2. 暴露问题：不要隐藏慢任务，而是监控并报警。
 * 3. 保持流动：避免低TPS时的"死亡螺旋"（停止处理->队列积压->内存爆炸->TPS更低）。
 */
class TaskScheduler(
    private val plugin: Plugin,
    private val maxQueueSize: Int = 500,
    private val maxTasksPerTick: Int = 50,
    private val slowTaskThresholdMs: Int = 10,
    private val minTpsThreshold: Double = 12.0,
    private val taskTimeoutSeconds: Long = 10
) {
    // 任务队列
    private val taskQueue = ConcurrentLinkedQueue<AsyncTask<*>>()

    // 队列容量控制
    private val queueLimit = Semaphore(maxQueueSize)

    // 统计数据
    private val totalProcessed = AtomicInteger(0)
    private val totalDropped = AtomicInteger(0)
    private val totalTimeout = AtomicInteger(0)

    private var lastWarningTime = 0L
    private val startTime = System.currentTimeMillis()

    fun start() {
        // 每tick执行
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            processTick()
        }, 0L, 1L)

        // 统计日志
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            logStats()
        }, 0L, 100L)

        info(LanguageManager.getMessage("task.scheduler-started", maxQueueSize, maxTasksPerTick, minTpsThreshold))
    }

    private fun processTick() {
        // 0. 熔断机制 (Circuit Breaker)
        // 预热保护：启动后前30秒不检查TPS，避免因服务器启动卡顿导致的误报
        if (System.currentTimeMillis() - startTime > 30000) {
            // 仅在服务器严重卡顿 (TPS < 12) 时才停止处理任务，避免雪上加霜
            if (TPSMonitor.getTPS() < minTpsThreshold) {
                // 可选：如果长时间熔断，应该警告
                if (System.currentTimeMillis() - lastWarningTime > 5000) {
                    warning(LanguageManager.getMessage("task.server-lag", minTpsThreshold))
                    lastWarningTime = System.currentTimeMillis()
                }
                return
            }
        }

        // 1. 定量处理：每tick最多处理 N 个任务
        // 这保证了吞吐量，同时避免单tick处理数千个任务导致瞬时卡顿
        var processedCount = 0

        while (processedCount < maxTasksPerTick && !taskQueue.isEmpty()) {
            val task = taskQueue.poll() ?: break

            // 2. 看门狗监控 (Watchdog)
            val start = System.nanoTime()
            var executed = false
            try {
                // ✅ Semaphore 泄漏修复：execute() 返回是否真正执行
                executed = task.execute()
            } catch (e: Exception) {
                warning(LanguageManager.getMessage("task.execution-exception", e.message ?: "Unknown error"))
                e.printStackTrace()
                task.fail(e)
                executed = true  // 即使失败也算执行了
            } finally {
                // ✅ 修复：只有真正执行的任务才 release（未执行说明已在超时处理器中 release 了）
                if (executed) {
                    queueLimit.release()
                    processedCount++
                    totalProcessed.incrementAndGet()
                }
            }

            val durationNs = System.nanoTime() - start
            val durationMs = durationNs / 1_000_000.0

            // 3. 慢任务报警
            // 如果一个任务卡了主线程，必须让开发者知道
            if (durationMs > slowTaskThresholdMs && executed) {
                warning(LanguageManager.getMessage("task.slow-task", "%.2f".format(durationMs), slowTaskThresholdMs))
                // 这里可以考虑打印堆栈或任务信息来定位问题
            }
        }

        // 4. 积压警告
        val queueSize = taskQueue.size
        if (queueSize > maxQueueSize * 0.8 && System.currentTimeMillis() - lastWarningTime > 5000) {
            warning(LanguageManager.getMessage("task.queue-high-load", queueSize, maxQueueSize))
            lastWarningTime = System.currentTimeMillis()
        }
    }

    /**
     * 提交任务到主线程
     */
    fun <T> submitTask(task: Callable<T>): CompletableFuture<T> {
        if (!queueLimit.tryAcquire()) {
            totalDropped.incrementAndGet()
            return CompletableFuture.failedFuture(
                RejectedExecutionException("任务队列已满 ($maxQueueSize) - 系统繁忙")
            )
        }

        val future = CompletableFuture<T>()
        val asyncTask = AsyncTask(task, future)

        // ✅ Semaphore 泄漏修复：使用 cancel() 方法确保 semaphore 只被释放一次
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            // 尝试取消任务（如果任务已开始执行，cancel() 返回 false）
            if (asyncTask.cancel(TimeoutException("任务等待调度超时 (${taskTimeoutSeconds}s)"))) {
                // 成功取消：任务还在队列中或刚被 poll 但还未执行
                // 从队列中移除（如果还在队列中）
                taskQueue.remove(asyncTask)
                // 释放 semaphore（因为任务不会被执行了）
                queueLimit.release()
                totalTimeout.incrementAndGet()
                warning(LanguageManager.getMessage("task.task-timeout"))
            }
            // 如果 cancel() 返回 false，说明任务已经开始执行
            // semaphore 会在 processTick 的 finally 块中释放
        }, taskTimeoutSeconds * 20L)

        taskQueue.offer(asyncTask)
        return future
    }

    private fun logStats() {
        val processed = totalProcessed.getAndSet(0)
        val dropped = totalDropped.getAndSet(0)
        val timeout = totalTimeout.getAndSet(0)
        val queueSize = taskQueue.size

        if (processed > 0 || dropped > 0 || timeout > 0 || queueSize > 10) {
            val tps = TPSMonitor.getTPS()
            info(LanguageManager.getMessage("task.scheduler-stats", "%.1f".format(tps), processed, queueSize, dropped, timeout))
        }
    }

    fun getQueueSize(): Int = taskQueue.size
    fun getAvailableCapacity(): Int = queueLimit.availablePermits()
}
