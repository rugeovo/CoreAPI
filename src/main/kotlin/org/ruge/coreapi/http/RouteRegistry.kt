package org.ruge.coreapi.http

import org.bukkit.plugin.Plugin
import org.ruge.coreapi.lang.LanguageManager
import taboolib.common.platform.function.info
import java.util.concurrent.ConcurrentHashMap

/**
 * 路由信息
 */
data class RouteInfo(
    val path: String,
    val method: HttpMethod,
    val handler: RouteHandler,
    val plugin: Plugin,
    val requireAuth: Boolean = false,
    val permission: String? = null  // 自定义权限节点，null 表示使用默认生成的权限节点
)

/**
 * 路由注册表
 * 管理所有API路由
 */
class RouteRegistry {
    // 路由表：key = "METHOD:path", value = RouteInfo
    private val routes = ConcurrentHashMap<String, RouteInfo>()

    // 插件路由映射（用于插件卸载时清理）
    private val pluginRoutes = ConcurrentHashMap<Plugin, MutableList<String>>()

    /**
     * 注册路由
     *
     * @param plugin 注册路由的插件
     * @param path 路由路径（如 "/litesignin/checkin"）
     * @param method HTTP方法
     * @param handler 处理器
     * @param requireAuth 是否需要认证
     * @param permission 自定义权限节点（可选，默认为 coreapi.route.<plugin>.<path>）
     */
    fun registerRoute(
        plugin: Plugin,
        path: String,
        method: HttpMethod,
        handler: RouteHandler,
        requireAuth: Boolean = true,
        permission: String? = null
    ) {
        val normalizedPath = normalizePath(path)
        val routeKey = "${method}:${normalizedPath}"

        // 检查路由是否已存在
        if (routes.containsKey(routeKey)) {
            val existingRoute = routes[routeKey]!!
            throw IllegalStateException(
                "路由冲突: ${method} ${normalizedPath} 已被插件 ${existingRoute.plugin.name} 注册"
            )
        }

        // 注册路由
        val routeInfo = RouteInfo(normalizedPath, method, handler, plugin, requireAuth, permission)
        routes[routeKey] = routeInfo

        // 记录插件路由映射
        pluginRoutes.computeIfAbsent(plugin) { mutableListOf() }.add(routeKey)

        info(LanguageManager.getMessage("route.registered", method, normalizedPath, plugin.name))
    }

    /**
     * 便捷方法：注册GET路由
     */
    fun registerGet(
        plugin: Plugin,
        path: String,
        handler: RouteHandler,
        requireAuth: Boolean = true,
        permission: String? = null
    ) {
        registerRoute(plugin, path, HttpMethod.GET, handler, requireAuth, permission)
    }

    /**
     * 便捷方法：注册POST路由
     */
    fun registerPost(
        plugin: Plugin,
        path: String,
        handler: RouteHandler,
        requireAuth: Boolean = true,
        permission: String? = null
    ) {
        registerRoute(plugin, path, HttpMethod.POST, handler, requireAuth, permission)
    }

    /**
     * 便捷方法：注册PUT路由
     */
    fun registerPut(
        plugin: Plugin,
        path: String,
        handler: RouteHandler,
        requireAuth: Boolean = true,
        permission: String? = null
    ) {
        registerRoute(plugin, path, HttpMethod.PUT, handler, requireAuth,permission)
    }

    /**
     * 便捷方法：注册DELETE路由
     */
    fun registerDelete(
        plugin: Plugin,
        path: String,
        handler: RouteHandler,
        requireAuth: Boolean = true,
        permission: String? = null
    ) {
        registerRoute(plugin, path, HttpMethod.DELETE, handler, requireAuth, permission)
    }

    /**
     * 查找路由处理器
     */
    fun findHandler(path: String, method: HttpMethod): RouteHandler? {
        val normalizedPath = normalizePath(path)
        val routeKey = "${method}:${normalizedPath}"
        return routes[routeKey]?.handler
    }

    /**
     * 获取路由信息
     */
    fun getRouteInfo(path: String, method: HttpMethod): RouteInfo? {
        val normalizedPath = normalizePath(path)
        val routeKey = "${method}:${normalizedPath}"
        return routes[routeKey]
    }

    /**
     * 检查路由是否需要认证
     */
    fun requiresAuth(path: String, method: HttpMethod): Boolean {
        return getRouteInfo(path, method)?.requireAuth ?: false
    }

    /**
     * 注销插件的所有路由
     * 当插件被卸载时调用
     */
    fun unregisterPlugin(plugin: Plugin) {
        val routeKeys = pluginRoutes.remove(plugin) ?: return

        var count = 0
        for (routeKey in routeKeys) {
            routes.remove(routeKey)
            count++
        }

        if (count > 0) {
            info(LanguageManager.getMessage("route.plugin-routes-unregistered", plugin.name, count))
        }
    }

    /**
     * 获取所有路由
     */
    fun getAllRoutes(): List<RouteInfo> {
        return routes.values.toList()
    }

    /**
     * 规范化路径
     * - 确保以/开头
     * - 移除末尾的/
     * - 转为小写
     */
    private fun normalizePath(path: String): String {
        var normalized = path.trim()
        if (!normalized.startsWith("/")) {
            normalized = "/$normalized"
        }
        if (normalized.length > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length - 1)
        }
        return normalized.lowercase()
    }
}
