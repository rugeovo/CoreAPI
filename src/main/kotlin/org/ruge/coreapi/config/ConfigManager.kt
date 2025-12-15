package org.ruge.coreapi.config

import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration

/**
 * 配置管理器
 */
object ConfigManager {
    @Config("config.yml")
    lateinit var config: Configuration
        private set

    // 服务器配置
    val serverPort: Int
        get() = config.getInt("server.port", 8080)

    val serverEnabled: Boolean
        get() = config.getBoolean("server.enabled", true)

    // 调度器配置
    val maxQueueSize: Int
        get() = config.getInt("scheduler.max-queue-size", 500)

    val maxMsPerTick: Int
        get() = config.getInt("scheduler.max-ms-per-tick", 10)

    val taskTimeoutSeconds: Long
        get() = config.getLong("scheduler.task-timeout-seconds", 10)

    // 限流配置
    val rateLimitEnabled: Boolean
        get() = config.getBoolean("rate-limit.enabled", true)

    val rateLimitRequestsPerSecond: Double
        get() = config.getDouble("rate-limit.requests-per-second", 5.0)

    val rateLimitCacheExpireHours: Long
        get() = config.getLong("rate-limit.cache-expire-hours", 1)
}
