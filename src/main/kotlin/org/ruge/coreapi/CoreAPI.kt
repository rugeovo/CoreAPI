package org.ruge.coreapi

import org.bukkit.Bukkit
import org.ruge.coreapi.config.ConfigManager
import org.ruge.coreapi.http.*
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
    private var httpServer: CoreHttpServer? = null

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
            maxMsPerTick = ConfigManager.maxMsPerTick,
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

        // 注册内置API路由
        registerBuiltinRoutes()

        // 启动HTTP服务器
        if (ConfigManager.serverEnabled) {
            info("启动HTTP服务器...")
            httpServer = CoreHttpServer(
                port = ConfigManager.serverPort,
                routeRegistry = routeRegistry,
                rateLimitManager = rateLimitManager
            )
            try {
                httpServer?.startServer()
                info("CoreAPI 启动成功！")
            } catch (e: Exception) {
                warning("HTTP服务器启动失败: ${e.message}")
                e.printStackTrace()
            }
        } else {
            info("HTTP服务器已禁用（配置文件 server.enabled = false）")
        }
    }

    override fun onDisable() {
        info("CoreAPI 正在关闭...")

        // 停止HTTP服务器
        httpServer?.stopServer()

        info("CoreAPI 已关闭")
    }

    /**
     * 注册内置API路由
     */
    private fun registerBuiltinRoutes() {
        val plugin = Bukkit.getPluginManager().getPlugin("CoreAPI")!!

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
        }, requireAuth = false)

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
        }, requireAuth = false)

        info("内置路由已注册: /status, /routes")
    }

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
}
