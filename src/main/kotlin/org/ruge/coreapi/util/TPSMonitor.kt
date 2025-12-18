package org.ruge.coreapi.util

import java.util.concurrent.atomic.AtomicInteger

/**
 * TPS 监控工具
 * 
 * 线程安全的 TPS 计算器，支持多个时间窗口：
 * - 1 秒（20 ticks）
 * - 5 秒（100 ticks）
 * - 1 分钟（1200 ticks）
 * - 5 分钟（6000 ticks）
 * - 15 分钟（18000 ticks）
 * 
 * 使用环形缓冲区存储最近 18000 个 tick 的时间戳（约 15 分钟）
 */
object TPSMonitor : Runnable {
    // 环形缓冲区大小：18000 ticks = 15 分钟（理想情况下）
    private const val BUFFER_SIZE = 18000
    
    // 时间戳环形缓冲区
    private val tickTimestamps = LongArray(BUFFER_SIZE)
    
    // 当前写入位置（使用 AtomicInteger 保证线程安全）
    private val currentIndex = AtomicInteger(0)
    
    // 已记录的 tick 总数（用于冷启动判断）
    @Volatile
    private var totalTicks = 0L
    
    /**
     * 获取 TPS（默认 5 秒窗口）
     */
    fun getTPS(): Double = getTPS(100)
    
    /**
     * 获取指定 tick 数的平均 TPS
     * 
     * @param ticks 时间窗口大小（tick 数）
     * @return 平均 TPS，如果数据不足则返回估算值
     */
    fun getTPS(ticks: Int): Double {
        require(ticks > 0) { "ticks 必须大于 0" }
        
        // 冷启动保护：如果记录的 tick 数不足，返回估算值
        if (totalTicks < ticks) {
            return if (totalTicks < 2) {
                20.0 // 数据不足，返回理想值
            } else {
                // 使用已有数据估算
                calculateTPS(totalTicks.toInt())
            }
        }
        // 限制窗口大小不超过缓冲区
        val windowSize = minOf(ticks, BUFFER_SIZE)
        return calculateTPS(windowSize)
    }
    
    /**
     * 获取 1 秒平均 TPS（20 ticks）
     */
    fun getTPS1s(): Double = getTPS(20)
    
    /**
     * 获取 5 秒平均 TPS（100 ticks）
     */
    fun getTPS5s(): Double = getTPS(100)
    
    /**
     * 获取 1 分钟平均 TPS（1200 ticks）
     */
    fun getTPS1m(): Double = getTPS(1200)
    
    /**
     * 获取 5 分钟平均 TPS（6000 ticks）
     */
    fun getTPS5m(): Double = getTPS(6000)
    
    /**
     * 获取 15 分钟平均 TPS（18000 ticks）
     */
    fun getTPS15m(): Double = getTPS(18000)
    
    /**
     * 计算指定窗口大小的 TPS
     */
    private fun calculateTPS(windowSize: Int): Double {
        val current = currentIndex.get()
        val currentTime = System.currentTimeMillis()
        
        // 计算起始位置（环形缓冲区）
        val startIndex = (current - windowSize + BUFFER_SIZE) % BUFFER_SIZE
        val startTime = tickTimestamps[startIndex]
        
        // 防止除零
        if (startTime == 0L) {
            return 20.0
        }
        
        // 计算时间差（毫秒）
        val elapsedMs = currentTime - startTime
        
        // 防止时间倒流或异常
        if (elapsedMs <= 0) {
            return 20.0
        }
        
        // TPS = tick数 / 秒数
        return windowSize / (elapsedMs / 1000.0)
    }
    
    /**
     * 每 tick 调用一次（由 Bukkit 调度器执行）
     */
    override fun run() {
        // 获取当前索引并原子性递增
        val index = currentIndex.getAndIncrement() % BUFFER_SIZE
        
        // 记录当前时间戳
        tickTimestamps[index] = System.currentTimeMillis()
        
        // 更新总 tick 数（仅在未达到缓冲区大小时递增）
        if (totalTicks < BUFFER_SIZE) {
            totalTicks++
        }
    }
    
    /**
     * 获取已记录的 tick 总数
     */
    fun getTotalTicks(): Long = totalTicks
    
    /**
     * 重置监控器（用于测试或重新初始化）
     */
    fun reset() {
        currentIndex.set(0)
        totalTicks = 0
        tickTimestamps.fill(0)
    }
}
