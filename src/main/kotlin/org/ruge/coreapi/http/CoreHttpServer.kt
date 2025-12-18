package org.ruge.coreapi.http

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import org.eclipse.jetty.util.thread.QueuedThreadPool
import org.ruge.coreapi.auth.AuthManager
import org.ruge.coreapi.lang.LanguageManager
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning
import java.util.concurrent.TimeUnit

/**
 * CoreAPI HTTP服务器
 * 基于Jetty实现的企业级HTTP服务器
 */
class CoreHttpServer(
    private val port: Int,
    private val routeRegistry: RouteRegistry,
    private val taskScheduler: org.ruge.coreapi.task.TaskScheduler, // 新增依赖
    private val rateLimitManager: RateLimitManager? = null,
    private val authManager: AuthManager? = null,
    private val trustProxy: Boolean = false,
    private val maxBodySize: Long = 1048576,
    private val corsOrigin: String = "none"
) {
    private var server: Server? = null

    /**
     * 启动服务器
     */
    fun startServer() {
        // 配置线程池
        val threadPool = QueuedThreadPool().apply {
            maxThreads = 20      // 最多20个工作线程
            minThreads = 5       // 最少5个工作线程
            idleTimeout = 60000  // 空闲60秒后回收
        }

        // 创建服务器
        server = Server(threadPool)

        // 配置连接器
        val connector = ServerConnector(server).apply {
            this.port = this@CoreHttpServer.port
            idleTimeout = 30000  // 30秒超时
            acceptQueueSize = 50 // 等待队列最多50
        }
        server?.addConnector(connector)

        // 配置Servlet
        val context = ServletContextHandler(ServletContextHandler.SESSIONS)
        context.contextPath = "/"
        server?.handler = context

        // 注册Servlet
        context.addServlet(ServletHolder(CoreApiServlet()), "/*")

        // 启动服务器
        try {
            server?.start()
            info(LanguageManager.getMessage("http.server-started", port))
            info(LanguageManager.getMessage("http.server-url", port))
        } catch (e: Exception) {
            warning(LanguageManager.getMessage("http.server-start-failed", e.message ?: "Unknown error"))
            throw e
        }
    }

    /**
     * 停止服务器
     */
    fun stopServer() {
        try {
            server?.stop()
            info(LanguageManager.getMessage("http.server-stopped"))
        } catch (e: Exception) {
            warning(LanguageManager.getMessage("http.server-stop-failed", e.message ?: "Unknown error"))
        }
    }

    /**
     * 核心Servlet处理器
     */
    inner class CoreApiServlet : HttpServlet() {

        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            handleRequest(req, resp, HttpMethod.GET)
        }

        override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
            handleRequest(req, resp, HttpMethod.POST)
        }

        override fun doPut(req: HttpServletRequest, resp: HttpServletResponse) {
            handleRequest(req, resp, HttpMethod.PUT)
        }

        override fun doDelete(req: HttpServletRequest, resp: HttpServletResponse) {
            handleRequest(req, resp, HttpMethod.DELETE)
        }

        override fun doOptions(req: HttpServletRequest, resp: HttpServletResponse) {
            // CORS预检请求
            applyCorsHeaders(resp)
            resp.status = HttpServletResponse.SC_OK
        }

        /**
         * 处理HTTP请求
         */
        private fun handleRequest(req: HttpServletRequest, resp: HttpServletResponse, method: HttpMethod) {
            val startTime = System.currentTimeMillis()

            try {
                // 获取客户端IP
                // 安全修复：根据配置决定是否信任代理头
                val clientIp = if (trustProxy) {
                    req.getHeader("X-Forwarded-For")
                        ?: req.getHeader("X-Real-IP")
                        ?: req.remoteAddr
                } else {
                    req.remoteAddr
                }

                // Rate Limiting检查
                if (rateLimitManager != null && !rateLimitManager.tryAcquire(clientIp)) {
                    sendJsonResponse(resp, 429, ApiResponse.error("请求过于频繁，请稍后重试"))
                    return
                }

                // 解析请求
                val context = parseRequest(req, method, clientIp)

                // 查找路由处理器
                val routeInfo = routeRegistry.getRouteInfo(context.uri, context.method)
                if (routeInfo == null) {
                    sendJsonResponse(resp, 404, ApiResponse.error("路由不存在: ${method} ${context.uri}"))
                    return
                }

                // 权限认证检查
                if (routeInfo.requireAuth && authManager != null && authManager.isEnabled()) {
                    // 从请求头获取 token
                    val token = context.getAuthToken()

                    // 确定权限节点（优先使用自定义权限节点）
                    val permission = routeInfo.permission
                        ?: authManager.generatePermissionNode(routeInfo.plugin.name, routeInfo.path)

                    // 执行权限检查
                    val authResult = authManager.authenticate(token, permission)

                    if (!authResult.success) {
                        val statusCode = if (token == null) 401 else 403
                        val errorMessage = if (token == null) {
                            "未提供认证 token"
                        } else {
                            authResult.error ?: "权限不足"
                        }
                        sendJsonResponse(resp, statusCode, ApiResponse.error(errorMessage))
                        return
                    }
                }

                // 执行处理器
                val futureResponse = if (routeInfo.handler is BukkitSyncRouteHandler) {
                    // 特殊处理：Bukkit主线程任务
                    // 自动提交到 TaskScheduler
                    taskScheduler.submitTask {
                        (routeInfo.handler as BukkitSyncRouteHandler).handleBukkit(context)
                    }
                } else {
                    // 标准处理：在当前线程（HTTP线程）执行
                    // 或者是 AsyncRouteHandler 自行处理了线程切换
                    routeInfo.handler.handle(context)
                }

                // 等待结果（最多3秒）
                val apiResponse = try {
                    futureResponse.get(3, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    when (e) {
                        is java.util.concurrent.TimeoutException ->
                            ApiResponse.error("请求处理超时")
                        is java.util.concurrent.RejectedExecutionException ->
                            ApiResponse.error("服务器繁忙，请稍后重试")
                        else ->
                            ApiResponse.error(e)
                    }
                }

                // 记录慢请求
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed > 1000) {
                    warning(LanguageManager.getMessage("http.slow-request", method, context.uri, elapsed))
                }

                // 返回响应
                sendJsonResponse(resp, 200, apiResponse)

            } catch (e: Exception) {
                // 安全修复：防止异常信息泄露敏感数据
                // 生成一个随机错误ID，方便管理员在日志中查找
                val errorId = java.util.UUID.randomUUID().toString().substring(0, 8)
                warning(LanguageManager.getMessage("http.request-exception", errorId, e.message ?: "Unknown error"))
                e.printStackTrace()
                
                // 返回给用户的是通用错误信息 + 错误ID
                sendJsonResponse(resp, 500, ApiResponse.error("服务器内部错误 (Ref: $errorId)"))
            }
        }

        /**
         * 解析请求
         */
        private fun parseRequest(req: HttpServletRequest, method: HttpMethod, clientIp: String): RequestContext {
            // 读取请求头
            val headers = mutableMapOf<String, String>()
            req.headerNames.asIterator().forEach { name ->
                headers[name.lowercase()] = req.getHeader(name)
            }

            // 读取请求参数
            val params = mutableMapOf<String, String>()
            req.parameterMap.forEach { (name, values) ->
                params[name] = values.firstOrNull() ?: ""
            }

            // 构造延迟加载的Body读取器
            val bodyLoader = {
                if (method == HttpMethod.POST || method == HttpMethod.PUT) {
                    try {
                        // 安全修复：限制读取大小，防止内存溢出 (DoS)
                        if (req.contentLengthLong > maxBodySize) {
                            throw IllegalArgumentException("请求体过大 (超过 ${maxBodySize} 字节)")
                        }
                        
                        // 读取受限内容
                        // 使用 InputStream 直接读取字节，避免字符集编码导致的内存估算错误
                        val inputStream = req.inputStream
                        val buffer = ByteArray(4096)
                        val outStream = java.io.ByteArrayOutputStream()
                        var totalBytesRead = 0L
                        var bytesRead: Int
                        
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            totalBytesRead += bytesRead
                            if (totalBytesRead > maxBodySize) {
                                 throw IllegalArgumentException("请求体过大 (读取超过 ${maxBodySize} 字节)")
                            }
                            outStream.write(buffer, 0, bytesRead)
                        }
                        
                        outStream.toString("UTF-8")
                    } catch (e: Exception) {
                        warning(LanguageManager.getMessage("http.read-body-failed", e.message ?: "Unknown error"))
                        null
                    }
                } else {
                    null
                }
            }

            return RequestContext(
                method = method,
                uri = req.requestURI,
                headers = headers,
                params = params,
                bodyLoader = bodyLoader,
                clientIp = clientIp
            )
        }

        /**
         * 发送JSON响应
         */
        private fun sendJsonResponse(resp: HttpServletResponse, statusCode: Int, apiResponse: ApiResponse) {
            resp.status = statusCode
            resp.contentType = "application/json"
            resp.characterEncoding = "UTF-8"

            // CORS headers
            applyCorsHeaders(resp)

            resp.writer.write(apiResponse.toJson())
            resp.writer.flush()
        }

        /**
         * 应用CORS响应头
         */
        private fun applyCorsHeaders(resp: HttpServletResponse) {
            if (corsOrigin.equals("none", ignoreCase = true)) {
                return
            }
            
            resp.addHeader("Access-Control-Allow-Origin", corsOrigin)
            resp.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
            resp.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
            resp.addHeader("Access-Control-Max-Age", "3600") // 缓存预检结果1小时
        }
    }
}
