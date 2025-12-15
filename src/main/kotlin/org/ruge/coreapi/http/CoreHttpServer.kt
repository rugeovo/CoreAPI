package org.ruge.coreapi.http

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import org.eclipse.jetty.util.thread.QueuedThreadPool
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
    private val rateLimitManager: RateLimitManager? = null
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
            info("HTTP服务器已启动 - 端口: $port")
            info("API地址: http://localhost:$port/")
        } catch (e: Exception) {
            warning("HTTP服务器启动失败: ${e.message}")
            throw e
        }
    }

    /**
     * 停止服务器
     */
    fun stopServer() {
        try {
            server?.stop()
            info("HTTP服务器已停止")
        } catch (e: Exception) {
            warning("HTTP服务器停止失败: ${e.message}")
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
                // 注意：X-Forwarded-For和X-Real-IP可以被伪造，仅适用于可信代理环境
                // 如果部署在不可信网络中，攻击者可通过伪造header绕过IP限流
                // 生产环境建议：1) 仅在可信反向代理后使用 2) 配置代理正确设置这些header
                val clientIp = req.getHeader("X-Forwarded-For")
                    ?: req.getHeader("X-Real-IP")
                    ?: req.remoteAddr

                // Rate Limiting检查
                if (rateLimitManager != null && !rateLimitManager.tryAcquire(clientIp)) {
                    sendJsonResponse(resp, 429, ApiResponse.error("请求过于频繁，请稍后重试"))
                    return
                }

                // 解析请求
                val context = parseRequest(req, method)

                // 查找路由处理器
                val handler = routeRegistry.findHandler(context.uri, context.method)
                if (handler == null) {
                    sendJsonResponse(resp, 404, ApiResponse.error("路由不存在: ${method} ${context.uri}"))
                    return
                }

                // 执行处理器（可能是异步的）
                val futureResponse = handler.handle(context)

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
                    warning("慢请求: ${method} ${context.uri} - ${elapsed}ms")
                }

                // 返回响应
                sendJsonResponse(resp, 200, apiResponse)

            } catch (e: Exception) {
                warning("HTTP请求处理失败: ${e.message}")
                e.printStackTrace()
                sendJsonResponse(resp, 500, ApiResponse.error("服务器内部错误: ${e.message}"))
            }
        }

        /**
         * 解析请求
         */
        private fun parseRequest(req: HttpServletRequest, method: HttpMethod): RequestContext {
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

            // 读取请求体（POST/PUT）
            val body = if (method == HttpMethod.POST || method == HttpMethod.PUT) {
                try {
                    req.reader.readText()
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }

            return RequestContext(
                method = method,
                uri = req.requestURI,
                headers = headers,
                params = params,
                body = body
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
         * 注意：当前配置为全开放（*），适用于开发环境
         * 生产环境建议配置为特定的域名以提高安全性
         */
        private fun applyCorsHeaders(resp: HttpServletResponse) {
            resp.addHeader("Access-Control-Allow-Origin", "*")
            resp.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
            resp.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        }
    }
}
