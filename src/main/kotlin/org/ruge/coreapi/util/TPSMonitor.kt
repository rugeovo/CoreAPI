package org.ruge.coreapi.util

/**
 * TPS监控工具
 * 自己实现TPS检测，不依赖Paper服务器
 */
object TPSMonitor : Runnable {
    private var tickCount = 0
    private val ticks = LongArray(600)
    private var lastTick = 0L

    /**
     * 获取TPS（默认最近100个tick）
     */
    fun getTPS(): Double = getTPS(100)

    /**
     * 获取指定tick数的TPS
     */
    fun getTPS(tickCount: Int): Double {
        if (this.tickCount < tickCount) {
            return 20.0
        }
        // 确保target始终为正数
        val target = ((this.tickCount - 1 - tickCount) % ticks.size + ticks.size) % ticks.size
        val elapsed = System.currentTimeMillis() - ticks[target]
        return tickCount / (elapsed / 1000.0)
    }

    /**
     * 获取指定tick到现在的经过时间
     */
    fun getElapsed(tickID: Int): Long {
        if (tickCount - tickID >= ticks.size) {
            return 0
        }
        val time = ticks[tickID % ticks.size]
        return System.currentTimeMillis() - time
    }

    /**
     * 每tick执行一次
     */
    override fun run() {
        ticks[tickCount % ticks.size] = System.currentTimeMillis()
        tickCount++
    }
}
