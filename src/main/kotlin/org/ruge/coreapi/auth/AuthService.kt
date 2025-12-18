package org.ruge.coreapi.auth

import com.google.common.cache.CacheBuilder
import com.google.common.cache.Cache
import com.google.common.util.concurrent.RateLimiter
import fr.xephi.authme.api.v3.AuthMeApi
import org.bukkit.Bukkit
import org.ruge.coreapi.lang.LanguageManager
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * 认证服务
 *
 * 职责：
 * - 集成 AuthMe API
 * - 处理用户登录和注册
 * - 生成和管理 JWT token
 * - 防暴力破解（登录失败次数限制）
 * - ✅ 登录/注册独立限流保护
 *
 * 安全特性：
 * - 密码由 AuthMe 加密存储
 * - JWT token 自动过期
 * - 登录失败次数限制（防暴力破解）
 * - IP 黑名单（临时锁定）
 * - 独立的登录/注册限流（更严格：1 req/s/IP）
 */
class AuthService(
    private val jwtManager: JwtManager,
    private val maxLoginAttempts: Int = 5,
    private val lockoutMinutes: Int = 15
) {
    private var authMeApi: AuthMeApi? = null
    private var enabled: Boolean = false

    // 登录失败次数记录：key = username, value = 失败次数
    private val loginAttempts: Cache<String, Int> = CacheBuilder.newBuilder()
        .expireAfterWrite(lockoutMinutes.toLong(), TimeUnit.MINUTES)
        .build()

    // IP 黑名单：key = IP地址, value = 锁定时间
    private val ipBlacklist: Cache<String, Long> = CacheBuilder.newBuilder()
        .expireAfterWrite(lockoutMinutes.toLong(), TimeUnit.MINUTES)
        .build()

    // ✅ 安全修复：登录/注册独立限流器
    // 每个 IP 独立限制：1 req/s（比全局限流的 5 req/s 更严格）
    private val authRateLimiters: ConcurrentHashMap<String, RateLimiter> = ConcurrentHashMap()

    /**
     * 初始化 AuthMe API
     */
    fun initialize() {
        try {
            // 检查 AuthMe 插件是否存在
            val authMePlugin = Bukkit.getPluginManager().getPlugin("AuthMe")
            if (authMePlugin == null || !authMePlugin.isEnabled) {
                warning(LanguageManager.getMessage("auth.authme-not-found"))
                enabled = false
                return
            }

            // 获取 AuthMe API
            authMeApi = AuthMeApi.getInstance()
            enabled = true
            info(LanguageManager.getMessage("auth.authme-enabled"))

        } catch (e: Exception) {
            warning(LanguageManager.getMessage("auth.authme-init-failed", e.message ?: "Unknown error"))
            warning(LanguageManager.getMessage("auth.authme-disabled"))
            enabled = false
        }
    }

    /**
     * 检查是否已启用
     */
    fun isEnabled(): Boolean = enabled

    /**
     * 检查认证接口限流
     * @return true=允许通过, false=限流中
     */
    private fun checkAuthRateLimit(clientIp: String): Boolean {
        val limiter = authRateLimiters.computeIfAbsent(clientIp) {
            RateLimiter.create(1.0) // 1 req/s/IP
        }
        return limiter.tryAcquire()
    }

    /**
     * 用户登录
     *
     * @param username 用户名
     * @param password 密码（明文）
     * @param clientIp 客户端 IP（用于防暴力破解）
     * @return LoginResult 登录结果
     */
    fun login(username: String, password: String, clientIp: String): LoginResult {
        if (!enabled || authMeApi == null) {
            return LoginResult.failure("认证服务未启用")
        }

        // ✅ 安全修复：检查登录接口限流（1 req/s/IP）
        if (!checkAuthRateLimit(clientIp)) {
            return LoginResult.failure("登录请求过于频繁，请1秒后重试")
        }

        // 检查 IP 是否被锁定
        if (ipBlacklist.getIfPresent(clientIp) != null) {
            return LoginResult.failure("IP 地址已被临时锁定，请 $lockoutMinutes 分钟后重试")
        }

        // ✅ 缓存过期竞态修复：先检查用户名锁定（不依赖 IP 锁定）
        val attempts = loginAttempts.getIfPresent(username) ?: 0
        if (attempts >= maxLoginAttempts) {
            // 已达到最大失败次数，拒绝登录
            return LoginResult.failure("登录失败次数过多，账户已被锁定 $lockoutMinutes 分钟")
        }

        // 检查用户是否存在
        if (!authMeApi!!.isRegistered(username)) {
            recordLoginFailure(username, clientIp)
            return LoginResult.failure("用户名或密码错误")
        }

        // 验证密码（直接调用 AuthMe API）
        if (!authMeApi!!.checkPassword(username, password)) {
            recordLoginFailure(username, clientIp)
            return LoginResult.failure("用户名或密码错误")
        }

        // 登录成功，清除失败记录
        loginAttempts.invalidate(username)

        // 获取玩家 UUID
        val uuid = Bukkit.getOfflinePlayer(username).uniqueId

        // 生成 JWT token（传入 UUID）
        val token = jwtManager.generateToken(uuid)

        return LoginResult.success(token, uuid, username)
    }

    /**
     * 用户注册
     *
     * @param username 用户名
     * @param password 密码（明文）
     * @param clientIp 客户端 IP
     * @return RegisterResult 注册结果
     */
    fun register(username: String, password: String, clientIp: String): RegisterResult {
        if (!enabled || authMeApi == null) {
            return RegisterResult.failure("认证服务未启用")
        }

        // ✅ 安全修复：检查注册接口限流（1 req/s/IP）
        if (!checkAuthRateLimit(clientIp)) {
            return RegisterResult.failure("注册请求过于频繁，请1秒后重试")
        }

        // 检查 IP 是否被锁定
        if (ipBlacklist.getIfPresent(clientIp) != null) {
            return RegisterResult.failure("IP 地址已被临时锁定")
        }

        // 检查用户是否已存在
        if (authMeApi!!.isRegistered(username)) {
            return RegisterResult.failure("用户名已存在")
        }

        // 注册用户（直接调用 AuthMe API）
        try {
            val success = authMeApi!!.registerPlayer(username, password)
            if (!success) {
                return RegisterResult.failure("注册失败，请稍后重试")
            }

            // 获取新注册用户的 UUID
            val uuid = Bukkit.getOfflinePlayer(username).uniqueId

            // 生成 JWT token（自动登录，传入 UUID）
            val token = jwtManager.generateToken(uuid)

            return RegisterResult.success(token, uuid, username)

        } catch (e: Exception) {
            warning(LanguageManager.getMessage("auth.register-failed-exception", e.message ?: "Unknown error"))
            return RegisterResult.failure("注册失败: ${e.message}")
        }
    }

    /**
     * 记录登录失败
     *
     * ✅ 缓存过期竞态修复：达到阈值时立即锁定 IP 并清除计数器
     */
    private fun recordLoginFailure(username: String, clientIp: String) {
        val attempts = (loginAttempts.getIfPresent(username) ?: 0) + 1
        loginAttempts.put(username, attempts)

        // 如果达到最大失败次数，立即锁定 IP 并清除计数器
        if (attempts >= maxLoginAttempts) {
            ipBlacklist.put(clientIp, System.currentTimeMillis())
            loginAttempts.invalidate(username)  // 清除计数器，避免缓存过期竞态
            warning(LanguageManager.getMessage("auth.login-locked", username, attempts, clientIp))
        }
    }

    /**
     * 获取登录失败次数
     */
    fun getLoginAttempts(username: String): Int {
        return loginAttempts.getIfPresent(username) ?: 0
    }

    /**
     * 检查 IP 是否被锁定
     */
    fun isIpLocked(clientIp: String): Boolean {
        return ipBlacklist.getIfPresent(clientIp) != null
    }
}

/**
 * 登录结果
 */
data class LoginResult(
    val success: Boolean,
    val token: String?,
    val uuid: UUID?,
    val username: String?,
    val error: String?
) {
    companion object {
        fun success(token: String, uuid: UUID, username: String): LoginResult {
            return LoginResult(
                success = true,
                token = token,
                uuid = uuid,
                username = username,
                error = null
            )
        }

        fun failure(error: String): LoginResult {
            return LoginResult(
                success = false,
                token = null,
                uuid = null,
                username = null,
                error = error
            )
        }
    }
}

/**
 * 注册结果
 */
data class RegisterResult(
    val success: Boolean,
    val token: String?,
    val uuid: UUID?,
    val username: String?,
    val error: String?
) {
    companion object {
        fun success(token: String, uuid: UUID, username: String): RegisterResult {
            return RegisterResult(
                success = true,
                token = token,
                uuid = uuid,
                username = username,
                error = null
            )
        }

        fun failure(error: String): RegisterResult {
            return RegisterResult(
                success = false,
                token = null,
                uuid = null,
                username = null,
                error = error
            )
        }
    }
}
