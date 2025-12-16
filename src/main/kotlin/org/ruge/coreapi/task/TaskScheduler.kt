package org.ruge.coreapi.task

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.ruge.coreapi.util.TPSMonitor
import taboolib.common.platform.function.warning
import taboolib.common.platform.function.info
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

        info("任务调度器(Watchdog模式)已启动 - 队列: $maxQueueSize, 吞吐量: $maxTasksPerTick/tick, 熔断TPS: $minTpsThreshold")
    }

    private fun processTick() {
        // 0. 熔断机制 (Circuit Breaker)
        // 预热保护：启动后前30秒不检查TPS，避免因服务器启动卡顿导致的误报
        if (System.currentTimeMillis() - startTime > 30000) {
            // 仅在服务器严重卡顿 (TPS < 12) 时才停止处理任务，避免雪上加霜
            if (TPSMonitor.getTPS() < minTpsThreshold) {
                // 可选：如果长时间熔断，应该警告
                if (System.currentTimeMillis() - lastWarningTime > 5000) {
                    warning("⚠️ 服务器严重卡顿 (TPS < $minTpsThreshold) - 暂停处理 API 任务")
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
            try {
                task.execute()
            } catch (e: Exception) {
                warning("任务执行异常: ${e.message}")
                e.printStackTrace()
                task.fail(e)
            } finally {
                queueLimit.release()
                processedCount++
                totalProcessed.incrementAndGet()
            }
            
            val durationNs = System.nanoTime() - start
            val durationMs = durationNs / 1_000_000.0
            
            // 3. 慢任务报警
            // 如果一个任务卡了主线程，必须让开发者知道
            if (durationMs > slowTaskThresholdMs) {
                warning("⚠️ [主线程卡顿检测] 发现慢任务！耗时: %.2f ms (阈值: %d ms)".format(durationMs, slowTaskThresholdMs))
                // 这里可以考虑打印堆栈或任务信息来定位问题
            }
        }

        // 4. 积压警告
        val queueSize = taskQueue.size
        if (queueSize > maxQueueSize * 0.8 && System.currentTimeMillis() - lastWarningTime > 5000) {
            warning("⚠️ 任务队列高负载: $queueSize / $maxQueueSize - 请检查是否有插件在大量提交任务")
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

        // ✅ 安全修复：超时时释放资源
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!future.isDone) {
                // 1. 标记任务为超时失败
                future.completeExceptionally(TimeoutException("任务等待调度超时 (${taskTimeoutSeconds}s)"))

                // 2. 尝试从队列中移除任务（如果还在队列中）
                val removed = taskQueue.remove(asyncTask)

                // 3. 如果成功移除（任务还在队列中），释放 semaphore
                if (removed) {
                    queueLimit.release()
                    warning("任务超时并已从队列移除，资源已释放")
                } else {
                    // 任务已经被处理或正在处理，semaphore 会在 processTick 中释放
                    warning("任务超时，但已被调度执行")
                }

                totalTimeout.incrementAndGet()
            }
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
            info("调度统计 (TPS: %.1f) | 处理: %d | 积压: %d | 拒绝: %d | 超时: %d"
                .format(tps, processed, queueSize, dropped, timeout))
        }
    }

    fun getQueueSize(): Int = taskQueue.size
    fun getAvailableCapacity(): Int = queueLimit.availablePermits()
}
