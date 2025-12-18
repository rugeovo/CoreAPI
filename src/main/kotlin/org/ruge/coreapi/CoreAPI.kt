package org.ruge.coreapi

import org.bukkit.Bukkit
import org.ruge.coreapi.auth.AuthManager
import org.ruge.coreapi.auth.AuthService
import org.ruge.coreapi.auth.JwtManager
import org.ruge.coreapi.auth.TokenParser
import org.ruge.coreapi.config.ConfigManager
import org.ruge.coreapi.http.*
import org.ruge.coreapi.lang.LanguageManager
import org.ruge.coreapi.listener.PluginListener
import org.ruge.coreapi.task.TaskScheduler
import org.ruge.coreapi.util.TPSMonitor
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning
import java.util.concurrent.CompletableFuture

object CoreAPI : Plugin() {

    // 核心组件
    private lateinit var taskScheduler: TaskScheduler
    private lateinit var routeRegistry: RouteRegistry
    private var rateLimitManager: RateLimitManager? = null
    private var authManager: AuthManager? = null
    private var httpServer: CoreHttpServer? = null

    // 认证组件
    private lateinit var jwtManager: JwtManager
    private lateinit var authService: AuthService

    override fun onEnable() {
        info("CoreAPI 正在启动...")

        // 启动TPS监控
        info("启动TPS监控...")
        Bukkit.getScheduler().scheduleSyncRepeatingTask(
            Bukkit.getPluginManager().getPlugin("CoreAPI")!!,
            TPSMonitor,
            100L,
            1L
        )

        // 初始化配置
        info("加载配置文件...")

        // 初始化任务调度器
        info("初始化任务调度器...")
        taskScheduler = TaskScheduler(
            plugin = Bukkit.getPluginManager().getPlugin("CoreAPI")!!,
            maxQueueSize = ConfigManager.maxQueueSize,
            maxTasksPerTick = ConfigManager.maxTasksPerTick,
            slowTaskThresholdMs = ConfigManager.slowTaskThresholdMs,
            minTpsThreshold = ConfigManager.minTpsThreshold,
            taskTimeoutSeconds = ConfigManager.taskTimeoutSeconds
        )
        taskScheduler.start()

        // 初始化路由注册表
        info("初始化路由注册表...")
        routeRegistry = RouteRegistry()

        // 注册插件卸载监听器（支持热重载）
        info("注册插件卸载监听器...")
        Bukkit.getPluginManager().registerEvents(
            PluginListener(routeRegistry),
            Bukkit.getPluginManager().getPlugin("CoreAPI")!!
        )

        // 初始化限流管理器
        if (ConfigManager.rateLimitEnabled) {
            info("初始化限流管理器...")
            rateLimitManager = RateLimitManager(
                requestsPerSecond = ConfigManager.rateLimitRequestsPerSecond,
                cacheExpireHours = ConfigManager.rateLimitCacheExpireHours
            )
        }

        // 初始化认证服务
        info("初始化认证服务...")
        initializeAuthServices()

        // 注册内置API路由
        registerBuiltinRoutes()

        // 启动HTTP服务器
        if (ConfigManager.serverEnabled) {
            info("启动HTTP服务器...")
            httpServer = CoreHttpServer(
                port = ConfigManager.serverPort,
                routeRegistry = routeRegistry,
                taskScheduler = taskScheduler,
                rateLimitManager = rateLimitManager,
                authManager = authManager,
                trustProxy = ConfigManager.serverTrustProxy,
                maxBodySize = ConfigManager.serverMaxBodySize,
                corsOrigin = ConfigManager.serverCorsOrigin
            )
            try {
                httpServer?.startServer()
                info("CoreAPI 启动成功！")
            } catch (e: Exception) {
                warning("HTTP服务器启动失败: ${e.message}")
                e.printStackTrace()
            }
        } else {
            info("HTTP服务器已禁用，CoreAPI 启动成功！")
        }
    }

    override fun onDisable() {
        info("CoreAPI 正在关闭...")

        // 停止HTTP服务器
        httpServer?.stopServer()

        info("CoreAPI 已关闭")
    }

    /**
     * 初始化认证服务
     */
    private fun initializeAuthServices() {
        info("初始化认证服务...")

        // 初始化JWT管理器
        info("初始化JWT管理器...")
        jwtManager = JwtManager(
            secretKey = ConfigManager.jwtSecret,
            expirationHours = ConfigManager.jwtExpirationHours
        )

        // 初始化认证服务
        info("初始化AuthMe认证服务...")
        authService = AuthService(
            jwtManager = jwtManager,
            maxLoginAttempts = ConfigManager.authMaxLoginAttempts,
            lockoutMinutes = ConfigManager.authLockoutMinutes
        )
        authService.initialize()

        // 初始化权限认证管理器
        info("初始化LuckPerms权限管理器...")
        val tokenParser = TokenParser(jwtManager)
        authManager = AuthManager(tokenParser)
        authManager!!.initialize()

        info("认证服务初始化完成")
    }

    /**
     * 注册内置API路由
     */
    private fun registerBuiltinRoutes() {
        val plugin = Bukkit.getPluginManager().getPlugin("CoreAPI")!!

        // POST /login - 用户登录
        routeRegistry.registerPost(plugin, "/login", object : SyncRouteHandler() {
            override fun handleSync(context: RequestContext): ApiResponse {
                try {
                    // 解析请求体
                    val body = context.body ?: return ApiResponse.error(LanguageManager.getMessage("api.empty-body"))
                    val gson = com.google.gson.Gson()
                    val request = try {
                        gson.fromJson(body, LoginRequest::class.java)
                    } catch (e: com.google.gson.JsonSyntaxException) {
                        return ApiResponse.error(LanguageManager.getMessage("api.json-syntax-error"))
                    } catch (e: com.google.gson.JsonParseException) {
                        return ApiResponse.error(LanguageManager.getMessage("api.json-parse-error"))
                    }

                    // 验证参数
                    if (request.username.isBlank()) {
                        return ApiResponse.error(LanguageManager.getMessage("api.username-empty"))
                    }
                    if (request.password.isBlank()) {
                        return ApiResponse.error(LanguageManager.getMessage("api.password-empty"))
                    }

                    // 获取客户端IP (安全)
                    val clientIp = context.clientIp

                    // 调用登录服务
                    val result = authService.login(request.username, request.password, clientIp)

                    return if (result.success) {
                        ApiResponse.success(
                            mapOf(
                                "token" to result.token,
                                "uuid" to result.uuid.toString(),
                                "username" to result.username
                            )
                        )
                    } else {
                        ApiResponse.error(result.error ?: LanguageManager.getMessage("api.login-failed"))
                    }
                } catch (e: IllegalArgumentException) {
                    return ApiResponse.error(LanguageManager.getMessage("api.param-error", e.message ?: ""))
                } catch (e: Exception) {
                    warning(LanguageManager.getMessage("auth.login-exception", e.message ?: ""))
                    return ApiResponse.error(LanguageManager.getMessage("api.login-service-exception"))
                }
            }
        }, requireAuth = false)

        // POST /register - 用户注册
        routeRegistry.registerPost(plugin, "/register", object : SyncRouteHandler() {
            override fun handleSync(context: RequestContext): ApiResponse {
                try {
                    // 解析请求体
                    val body = context.body ?: return ApiResponse.error(LanguageManager.getMessage("api.empty-body"))
                    val gson = com.google.gson.Gson()
                    val request = try {
                        gson.fromJson(body, RegisterRequest::class.java)
                    } catch (e: com.google.gson.JsonSyntaxException) {
                        return ApiResponse.error(LanguageManager.getMessage("api.json-syntax-error"))
                    } catch (e: com.google.gson.JsonParseException) {
                        return ApiResponse.error(LanguageManager.getMessage("api.json-parse-error"))
                    }

                    // 验证用户名
                    if (request.username.isBlank()) {
                        return ApiResponse.error(LanguageManager.getMessage("api.username-empty"))
                    }
                    if (request.username.length < 3) {
                        return ApiResponse.error(LanguageManager.getMessage("api.username-too-short"))
                    }
                    if (request.username.length > 16) {
                        return ApiResponse.error(LanguageManager.getMessage("api.username-too-long"))
                    }
                    if (!request.username.matches(Regex("^[a-zA-Z0-9_]+$"))) {
                        return ApiResponse.error(LanguageManager.getMessage("api.username-invalid-chars"))
                    }

                    // ✅ 安全修复：验证密码强度
                    val passwordValidation = validatePassword(request.password)
                    if (!passwordValidation.isValid) {
                        return ApiResponse.error(passwordValidation.error!!)
                    }

                    // 获取客户端IP (安全)
                    val clientIp = context.clientIp

                    // 调用注册服务
                    val result = authService.register(request.username, request.password, clientIp)

                    return if (result.success) {
                        ApiResponse.success(
                            mapOf(
                                "token" to result.token,
                                "uuid" to result.uuid.toString(),
                                "username" to result.username
                            )
                        )
                    } else {
                        ApiResponse.error(result.error ?: LanguageManager.getMessage("api.register-failed"))
                    }
                } catch (e: IllegalArgumentException) {
                    return ApiResponse.error(LanguageManager.getMessage("api.param-error", e.message ?: ""))
                } catch (e: Exception) {
                    warning(LanguageManager.getMessage("auth.register-exception", e.message ?: ""))
                    return ApiResponse.error(LanguageManager.getMessage("api.register-service-exception"))
                }
            }
        }, requireAuth = false)

        // GET /status - 服务器状态
        routeRegistry.registerGet(plugin, "/status", object : SyncRouteHandler() {
            override fun handleSync(context: RequestContext): ApiResponse {
                val tps = TPSMonitor.getTPS()
                val queueSize = taskScheduler.getQueueSize()
                val availableCapacity = taskScheduler.getAvailableCapacity()

                return ApiResponse.success(
                    mapOf(
                        "server" to "online",
                        "tps" to "%.2f".format(tps),
                        "queue_size" to queueSize,
                        "queue_capacity" to availableCapacity,
                        "online_players" to Bukkit.getOnlinePlayers().size
                    )
                )
            }
        }, requireAuth = true)

        // GET /routes - 路由列表
        routeRegistry.registerGet(plugin, "/routes", object : SyncRouteHandler() {
            override fun handleSync(context: RequestContext): ApiResponse {
                val routes = routeRegistry.getAllRoutes().map {
                    mapOf(
                        "method" to it.method.name,
                        "path" to it.path,
                        "plugin" to it.plugin.name,
                        "require_auth" to it.requireAuth
                    )
                }
                return ApiResponse.success(mapOf("routes" to routes))
            }
        }, requireAuth = true)


        info("内置路由已注册: /login, /register, /status, /routes")
    }

    // 请求数据类
    private data class LoginRequest(
        val username: String,
        val password: String
    )

    private data class RegisterRequest(
        val username: String,
        val password: String
    )

    /**
     * 公开API：供其他插件注册路由
     */
    fun getRouteRegistry(): RouteRegistry = routeRegistry

    /**
     * 公开API：供其他插件提交任务到主线程
     */
    fun <T> submitTask(task: java.util.concurrent.Callable<T>): CompletableFuture<T> {
        return taskScheduler.submitTask(task)
    }

    /**
     * 公开API：获取任务调度器
     */
    fun getTaskScheduler(): TaskScheduler = taskScheduler

    /**
     * 验证密码强度
     *
     * 密码要求：
     * - 至少 8 个字符
     * - 至少包含 1 个字母
     * - 至少包含 1 个数字
     * - 不能包含用户名
     *
     * @param password 待验证的密码
     * @return PasswordValidation 验证结果
     */
    private fun validatePassword(password: String): PasswordValidation {
        // 1. 长度检查
        if (password.length < 8) {
            return PasswordValidation.invalid(LanguageManager.getMessage("api.password-too-short"))
        }

        if (password.length > 128) {
            return PasswordValidation.invalid(LanguageManager.getMessage("api.password-too-long"))
        }

        // 2. 复杂度检查：必须包含字母
        if (!password.matches(Regex(".*[a-zA-Z].*"))) {
            return PasswordValidation.invalid(LanguageManager.getMessage("api.password-no-letter"))
        }

        // 3. 复杂度检查：必须包含数字
        if (!password.matches(Regex(".*[0-9].*"))) {
            return PasswordValidation.invalid(LanguageManager.getMessage("api.password-no-digit"))
        }

        // 4. 弱密码检查
        val commonWeakPasswords = listOf(
            "12345678", "password", "password123", "qwerty123", "abc123456",
            "admin123", "letmein123", "welcome123", "monkey123", "dragon123"
        )
        if (commonWeakPasswords.contains(password.lowercase())) {
            return PasswordValidation.invalid(LanguageManager.getMessage("api.password-too-weak"))
        }

        // 5. 连续字符检查（如 "11111111", "aaaaaaaa"）
        if (password.matches(Regex("(.)\\1{7,}"))) {
            return PasswordValidation.invalid(LanguageManager.getMessage("api.password-repeated-chars"))
        }

        return PasswordValidation.valid()
    }

    /**
     * 密码验证结果
     */
    private data class PasswordValidation(
        val isValid: Boolean,
        val error: String?
    ) {
        companion object {
            fun valid() = PasswordValidation(true, null)
            fun invalid(error: String) = PasswordValidation(false, error)
        }
    }
}
