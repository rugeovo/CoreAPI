package org.ruge.coreapi.config

import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration

/**
 * 配置管理器
 * ✅ 性能优化：使用 lazy 初始化缓存配置值
 */
object ConfigManager {
    @Config("config.yml")
    lateinit var config: Configuration
        private set

    // 服务器配置 - 使用 lazy 缓存
    val serverPort: Int by lazy { config.getInt("server.port", 8080) }
    val serverEnabled: Boolean by lazy { config.getBoolean("server.enabled", true) }
    val serverTrustProxy: Boolean by lazy { config.getBoolean("server.trust-proxy", false) }
    val serverMaxBodySize: Long by lazy { config.getLong("server.max-body-size-bytes", 1048576) }
    val serverCorsOrigin: String by lazy { config.getString("server.cors-origin", "none")!! }

    // 调度器配置 - 使用 lazy 缓存
    val maxQueueSize: Int by lazy { config.getInt("scheduler.max-queue-size", 500) }
    val maxTasksPerTick: Int by lazy { config.getInt("scheduler.max-tasks-per-tick", 50) }
    val slowTaskThresholdMs: Int by lazy { config.getInt("scheduler.slow-task-threshold-ms", 10) }
    val minTpsThreshold: Double by lazy { config.getDouble("scheduler.min-tps-threshold", 12.0) }
    val taskTimeoutSeconds: Long by lazy { config.getLong("scheduler.task-timeout-seconds", 10) }

    // 限流配置 - 使用 lazy 缓存
    val rateLimitEnabled: Boolean by lazy { config.getBoolean("rate-limit.enabled", true) }
    val rateLimitRequestsPerSecond: Double by lazy { config.getDouble("rate-limit.requests-per-second", 5.0) }
    val rateLimitCacheExpireHours: Long by lazy { config.getLong("rate-limit.cache-expire-hours", 1) }

    // JWT 认证配置 - 使用 lazy 缓存
    val jwtSecret: String by lazy { config.getString("jwt.secret", "CHANGE-THIS-TO-A-RANDOM-SECRET-KEY-AT-LEAST-32-CHARS-LONG")!! }
    val jwtExpirationHours: Int by lazy { config.getInt("jwt.expiration-hours", 24) }

    // 登录安全配置 - 使用 lazy 缓存
    val authMaxLoginAttempts: Int by lazy { config.getInt("auth.max-login-attempts", 5) }
    val authLockoutMinutes: Int by lazy { config.getInt("auth.lockout-minutes", 15) }
}
