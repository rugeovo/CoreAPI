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
data class RequestContext(
    val method: HttpMethod,
    val uri: String,
    val headers: Map<String, String>,
    val params: Map<String, String>,
    val body: String?
) {
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
 * 异步路由处理器（用于需要主线程的操作）
 */
abstract class AsyncRouteHandler : RouteHandler {
    /**
     * 异步处理请求
     * 实现类可以在这里提交任务到TaskScheduler
     */
    abstract override fun handle(context: RequestContext): java.util.concurrent.CompletableFuture<ApiResponse>
}
