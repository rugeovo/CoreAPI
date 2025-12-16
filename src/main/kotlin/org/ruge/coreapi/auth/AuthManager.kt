package org.ruge.coreapi.auth

import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.model.user.User
import org.bukkit.Bukkit
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
     */
    fun initialize() {
        try {
            // 检查 LuckPerms 插件是否存在
            val luckPermsPlugin = Bukkit.getPluginManager().getPlugin("LuckPerms")
            if (luckPermsPlugin == null || !luckPermsPlugin.isEnabled) {
                warning("LuckPerms 插件未安装或未启用，权限认证功能已禁用")
                enabled = false
                return
            }

            // 获取 LuckPerms API
            luckPerms = LuckPermsProvider.get()
            enabled = true
            info("LuckPerms 权限认证已启用")

        } catch (e: Exception) {
            warning("初始化 LuckPerms API 失败: ${e.message}")
            warning("权限认证功能已禁用")
            enabled = false
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
        // 如果权限系统未启用，直接通过
        if (!enabled) {
            return AuthResult.success(null)
        }

        // ✅ 安全修复：验证 JWT 签名和有效性
        val payload = tokenParser.validateJwt(token)
        if (payload == null) {
            return AuthResult.failure("无效或已过期的 token")
        }

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
     * @param uuid 用户 UUID
     * @param permission 权限节点
     * @return AuthResult 认证结果
     */
    fun checkPermission(uuid: UUID, permission: String): AuthResult {
        if (!enabled || luckPerms == null) {
            return AuthResult.success(uuid)
        }

        try {
            // 加载用户数据
            val userFuture = luckPerms!!.userManager.loadUser(uuid)
            val user: User = userFuture.join()
                ?: return AuthResult.failure("用户 $uuid 不存在")

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
            warning("检查权限时发生错误: ${e.message}")
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
