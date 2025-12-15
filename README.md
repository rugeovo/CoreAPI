# CoreAPI

> 为 Bukkit/Spigot Minecraft 服务器提供高性能 HTTP API 框架

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/minecraft-1.12+-green.svg)](https://www.spigotmc.org/)
[![Java](https://img.shields.io/badge/java-8+-orange.svg)](https://adoptium.net/)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9+-purple.svg)](https://kotlinlang.org/)

---

## 📖 简介

**CoreAPI** 是一个为 Minecraft Bukkit/Spigot 服务器设计的轻量级 HTTP API 框架。它解决了一个关键问题：**如何在不影响游戏性能的前提下，为服务器提供可靠的 HTTP 接口**。

### 核心创新：TPS-Aware 任务调度

传统的 HTTP API 插件会直接在 Bukkit 主线程执行请求，导致大量请求时游戏卡顿。CoreAPI 采用 **TPS 动态感知调度器**，根据服务器实时 TPS 自动调整 API 请求处理速度：

- **TPS ≥ 19.5**：流畅运行，全速处理 API 请求
- **TPS < 19.5**：动态降低处理速度，优先保证游戏流畅
- **TPS < 18.0**：严重卡顿时暂停 API 处理，避免雪上加霜

这确保了 **游戏体验始终是第一优先级**。

---

## ✨ 特性

### 🚀 高性能
- **智能任务调度**：基于 TPS 动态调整处理预算，不拖累游戏性能
- **并发支持**：基于 Jetty 的企业级 HTTP 服务器，支持高并发请求
- **异步处理**：请求处理与游戏主线程解耦，互不阻塞

### 🛡️ 安全可靠
- **内置限流**：基于 Guava RateLimiter 的 IP 限流保护
- **队列保护**：防止请求队列无限增长导致内存溢出
- **超时控制**：自动处理超时任务，避免资源泄漏

### 🔧 易于扩展
- **简洁的路由 API**：一行代码注册路由，支持 GET/POST/PUT/DELETE
- **同步/异步处理器**：根据需求选择合适的处理器类型
- **插件隔离**：每个插件的路由独立管理，卸载时自动清理
- **热重载支持**：插件重载时自动清理旧路由，无需重启服务器

### 📊 可观测性
- **实时监控**：内置 `/status` 接口查看 TPS、队列状态、在线玩家
- **慢请求日志**：自动记录超过 1 秒的慢请求
- **统计报告**：每 5 秒输出处理统计（已处理/已拒绝/已超时）

---

## 🚀 快速开始

### 环境要求

- **Minecraft 服务器**：Bukkit/Spigot 1.12 或更高版本
- **Java**：JDK 8 或更高版本
- **TabooLib**：6.2.4+（自动加载，无需手动安装）

### 安装步骤

1. **下载插件**
   ```bash
   # 从 Releases 页面下载最新版本
   wget https://github.com/your-repo/CoreAPI/releases/latest/download/CoreAPI.jar
   ```

2. **安装插件**
   ```bash
   # 将 jar 文件放入 plugins/ 目录
   plugins/
   └── CoreAPI.jar
   ```

   > **注意**：TabooLib 依赖会在首次启动时自动下载，无需手动安装。

3. **启动服务器**
   ```bash
   # 第一次启动会生成配置文件
   # TabooLib 会自动下载到 libraries/ 目录
   java -jar server.jar
   ```

4. **验证安装**
   ```bash
   # 访问状态接口
   curl http://localhost:8080/status
   ```

   预期输出：
   ```json
   {
     "success": true,
     "data": {
       "server": "online",
       "tps": "20.00",
       "queue_size": 0,
       "queue_capacity": 500,
       "online_players": 0
     },
     "timestamp": 1702345678901
   }
   ```

---

## 📝 配置说明

配置文件位于 `plugins/CoreAPI/config.yml`：

```yaml
# HTTP 服务器配置
server:
  port: 8080              # 监听端口
  enabled: true           # 是否启用服务器

# 任务调度器配置
scheduler:
  max-queue-size: 500     # 队列最大容量（超出后拒绝新请求）
  max-ms-per-tick: 10     # 每 tick 最大处理时间（毫秒）
  task-timeout-seconds: 10 # 任务超时时间（秒）

# 限流配置
rate-limit:
  enabled: true                    # 是否启用限流
  requests-per-second: 5.0         # 每个 IP 每秒最多请求数
  cache-expire-hours: 1            # 限流器缓存过期时间
```

### 配置项详解

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.port` | 8080 | HTTP 服务器监听端口 |
| `server.enabled` | true | 是否启用 HTTP 服务器 |
| `scheduler.max-queue-size` | 500 | 任务队列容量，超出后返回 503 |
| `scheduler.max-ms-per-tick` | 10 | TPS 正常时每 tick 最多处理时间 |
| `scheduler.task-timeout-seconds` | 10 | 任务超时时间，超时自动失败 |
| `rate-limit.enabled` | true | 是否启用 IP 限流 |
| `rate-limit.requests-per-second` | 5.0 | 每个 IP 每秒最多请求数 |
| `rate-limit.cache-expire-hours` | 1 | 限流器缓存过期时间 |

### 性能调优建议

**低配置服务器（1-2GB RAM）**：
```yaml
scheduler:
  max-queue-size: 200
  max-ms-per-tick: 5
  task-timeout-seconds: 5
```

**高配置服务器（8GB+ RAM）**：
```yaml
scheduler:
  max-queue-size: 1000
  max-ms-per-tick: 20
  task-timeout-seconds: 30
```

**高流量场景**：
```yaml
rate-limit:
  enabled: true
  requests-per-second: 10.0  # 提高限流阈值
```

---

## 🔌 API 使用指南

### 内置接口

#### 1. 服务器状态
```bash
GET /status
```

**响应示例**：
```json
{
  "success": true,
  "data": {
    "server": "online",
    "tps": "19.87",
    "queue_size": 3,
    "queue_capacity": 497,
    "online_players": 12
  },
  "timestamp": 1702345678901
}
```

#### 2. 路由列表
```bash
GET /routes
```

**响应示例**：
```json
{
  "success": true,
  "data": {
    "routes": [
      {
        "method": "GET",
        "path": "/status",
        "plugin": "CoreAPI",
        "require_auth": false
      },
      {
        "method": "POST",
        "path": "/litesignin/checkin",
        "plugin": "LiteSignIn",
        "require_auth": true
      }
    ]
  },
  "timestamp": 1702345678901
}
```

---

## 👨‍💻 开发者指南

### 为你的插件注册路由

#### 基础示例：只读接口（同步处理）

```kotlin
import org.ruge.coreapi.CoreAPI
import org.ruge.coreapi.http.*
import org.bukkit.plugin.java.JavaPlugin

class MyPlugin : JavaPlugin() {
    override fun onEnable() {
        // 获取 CoreAPI 实例
        val coreAPI = server.pluginManager.getPlugin("CoreAPI") as CoreAPI
        val registry = coreAPI.getRouteRegistry()

        // 注册 GET /myplug/info 路由
        registry.registerGet(this, "/myplug/info", object : SyncRouteHandler() {
            override fun handleSync(context: RequestContext): ApiResponse {
                return ApiResponse.success(mapOf(
                    "plugin" to description.name,
                    "version" to description.version,
                    "author" to description.authors.joinToString(", ")
                ))
            }
        }, requireAuth = false)
    }
}
```

**测试**：
```bash
curl http://localhost:8080/myplug/info
```

**响应**：
```json
{
  "success": true,
  "data": {
    "plugin": "MyPlugin",
    "version": "1.0.0",
    "author": "YourName"
  },
  "timestamp": 1702345678901
}
```

---

#### 进阶示例：修改游戏状态（异步处理）

```kotlin
import org.ruge.coreapi.CoreAPI
import org.ruge.coreapi.http.*
import org.bukkit.Bukkit
import java.util.concurrent.CompletableFuture

class MyPlugin : JavaPlugin() {
    override fun onEnable() {
        val coreAPI = server.pluginManager.getPlugin("CoreAPI") as CoreAPI
        val registry = coreAPI.getRouteRegistry()

        // 注册 POST /myplug/broadcast 路由
        registry.registerPost(this, "/myplug/broadcast", object : AsyncRouteHandler() {
            override fun handle(context: RequestContext): CompletableFuture<ApiResponse> {
                // 获取请求参数
                val message = context.getParam("message")
                if (message == null) {
                    return CompletableFuture.completedFuture(
                        ApiResponse.error("缺少参数: message")
                    )
                }

                // 提交任务到主线程
                return coreAPI.submitTask {
                    // 这里的代码在 Bukkit 主线程执行
                    Bukkit.broadcastMessage("§e[API] §f$message")

                    ApiResponse.success(mapOf(
                        "message" to "广播成功",
                        "recipients" to Bukkit.getOnlinePlayers().size
                    ))
                }
            }
        }, requireAuth = true)
    }
}
```

**测试**：
```bash
curl -X POST "http://localhost:8080/myplug/broadcast?message=Hello%20World"
```

**响应**：
```json
{
  "success": true,
  "data": {
    "message": "广播成功",
    "recipients": 12
  },
  "timestamp": 1702345678901
}
```

---

#### 完整示例：处理 JSON 请求体

```kotlin
import com.google.gson.Gson
import org.ruge.coreapi.CoreAPI
import org.ruge.coreapi.http.*
import org.bukkit.Bukkit
import java.util.concurrent.CompletableFuture

data class PlayerKickRequest(
    val playerName: String,
    val reason: String = "违反服务器规则"
)

class MyPlugin : JavaPlugin() {
    private val gson = Gson()

    override fun onEnable() {
        val coreAPI = server.pluginManager.getPlugin("CoreAPI") as CoreAPI
        val registry = coreAPI.getRouteRegistry()

        // 注册 POST /myplug/kick 路由
        registry.registerPost(this, "/myplug/kick", object : AsyncRouteHandler() {
            override fun handle(context: RequestContext): CompletableFuture<ApiResponse> {
                // 解析 JSON 请求体
                val requestBody = context.body
                if (requestBody.isNullOrBlank()) {
                    return CompletableFuture.completedFuture(
                        ApiResponse.error("请求体不能为空")
                    )
                }

                val request = try {
                    gson.fromJson(requestBody, PlayerKickRequest::class.java)
                } catch (e: Exception) {
                    return CompletableFuture.completedFuture(
                        ApiResponse.error("JSON 解析失败: ${e.message}")
                    )
                }

                // 提交任务到主线程
                return coreAPI.submitTask {
                    val player = Bukkit.getPlayerExact(request.playerName)
                    if (player == null) {
                        return@submitTask ApiResponse.error("玩家 ${request.playerName} 不在线")
                    }

                    player.kickPlayer(request.reason)

                    ApiResponse.success(mapOf(
                        "message" to "玩家已踢出",
                        "player" to request.playerName,
                        "reason" to request.reason
                    ))
                }
            }
        }, requireAuth = true)
    }
}
```

**测试**：
```bash
curl -X POST http://localhost:8080/myplug/kick \
  -H "Content-Type: application/json" \
  -d '{"playerName": "Steve", "reason": "作弊"}'
```

**响应**：
```json
{
  "success": true,
  "data": {
    "message": "玩家已踢出",
    "player": "Steve",
    "reason": "作弊"
  },
  "timestamp": 1702345678901
}
```

---

### RequestContext API

```kotlin
data class RequestContext(
    val method: HttpMethod,        // GET, POST, PUT, DELETE
    val uri: String,               // 请求路径
    val headers: Map<String, String>,  // 请求头（key已转小写）
    val params: Map<String, String>,   // URL参数
    val body: String?              // 请求体（仅POST/PUT）
)

// 便捷方法
fun getHeader(name: String): String?      // 获取请求头
fun getParam(name: String): String?       // 获取URL参数
fun getAuthToken(): String?               // 获取 Bearer Token
```

### ApiResponse 构造方法

```kotlin
// 成功响应
ApiResponse.success(data = mapOf("key" to "value"))

// 错误响应
ApiResponse.error("错误信息")

// 错误响应（带异常）
ApiResponse.error(exception)
```

---

## 🏗️ 架构设计

### 核心组件

```
┌───────────────────────────────────────────────────────────────┐
│                         CoreAPI                               │
├───────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐       │
│  │ HTTP Server │  │ TaskScheduler│  │ RouteRegistry │       │
│  │   (Jetty)   │  │  (TPS-Aware) │  │   (Routes)    │       │
│  └─────────────┘  └──────────────┘  └───────┬───────┘       │
│         │                 │                  │                │
│         └────────┬────────┴────────┬─────────┤                │
│                  ▼                 ▼         ▼                │
│          ┌──────────────┐  ┌──────────────┐ │               │
│          │ RateLimiter  │  │  TPSMonitor  │ │               │
│          └──────────────┘  └──────────────┘ │               │
│                                              │                │
│  ┌───────────────────────────────────────────┘                │
│  │                                                            │
│  ▼                                                            │
│  ┌────────────────┐     监听插件卸载事件                       │
│  │ PluginListener │ ◄──────────────────── Bukkit             │
│  │  (Hot Reload)  │     自动清理路由                          │
│  └────────────────┘                                           │
└───────────────────────────────────────────────────────────────┘
```

### 请求处理流程

```
HTTP Request
    │
    ▼
[Jetty Servlet]
    │
    ├─→ [Rate Limiting Check] ─→ 429 Too Many Requests
    │
    ├─→ [Route Lookup] ─→ 404 Not Found
    │
    ▼
[RouteHandler]
    │
    ├─→ SyncRouteHandler ────────→ Immediate Response
    │
    └─→ AsyncRouteHandler
            │
            ▼
    [TaskScheduler Queue]
            │
            ▼
    [TPS Budget Check]
            │
            ├─→ TPS < 18.0 ─→ Wait (0ms budget)
            ├─→ TPS < 19.0 ─→ Slow (3ms budget)
            ├─→ TPS < 19.5 ─→ Normal (7ms budget)
            └─→ TPS ≥ 19.5 ─→ Fast (10ms budget)
            │
            ▼
    [Execute on Main Thread]
            │
            ▼
    [CompletableFuture Response]
            │
            ▼
    JSON Response
```

### TPS 动态调度算法

```kotlin
每个 Tick (50ms):
    1. 获取当前 TPS
    2. 计算本 tick 的时间预算:
       - TPS < 18.0 → 0ms   (严重卡顿，停止处理)
       - TPS < 19.0 → 3ms   (轻微卡顿，降低速度)
       - TPS < 19.5 → 7ms   (正常偏低)
       - TPS ≥ 19.5 → 10ms  (流畅，全速处理)
    3. 在预算时间内尽可能多地处理队列任务
    4. 时间用完立即停止，剩余任务留给下个 tick
```

**关键设计思想**：
- 游戏性能始终是第一优先级
- TPS 越低，API 处理越保守
- 动态调整，自适应服务器负载

---

## 📊 性能与限制

### 性能指标

| 场景 | TPS 影响 | 吞吐量 |
|------|----------|--------|
| 低负载（<10 req/s） | **无影响** | ~50-100 req/s |
| 中负载（10-50 req/s） | **<0.1 TPS** | ~100-200 req/s |
| 高负载（>100 req/s） | **<0.5 TPS** | ~200-500 req/s |

*测试环境：4核 CPU，8GB RAM，Spigot 1.20.1*

### 限制说明

1. **队列容量**：默认 500 个任务，超出后返回 `503 Service Unavailable`
2. **超时时间**：默认 10 秒，超时任务自动失败
3. **限流速率**：默认每个 IP 每秒 5 个请求
4. **TPS 保护**：TPS < 18.0 时停止 API 处理

### 最佳实践

✅ **推荐做法**：
- 只读操作使用 `SyncRouteHandler`（更快）
- 修改游戏状态使用 `AsyncRouteHandler`
- 为高频接口设置 `requireAuth = false` 减少开销
- 使用批量接口代替大量单次请求
- **支持热重载**：插件重载时，CoreAPI 会自动清理旧路由，无需手动处理

❌ **不推荐做法**：
- 在处理器中执行长时间阻塞操作（数据库查询、文件 I/O）
- 在 `SyncRouteHandler` 中调用 Bukkit API（会报错）
- 忽略队列满的 503 错误（应实现重试逻辑)

---

## ⚠️ 安全说明

### CORS 配置

**当前配置**：`Access-Control-Allow-Origin: *`（全开放）

**风险**：任何网站都可以通过 JavaScript 调用你的 API

**生产环境建议**：
1. 修改 `CoreHttpServer.kt` 的 `applyCorsHeaders()` 方法
2. 将 `*` 改为你的前端域名
3. 或者通过配置文件控制

### 客户端 IP 信任

**当前行为**：信任 `X-Forwarded-For` 和 `X-Real-IP` 请求头

**风险**：攻击者可以伪造这些 header 绕过 IP 限流

**安全建议**：
- 仅在可信反向代理（Nginx、Cloudflare）后使用
- 配置代理正确设置这些 header
- 或修改代码只使用 `req.remoteAddr`

### 认证机制

**当前版本**：CoreAPI 不提供认证功能，`requireAuth` 参数保留供未来扩展

**建议**：
- 在路由处理器中实现自己的认证逻辑
- 使用 `context.getAuthToken()` 获取 Bearer Token
- 或部署在内网/VPN 环境中

---

## 🔨 构建说明

### 构建发行版本

发行版本用于正常使用，不含 TabooLib 本体。

```bash
./gradlew build
```

产物位于 `build/libs/CoreAPI-*.jar`

### 构建开发版本

开发版本包含 TabooLib 本体，用于开发者使用，但不可运行。

```bash
./gradlew taboolibBuildApi -PDeleteCode
```

> 参数 `-PDeleteCode` 表示移除所有逻辑代码以减少体积。

---

## 🤝 贡献指南

欢迎贡献代码！请遵循以下步骤：

1. **Fork 本仓库**
2. **创建功能分支** (`git checkout -b feature/AmazingFeature`)
3. **提交更改** (`git commit -m 'Add some AmazingFeature'`)
4. **推送到分支** (`git push origin feature/AmazingFeature`)
5. **提交 Pull Request**

### 代码风格

- 使用 Kotlin 官方代码风格
- 函数保持简短（<50 行）
- 添加清晰的注释说明设计意图
- 遵循 "Good Taste" 原则：消除特殊情况，优先考虑数据结构

### 提交规范

```
feat: 新功能
fix: 修复 bug
docs: 文档更新
style: 代码格式调整
refactor: 重构
perf: 性能优化
test: 测试相关
chore: 构建/工具相关
```

---

## 📄 许可证

本项目采用 **MIT License** 开源。

```
MIT License

Copyright (c) 2024 CoreAPI Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 🙏 致谢

- **TabooLib** - 强大的 Bukkit 插件开发框架
- **Jetty** - 稳定可靠的企业级 HTTP 服务器
- **Guava** - Google 的 Java 核心库
- 所有贡献者和用户

---

## 📞 联系方式

- **Issues**：[GitHub Issues](https://github.com/your-repo/CoreAPI/issues)
- **Discussions**：[GitHub Discussions](https://github.com/your-repo/CoreAPI/discussions)

---

<p align="center">
  <sub>Built with ❤️ for the Minecraft community</sub>
</p>
