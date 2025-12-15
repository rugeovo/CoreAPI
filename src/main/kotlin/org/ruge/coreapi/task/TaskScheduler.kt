package org.ruge.coreapi.task

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.ruge.coreapi.util.TPSMonitor
import taboolib.common.platform.function.warning
import taboolib.common.platform.function.info
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * 智能任务调度器
 * 核心功能：在不影响游戏TPS的前提下，尽可能快地处理API请求
 *
 * 关键设计：
 * 1. 每tick限时处理（默认最多10ms）
 * 2. 根据服务器TPS动态调整处理时间
 * 3. 队列限制，防止内存溢出
 * 4. 任务超时自动失败
 */
class TaskScheduler(
    private val plugin: Plugin,
    private val maxQueueSize: Int = 500,
    private val maxMsPerTick: Int = 10,
    private val taskTimeoutSeconds: Long = 10
) {
    // 任务队列
    private val taskQueue = ConcurrentLinkedQueue<AsyncTask<*>>()

    // 队列容量控制（Semaphore保证线程安全）
    private val queueLimit = Semaphore(maxQueueSize)

    // 统计数据
    private val totalProcessed = AtomicInteger(0)
    private val totalDropped = AtomicInteger(0)
    private val totalTimeout = AtomicInteger(0)

    // 上次警告时间（避免日志刷屏）
    private var lastWarningTime = 0L

    /**
     * 启动调度器
     * 每tick执行一次任务处理
     */
    fun start() {
        // 每tick执行（0延迟，1tick间隔）
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            processTick()
        }, 0L, 1L)

        // 每5秒输出统计日志（100 ticks = 5秒）
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            logStats()
        }, 0L, 100L)

        info("任务调度器已启动 - 队列容量: $maxQueueSize, 每tick预算: ${maxMsPerTick}ms")
    }

    /**
     * 每tick执行的核心处理逻辑
     */
    private fun processTick() {
        // 1. 检查服务器TPS，计算本tick的时间预算
        val budget = calculateBudget()

        if (budget == 0) {
            // 服务器太卡，这个tick跳过API处理
            return
        }

        // 2. 开始处理任务队列
        val startTime = System.nanoTime()
        val deadline = startTime + (budget * 1_000_000L) // 转换为纳秒
        var processed = 0

        while (!taskQueue.isEmpty()) {
            // 检查时间：如果快到deadline了，停止处理
            val now = System.nanoTime()
            if (now >= deadline) {
                break // 时间用完，留给下个tick
            }

            // 取出任务
            val task = taskQueue.poll() ?: break

            // 执行任务
            try {
                task.execute()
                processed++
            } catch (e: Exception) {
                warning("任务执行失败: ${e.message}")
                task.fail(e)
            } finally {
                queueLimit.release() // 归还队列容量
            }
        }

        if (processed > 0) {
            totalProcessed.addAndGet(processed)
        }

        // 3. 检查队列积压情况
        val queueSize = taskQueue.size
        if (queueSize > 100 && System.currentTimeMillis() - lastWarningTime > 5000) {
            warning("API任务队列积压: $queueSize 个任务等待处理")
            lastWarningTime = System.currentTimeMillis()
        }
    }

    /**
     * 根据服务器TPS计算本tick可用的时间预算
     *
     * TPS阈值说明（基于Minecraft服务器特性）：
     * - Minecraft目标TPS为20.0（每tick 50ms）
     * - TPS < 18.0：严重卡顿（每tick > 55.6ms），Bukkit主线程已经过载
     *   → 预算 0ms：停止API处理，避免雪上加霜
     * - TPS < 19.0：轻微卡顿（每tick > 52.6ms），主线程有压力
     *   → 预算 3ms：仅处理紧急请求，减轻负担
     * - TPS < 19.5：正常偏低（每tick > 51.3ms），略有性能损耗
     *   → 预算 7ms：适度处理，保持服务可用
     * - TPS >= 19.5：流畅运行（每tick ≈ 50ms），主线程健康
     *   → 预算 maxMsPerTick：使用完整预算，最大化处理能力
     *
     * 根据TPS动态调整：
     * - TPS越低，预算越少（甚至为0）
     * - 确保API处理不会拖累游戏性能
     */
    private fun calculateBudget(): Int {
        val tps = TPSMonitor.getTPS()

        return when {
            tps < 18.0 -> 0   // 严重卡顿，停止处理API
            tps < 19.0 -> 3   // 轻微卡顿，减少到3ms
            tps < 19.5 -> 7   // 正常偏低，使用7ms
            else -> maxMsPerTick // 流畅，使用完整预算
        }
    }

    /**
     * 提交任务到队列
     *
     * @param task 要执行的任务
     * @return CompletableFuture，可用于等待结果或异步处理
     */
    fun <T> submitTask(task: Callable<T>): CompletableFuture<T> {
        // 尝试获取队列容量
        if (!queueLimit.tryAcquire()) {
            totalDropped.incrementAndGet()
            return CompletableFuture.failedFuture(
                RejectedExecutionException("服务器繁忙，任务队列已满（${maxQueueSize}个任务），请稍后重试")
            )
        }

        val future = CompletableFuture<T>()

        // 设置超时保护
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!future.isDone) {
                future.completeExceptionally(
                    TimeoutException("任务超时（${taskTimeoutSeconds}秒内未处理）")
                )
                queueLimit.release() // 归还容量
                totalTimeout.incrementAndGet()
            }
        }, taskTimeoutSeconds * 20L) // 转换为ticks

        // 添加到队列
        taskQueue.offer(AsyncTask(task, future))

        return future
    }

    /**
     * 输出统计日志
     */
    private fun logStats() {
        val queueSize = taskQueue.size
        val tps = TPSMonitor.getTPS()
        val processed = totalProcessed.getAndSet(0)
        val dropped = totalDropped.getAndSet(0)
        val timeout = totalTimeout.getAndSet(0)

        if (processed > 0 || dropped > 0 || timeout > 0 || queueSize > 10) {
            info(
                "API统计 - TPS: %.1f | 队列: %d | 已处理: %d | 已拒绝: %d | 已超时: %d"
                    .format(tps, queueSize, processed, dropped, timeout)
            )
        }
    }

    /**
     * 获取当前队列大小
     */
    fun getQueueSize(): Int = taskQueue.size

    /**
     * 获取剩余队列容量
     */
    fun getAvailableCapacity(): Int = queueLimit.availablePermits()
}
