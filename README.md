# CoreAPI 项目概述

**CoreAPI 是一个为 Minecraft Bukkit 服务器提供的生产级 HTTP API 网关框架，集成认证、限流、任务调度、权限管理等完整生态。**

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/minecraft-1.12+-green.svg)](https://www.spigotmc.org/)
[![Java](https://img.shields.io/badge/java-8+-orange.svg)](https://adoptium.net/)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2+-purple.svg)](https://kotlinlang.org/)
---

## 核心问题与解决方案

### 问题
Minecraft 插件间需要 HTTP API 通信，但存在以下痛点：
1. 每个插件单独实现 HTTP 服务器 → 端口混乱、代码重复
2. 缺乏统一认证 → 安全性差
3. HTTP 线程直接调用 Bukkit API → 线程安全问题、服务器崩溃
4. 无防护机制 → 易受攻击（暴力破解、DoS）

### 解决方案
CoreAPI 提供统一的 HTTP 网关：
- **统一端口**：所有插件共享一个 HTTP 服务器（Jetty）
- **安全认证**：JWT + AuthMe + LuckPerms 三层防护
- **线程安全**：智能任务调度器，HTTP 请求自动调度到 Bukkit 主线程
- **防护机制**：限流、熔断、暴力破解防护、请求体大小限制

---

## 技术架构

### 架构图（数据流）

```
┌─────────────────┐
│   HTTP 客户端    │
└────────┬────────┘
         │ HTTP Request
         ▼
┌─────────────────────────────────────────────────┐
│         CoreHttpServer (Jetty 11.0.20)          │
│  ┌──────────────────────────────────────────┐   │
│  │ 1. IP 获取 (trustProxy 决策)            │   │
│  │ 2. 限流检查 (RateLimitManager: 5 req/s) │   │
│  │ 3. 路由查找 (RouteRegistry)              │   │
│  │ 4. 认证验证 (JWT + LuckPerms)           │   │
│  └──────────────────────────────────────────┘   │
└────────┬────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────┐
│           RouteHandler 分派                      │
├─────────────────────────────────────────────────┤
│  ┌──────────────────┐   ┌───────────────────┐  │
│  │ SyncRouteHandler │   │BukkitSyncRoute    │  │
│  │                  │   │Handler            │  │
│  │ (HTTP 线程执行)  │   │(主线程执行)       │  │
│  │ 用于 I/O 操作   │   │用于 Bukkit API    │  │
│  └──────────────────┘   └─────────┬─────────┘  │
│                                    │             │
│                                    ▼             │
│                         ┌─────────────────────┐ │
│                         │  TaskScheduler      │ │
│                         │  (熔断 + 容量控制)  │ │
│                         └─────────────────────┘ │
└─────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────┐
│         外部依赖（可选）                         │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │
│  │ AuthMe   │  │LuckPerms │  │Bukkit Server │  │
│  │用户认证  │  │权限管理  │  │游戏逻辑      │  │
│  └──────────┘  └──────────┘  └──────────────┘  │
└─────────────────────────────────────────────────┘
```

---

## 核心模块

### 1. HTTP 层 (`org.ruge.coreapi.http`)

| 类 | 职责 | 关键特性 |
|---|------|---------|
| **CoreHttpServer** | Jetty HTTP 服务器封装 | • 端口配置<br>• CORS 支持<br>• trustProxy 反向代理支持<br>• 请求体大小限制（1MB） |
| **RouteRegistry** | 路由注册表 | • 线程安全（ConcurrentHashMap）<br>• 热重载（插件卸载自动清理）<br>• 支持 GET/POST/PUT/DELETE/PATCH |
| **RouteHandler** | 路由处理器接口 | • SyncRouteHandler（HTTP 线程）<br>• BukkitSyncRouteHandler（主线程）|
| **RateLimitManager** | IP 限流器 | • Guava RateLimiter（5 req/s/IP）<br>• 自动过期清理（1 小时） |

**数据结构设计评价**：🟢 简洁。`RouteRegistry` 用 `key = "$method:$path"` 存储，支持 O(1) 查找。`RateLimitManager` 用 Guava Cache 自动管理 IP 限流器生命周期，无需手动清理。

---

### 2. 认证层 (`org.ruge.coreapi.auth`)

| 类 | 职责 | 关键特性 |
|---|------|---------|
| **AuthService** | AuthMe 集成 | • 登录/注册<br>• 独立限流（1 req/s/IP）<br>• 防暴力破解（5 次失败 → 15 分钟 IP 黑名单）<br>• 密码强度验证 |
| **AuthManager** | LuckPerms 集成 | • JWT 验证<br>• 权限检查（支持通配符）<br>• UUID → 权限映射 |
| **JwtManager** | JWT 生成验证 | • HS256 算法<br>• 24 小时有效期<br>• 密钥安全检查（强制 32+ 字符，禁用默认密钥） |
| **TokenParser** | Token 解析 | • Bearer Token 提取<br>• JWT 签名验证<br>• 过期时间验证 |

**防暴力破解机制**：
```
认证请求 → checkAuthRateLimit(1 req/s) → 检查 IP 黑名单 → 检查失败次数
                                                ↓ 失败
                        recordLoginFailure → 计数 +1 → 达到 5 次？
                                                ↓ 是
                                        IP 黑名单 15 分钟 + 清除计数器
```

**设计亮点**：达到阈值时立即清除计数器（`loginAttempts.invalidate(username)`），避免缓存过期的竞态条件。

---

### 3. 任务调度层 (`org.ruge.coreapi.task`)

| 类 | 职责 | 关键特性 |
|---|------|---------|
| **TaskScheduler** | 主线程任务调度 | • 熔断保护（TPS < 12 停止处理）<br>• 流量控制（每 tick 最多 50 任务）<br>• 容量限制（Semaphore: 500）<br>• 超时监控（10ms 慢任务警告） |
| **AsyncTask** | 异步任务包装 | • CompletableFuture 封装<br>• Semaphore 泄漏修复（超时/异常仍释放） |

**熔断策略**：
```kotlin
if (tpsMonitor.getTPS() < minTpsThreshold) {
    // TPS < 12 → 停止处理任务，避免雪上加霜
    // 正确的舍弃策略：丢弃请求优于卡死整个服务器
    return
}
```

**评价**：🟢 好品味。使用 `while` 循环定量处理（每 tick 最多 50 个），不用复杂的优先级队列。Semaphore 确保容量，简单但有效。

---

### 4. 监控层 (`org.ruge.coreapi.util`)

| 类 | 职责 | 关键特性 |
|---|------|---------|
| **TPSMonitor** | TPS 多窗口监控 | • 环形缓冲（18000 ticks = 15 分钟）<br>• 支持 1s/5s/1min/5min/15min 窗口<br>• AtomicInteger 线程安全<br>• 冷启动保护（数据不足时估算） |

**数据结构**：
```kotlin
private val buffer = LongArray(18000)  // 18000 * 8 = 144 KB
private val currentIndex = AtomicInteger(0)
```

**为什么用 LongArray？** 避免对象开销，提高缓存局部性。144 KB 完全可以接受。

---

## 技术栈

### 核心依赖

| 库 | 版本 | 用途 | 评价 |
|----|------|------|------|
| **Jetty** | 11.0.20 | HTTP 服务器 | 轻量级，比 Ktor/Spring 更适合插件环境 |
| **JJWT** | 0.12.3 | JWT 生成验证 | 工业级库，支持所有标准算法 |
| **Guava** | 32.1.3-jre | 缓存 + 限流 | RateLimiter 和 Cache 是经过验证的方案 |
| **GSON** | 2.10.1 | JSON 序列化 | 简单直接，无需学习成本 |
| **AuthMe API** | 5.6.1-SNAPSHOT | 用户认证 | 可选依赖 |
| **LuckPerms API** | 5.4 | 权限管理 | 可选依赖 |
| **Kotlin** | 2.2.0 | 编译 | stdlib + coroutines |

### 编译配置
```gradle
Java: 1.8 兼容性（老版本 Minecraft 服务器）
Kotlin: 2.2.0
编译参数: -Xjvm-default=all（允许接口默认实现）
```

**依赖评价**：🟢 实用主义。选择成熟的库，不造轮子。Guava 的 Cache 和 RateLimiter 是工业级选择。

---

## 安全特性

### 1. JWT 密钥安全（JwtManager.init）
```kotlin
// 三层防护：
// 1. 禁止默认密钥（精确匹配）
if (secret == "CHANGE-THIS-TO-A-RANDOM-SECRET-KEY-AT-LEAST-32-CHARS-LONG") {
    throw SecurityException("请修改 JWT 密钥！")
}

// 2. 检查弱模式（"CHANGE", "DEFAULT" 等）
val weakPatterns = listOf("change", "default", "secret", "key", "test")
if (weakPatterns.any { secret.lowercase().contains(it) }) {
    throw SecurityException("JWT 密钥包含弱模式")
}

// 3. 强制最小长度 32 字符 + 字节校验
if (secret.length < 32 || secret.toByteArray().size < 32) {
    throw SecurityException("JWT 密钥长度不足 32 字符")
}
```
**评价**：🟢 好品味。不是所有项目都会硬性检查 JWT 密钥，这里做得很彻底。抛出 SecurityException 会导致插件无法启动，强制用户修改。

### 2. 密码强度验证（CoreAPI.validatePassword）
```
• 长度：8-128 字符
• 复杂度：必须包含字母 + 数字
• 弱密码黑名单：12345678, password, qwerty123 等 10 个
• 连续字符检查：禁止 7+ 个相同字符（aaaaaaaa）
```

### 3. 防 DoS
```kotlin
// 请求体大小限制（默认 1MB）
if (req.contentLengthLong > maxBodySize) {
    throw IllegalArgumentException("请求体过大")
}

// 使用 ByteArrayOutputStream 流式读取，定时检查总字节数
```

### 4. IP 地址获取安全
```kotlin
// trustProxy = false（默认）：只信任 req.remoteAddr
// trustProxy = true：信任 X-Forwarded-For（仅限可信反向代理）
```
**警告**：错误配置 `trustProxy=true` 可能导致 IP 伪造攻击。默认 false 是正确的。

---

## 配置文件

### config.yml
```yaml
server:
  port: 8080                        # HTTP 监听端口
  enabled: true                     # 总开关
  trust-proxy: false                # ⚠️ 仅在 Nginx/Cloudflare 后使用
  max-body-size-bytes: 1048576      # 1MB 上限
  cors-origin: "none"               # CORS 策略

scheduler:
  max-queue-size: 500               # 任务队列容量
  max-tasks-per-tick: 50            # 每 tick 处理数（流量控制）
  slow-task-threshold-ms: 10        # 慢任务报警
  min-tps-threshold: 12.0           # 熔断 TPS 阈值
  task-timeout-seconds: 10          # 任务超时

rate-limit:
  enabled: true                     # 全局限流（5 req/s/IP）
  requests-per-second: 5.0

jwt:
  secret: "CHANGE-THIS..."          # ⚠️ 必须修改！
  expiration-hours: 24

auth:
  max-login-attempts: 5             # 登录失败限制
  lockout-minutes: 15               # 账户锁定时间
```

**配置管理特点**：所有配置值使用 `lazy` 委托缓存，避免每次都从文件读取。

### lang.yml
完整的中文本地化文件，覆盖所有消息：
- 启动/关闭消息
- 认证相关（JWT、AuthMe、LuckPerms）
- HTTP 服务器
- 路由管理
- 任务调度统计
- API 响应消息

---

## 内置 API 路由

| 端点 | 方法 | 认证 | 功能 | 返回数据 |
|------|------|------|------|----------|
| `/login` | POST | ❌ | AuthMe 登录 | `{token, uuid, username}` |
| `/register` | POST | ❌ | AuthMe 注册 | `{token, uuid, username}` |
| `/status` | GET | ✅ | 服务器状态 | `{server, tps, queue_size, online_players}` |
| `/routes` | GET | ✅ | 列出所有路由 | `{routes: [{method, path, plugin, require_auth}]}` |

**响应格式**（统一）：
```json
{
  "success": boolean,
  "data": Any?,
  "error": String?,
  "timestamp": Long
}
```

---

## 插件集成 API

### 1. 注册路由
```kotlin
val registry = CoreAPI.getRouteRegistry()

// 注册不需要认证的路由
registry.registerGet(plugin, "/myapi/public", MyHandler())

// 注册需要认证 + 权限的路由
registry.registerPost(
    plugin = plugin,
    path = "/myapi/admin",
    handler = MyAdminHandler(),
    requireAuth = true  // 需要 coreapi.route.{plugin}.{path} 权限
)
```

### 2. 创建处理器

**同步处理器（HTTP 线程执行）**：
```kotlin
class MyHandler : SyncRouteHandler() {
    override fun handleSync(context: RequestContext): ApiResponse {
        // 用于 I/O 操作（数据库查询、HTTP 请求等）
        // 执行时间应 < 100ms
        val data = fetchFromDatabase()
        return ApiResponse.success(data)
    }
}
```

**Bukkit 同步处理器（主线程执行）**：
```kotlin
class MyBukkitHandler : BukkitSyncRouteHandler() {
    override fun handleBukkit(context: RequestContext): ApiResponse {
        // 可以安全调用 Bukkit API
        val player = Bukkit.getPlayer(context.body("player_name"))
        player?.sendMessage("Hello!")
        return ApiResponse.success(mapOf("sent" to true))
    }
}
```

### 3. 提交任务到主线程
```kotlin
val future: CompletableFuture<String> = CoreAPI.submitTask {
    // 这里是主线程，可以安全调用 Bukkit API
    val onlinePlayers = Bukkit.getOnlinePlayers().size
    "Online: $onlinePlayers"
}

// 等待结果（带超时）
val result = future.get(3, TimeUnit.SECONDS)
```

---

## 请求生命周期（完整示例）

### 用户登录全流程
```
客户端: POST /login {"username": "admin", "password": "Admin123456"}
  ↓
CoreApiServlet.doPost()
  ├─ IP: 192.168.1.100
  ├─ RateLimitManager.tryAcquire("192.168.1.100") ✅ 5 req/s/IP
  ├─ 路由查询: RouteRegistry.getRouteInfo("/login", POST)
  │  └─ 找到内置路由, requireAuth=false
  ├─ 跳过认证检查
  ├─ 调用 SyncRouteHandler:
  │  ├─ GSON 解析: LoginRequest(username, password)
  │  ├─ AuthService.login("admin", "Admin123456", "192.168.1.100")
  │  │  ├─ checkAuthRateLimit("192.168.1.100") ✅ 1 req/s
  │  │  ├─ ipBlacklist 检查: 无
  │  │  ├─ loginAttempts 检查: 0/5
  │  │  ├─ authMeApi.checkPassword("admin", pwd) ✅
  │  │  ├─ loginAttempts.invalidate("admin")  ← 清除失败计数
  │  │  ├─ uuid = Bukkit.getOfflinePlayer("admin").uniqueId
  │  │  ├─ token = jwtManager.generateToken(uuid)
  │  │  │  └─ Jwts.builder().subject(uuid).expiration(now+24h).signWith(key)
  │  │  └─ 返回: LoginResult.success(token, uuid, "admin")
  │  └─ ApiResponse.success({token, uuid, username})
  └─ 返回 HTTP 200 + JSON
```

### 使用 Token 访问受保护资源
```
客户端: GET /status, Authorization: Bearer eyJhbGci...
  ↓
CoreApiServlet.doGet()
  ├─ RateLimitManager.tryAcquire("192.168.1.100") ✅
  ├─ 路由查询: /status (GET), requireAuth=true
  ├─ 认证检查:
  │  ├─ context.getAuthToken() → "eyJhbGci..."
  │  ├─ authManager.authenticate(token, "coreapi.route.coreapi.status")
  │  ├─ tokenParser.validateJwt(token)
  │  │  └─ jwtManager.validateToken(token) ✅ 签名 + 过期时间
  │  ├─ authManager.checkPermission(uuid, "coreapi.route.coreapi.status")
  │  │  └─ luckperms.userManager.getUser(uuid).checkPermission(...) ✅
  │  └─ 认证通过
  ├─ 调用处理器: /status 的 SyncRouteHandler
  │  ├─ tps = TPSMonitor.getTPS()
  │  ├─ queueSize = taskScheduler.getQueueSize()
  │  └─ 返回状态 JSON
  └─ 返回 HTTP 200
```

---

## 热重载机制

### 插件卸载自动清理路由
```kotlin
// PluginListener.kt
@EventHandler
fun onPluginDisable(event: PluginDisableEvent) {
    val count = routeRegistry.unregisterAllForPlugin(event.plugin)
    if (count > 0) {
        logger.info("插件 ${event.plugin.name} 卸载，清理了 $count 个路由")
    }
}
```

**触发时机**：
- 插件 `/reload` 命令
- 插件被禁用
- 服务器关闭

**设计评价**：🟢 好。避免路由泄露，防止已卸载插件的路由仍然可访问。

---