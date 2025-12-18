package org.ruge.coreapi.auth

import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.model.user.User
import org.bukkit.Bukkit
import org.ruge.coreapi.lang.LanguageManager
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning
import java.util.*

/**
 * 权限认证管理器
 *
 * 职责：
 * - 集成 LuckPerms API
 * - 验证用户权限
 * - 提供权限检查接口
 */
class AuthManager(
    private val tokenParser: TokenParser
) {

    private var luckPerms: LuckPerms? = null
    private var enabled: Boolean = false

    /**
     * 初始化 LuckPerms API
     * 
     * 支持延迟初始化：如果 LuckPerms 尚未加载，会在后续请求时自动重试
     */
    fun initialize() {
        tryInitializeLuckPerms()
    }
    
    /**
     * 尝试初始化 LuckPerms（支持延迟加载）
     */
    private fun tryInitializeLuckPerms(): Boolean {
        // 如果已经初始化成功，直接返回
        if (enabled && luckPerms != null) {
            return true
        }
        
        try {
            // 检查 LuckPerms 插件是否存在
            val luckPermsPlugin = Bukkit.getPluginManager().getPlugin("LuckPerms")
            if (luckPermsPlugin == null || !luckPermsPlugin.isEnabled) {
                // 首次检查时记录日志
                if (luckPerms == null) {
                    warning(LanguageManager.getMessage("auth.luckperms-not-found"))
                    warning(LanguageManager.getMessage("auth.luckperms-delayed-init"))
                }
                enabled = false
                return false
            }

            // 获取 LuckPerms API
            luckPerms = LuckPermsProvider.get()
            enabled = true
            info(LanguageManager.getMessage("auth.luckperms-enabled"))
            return true

        } catch (e: IllegalStateException) {
            // LuckPerms API 尚未注册（插件加载顺序问题）
            if (luckPerms == null) {
                warning(LanguageManager.getMessage("auth.luckperms-not-ready"))
                warning(LanguageManager.getMessage("auth.luckperms-retry"))
            }
            enabled = false
            return false
        } catch (e: Exception) {
            warning(LanguageManager.getMessage("auth.luckperms-init-failed", e.message ?: "Unknown error"))
            warning(LanguageManager.getMessage("auth.luckperms-disabled"))
            enabled = false
            return false
        }
    }

    /**
     * 检查是否已启用
     */
    fun isEnabled(): Boolean = enabled

    /**
     * 验证 token 并检查权限
     *
     * @param token 认证 token（从请求头获取）
     * @param permission 需要的权限节点
     * @return AuthResult 认证结果
     */
    fun authenticate(token: String?, permission: String): AuthResult {
        // 尝试延迟初始化 LuckPerms（处理加载顺序问题）
        if (!enabled) {
            tryInitializeLuckPerms()
        }
        
        // 如果权限系统仍未启用，直接通过
        if (!enabled) {
            return AuthResult.success(null)
        }

        // ✅ 安全修复：验证 JWT 签名和有效性
        val payload = tokenParser.validateJwt(token) ?: return AuthResult.failure("无效或已过期的 token")

        // 检查 token 是否过期
        if (payload.isExpired()) {
            return AuthResult.failure("token 已过期")
        }

        // 检查权限
        return checkPermission(payload.uuid, permission)
    }

    /**
     * 检查用户权限
     *
     * ✅ 性能修复：使用同步缓存查询，避免阻塞 HTTP 线程
     *
     * @param uuid 用户 UUID
     * @param permission 权限节点
     * @return AuthResult 认证结果
     */
    fun checkPermission(uuid: UUID, permission: String): AuthResult {
        // 再次确认 LuckPerms 可用（防止运行时被禁用）
        if (!enabled || luckPerms == null) {
            // 尝试重新初始化
            if (!tryInitializeLuckPerms()) {
                return AuthResult.success(uuid)
            }
        }

        try {
            // ✅ 性能优化：先尝试从缓存获取用户（快速路径）
            var user: User? = luckPerms!!.userManager.getUser(uuid)

            // 缓存未命中：同步加载用户数据（慢速路径，但必须成功）
            if (user == null) {
                try {
                    // 阻塞等待加载完成（罕见情况，用户体验优先于性能）
                    user = luckPerms!!.userManager.loadUser(uuid).get()
                } catch (e: Exception) {
                    warning(LanguageManager.getMessage("auth.luckperms-load-user-failed", uuid, e.message ?: "Unknown error"))
                    return AuthResult.failure("无法加载用户数据")
                }
            }

            // 获取用户的权限数据
            val permissionData = user.cachedData.permissionData

            // 检查权限（使用全局查询选项）
            val hasPermission = permissionData.checkPermission(permission).asBoolean()

            return if (hasPermission) {
                AuthResult.success(uuid)
            } else {
                AuthResult.failure("权限不足：缺少权限 $permission")
            }

        } catch (e: Exception) {
            warning(LanguageManager.getMessage("auth.luckperms-check-permission-failed", e.message ?: "Unknown error"))
            return AuthResult.failure("权限检查失败: ${e.message}")
        }
    }

    /**
     * 生成路由的权限节点
     *
     * 格式：coreapi.route.<plugin>.<path>
     * 例如：coreapi.route.litesignin.checkin
     *
     * @param pluginName 插件名
     * @param path 路由路径（如 /litesignin/checkin）
     * @return 权限节点
     */
    fun generatePermissionNode(pluginName: String, path: String): String {
        // 规范化路径：移除前导斜杠，替换斜杠为点，转小写
        val normalizedPath = path
            .trim()
            .removePrefix("/")
            .replace("/", ".")
            .lowercase()

        return "coreapi.route.${pluginName.lowercase()}.$normalizedPath"
    }
}

/**
 * 认证结果
 */
data class AuthResult(
    val success: Boolean,
    val uuid: UUID?,
    val error: String?
) {
    companion object {
        /**
         * 认证成功
         */
        fun success(uuid: UUID?): AuthResult {
            return AuthResult(success = true, uuid = uuid, error = null)
        }

        /**
         * 认证失败
         */
        fun failure(error: String): AuthResult {
            return AuthResult(success = false, uuid = null, error = error)
        }
    }
}
