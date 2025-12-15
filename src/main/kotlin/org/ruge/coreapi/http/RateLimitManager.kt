package org.ruge.coreapi.http

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.google.common.util.concurrent.RateLimiter
import java.util.concurrent.TimeUnit

/**
 * Rate Limiter（限流器）
 * 基于IP地址进行限流，防止API被刷爆
 */
class RateLimitManager(
    private val requestsPerSecond: Double = 5.0,
    private val cacheExpireHours: Long = 1
) {
    // IP限流器缓存：IP -> RateLimiter
    // 每个IP独立的限流器
    private val limiters: LoadingCache<String, RateLimiter> = CacheBuilder.newBuilder()
        .expireAfterAccess(cacheExpireHours, TimeUnit.HOURS)
        .build(object : CacheLoader<String, RateLimiter>() {
            override fun load(key: String): RateLimiter {
                return RateLimiter.create(requestsPerSecond)
            }
        })

    /**
     * 尝试获取许可（非阻塞）
     *
     * @param ip 客户端IP
     * @return true=允许通过, false=已超过限流
     */
    fun tryAcquire(ip: String): Boolean {
        val limiter = limiters.get(ip)
        return limiter.tryAcquire()
    }

    /**
     * 获取IP的剩余配额（每秒令牌数）
     */
    fun getRemainingQuota(ip: String): Double {
        return limiters.get(ip).rate
    }

    /**
     * 清理过期的限流器
     */
    fun cleanup() {
        limiters.cleanUp()
    }

    /**
     * 获取当前缓存的IP数量
     */
    fun getCachedIpCount(): Long {
        return limiters.size()
    }
}
