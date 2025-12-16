package org.ruge.coreapi.http

import com.google.gson.Gson

/**
 * HTTP响应封装
 */
data class ApiResponse(
    val success: Boolean,
    val data: Any? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        private val gson = Gson()

        /**
         * 成功响应
         */
        fun success(data: Any? = null): ApiResponse {
            return ApiResponse(success = true, data = data)
        }

        /**
         * 错误响应
         */
        fun error(message: String): ApiResponse {
            return ApiResponse(success = false, error = message)
        }

        /**
         * 错误响应（带异常）
         */
        fun error(e: Exception): ApiResponse {
            return ApiResponse(success = false, error = e.message ?: "未知错误")
        }
    }

    /**
     * 转换为JSON字符串
     */
    fun toJson(): String {
        return gson.toJson(this)
    }
}

/**
 * HTTP方法枚举
 */
enum class HttpMethod {
    GET, POST, PUT, DELETE, OPTIONS
}

/**
 * HTTP请求上下文
 * 封装请求的所有信息
 */
class RequestContext(
    val method: HttpMethod,
    val uri: String,
    val headers: Map<String, String>,
    val params: Map<String, String>,
    private val bodyLoader: () -> String?,
    val clientIp: String
) {
    /**
     * 请求体（懒加载）
     * 只有在首次访问时才会读取流
     */
    val body: String? by lazy { bodyLoader() }

    /**
     * 获取请求头
     */
    fun getHeader(name: String): String? = headers[name.lowercase()]

    /**
     * 获取参数
     */
    fun getParam(name: String): String? = params[name]

    /**
     * 获取Authorization token
     */
    fun getAuthToken(): String? {
        val auth = getHeader("authorization") ?: return null
        if (auth.startsWith("Bearer ", ignoreCase = true)) {
            return auth.substring(7)
        }
        return null
    }
}

/**
 * 路由处理器接口
 */
interface RouteHandler {
    /**
     * 处理请求
     * 返回CompletableFuture支持异步处理
     */
    fun handle(context: RequestContext): java.util.concurrent.CompletableFuture<ApiResponse>
}

/**
 * 同步路由处理器（用于只读操作）
 */
abstract class SyncRouteHandler : RouteHandler {
    override fun handle(context: RequestContext): java.util.concurrent.CompletableFuture<ApiResponse> {
        return try {
            val response = handleSync(context)
            java.util.concurrent.CompletableFuture.completedFuture(response)
        } catch (e: Exception) {
            java.util.concurrent.CompletableFuture.completedFuture(ApiResponse.error(e))
        }
    }

    /**
     * 同步处理请求
     */
    abstract fun handleSync(context: RequestContext): ApiResponse
}


/**
 * Bukkit主线程路由处理器
 *
 * 核心特性：
 * - 框架会自动将 handleBukkit 调度到 Bukkit 主线程执行
 * - 可以在这里安全地调用任何 Bukkit API (如 player.kick, block.setType)
 * - 受到 TaskScheduler 的 TPS 保护和限流
 */
abstract class BukkitSyncRouteHandler : RouteHandler {
    // 这个方法由 CoreHttpServer 自动调度，不需要用户操心
    override fun handle(context: RequestContext): java.util.concurrent.CompletableFuture<ApiResponse> {
        // 这一层逻辑将由 CoreHttpServer 中的调度器接管
        // 但为了接口兼容，我们这里暂时返回 null 或抛出异常，实际调用逻辑在 Server 中
        throw UnsupportedOperationException("BukkitSyncRouteHandler should be handled by the server framework")
    }

    /**
     * 在主线程执行的逻辑
     */
    abstract fun handleBukkit(context: RequestContext): ApiResponse
}
