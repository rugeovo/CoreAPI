package org.ruge.coreapi.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.ruge.coreapi.http.RouteRegistry
import org.ruge.coreapi.lang.LanguageManager
import taboolib.common.platform.function.info

/**
 * 插件事件监听器
 *
 * 职责：
 * - 监听插件卸载事件（PluginDisableEvent）
 * - 自动清理被卸载插件注册的所有路由
 * - 确保热重载时不会残留无效路由
 */
class PluginListener(
    private val routeRegistry: RouteRegistry
) : Listener {

    /**
     * 监听插件卸载事件
     *
     * 触发时机：
     * - 插件被 /reload 命令重载
     * - 插件被 PlugMan 等插件管理器卸载
     * - 服务器关闭时所有插件卸载
     */
    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        val plugin = event.plugin

        // 跳过 CoreAPI 自身
        // CoreAPI 卸载时会自动停止 HTTP 服务器，不需要额外处理
        if (plugin.name == "CoreAPI") {
            return
        }

        // 清理该插件注册的所有路由
        // RouteRegistry.unregisterPlugin() 会：
        // 1. 从路由表中移除所有该插件的路由
        // 2. 清理插件路由映射
        // 3. 输出日志记录清理的路由数量
        routeRegistry.unregisterPlugin(plugin)

        info(LanguageManager.getMessage("route.plugin-unloaded", plugin.name))
    }
}
