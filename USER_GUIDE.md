# CoreAPI 使用指南

## 目录

1. [目标读者](#1-目标读者)
2. [快速开始（5 分钟）](#2-快速开始5-分钟)
3. [配置说明](#3-配置说明)
4. [内置 API 使用指南](#4-内置-api-使用指南)
5. [插件开发指南](#5-插件开发指南)
6. [常见问题](#6-常见问题)
7. [故障排查](#7-故障排查)
8. [安全建议](#8-安全建议)
9. [性能优化](#9-性能优化)
10. [API 测试工具](#10-api-测试工具)
11. [总结](#14-总结)

---

## 1. 目标读者

- **服务器管理员**：需要部署和配置 CoreAPI
- **插件开发者**：需要为自己的插件添加 HTTP API

---

## 2. 快速开始（5 分钟）

### 1. 安装

将 `CoreAPI.jar` 放入服务器的 `plugins/` 目录。

### 2. 必要依赖

| 插件 | 是否必需 | 用途 |
|------|---------|------|
| **AuthMe** | 可选 | 用户登录/注册（使用 `/login` 和 `/register` 接口需要） |
| **LuckPerms** | 可选 | 权限验证（使用 `requireAuth=true` 的路由需要） |

**重要**：如果不需要认证功能，可以不装这两个插件。CoreAPI 会自动检测并禁用相关功能。

### 3. 修改 JWT 密钥（⚠️ 必须做）

首次启动后，编辑 `plugins/CoreAPI/config.yml`：

```yaml
jwt:
  secret: "你的48字符随机密钥"  # ⚠️ 必须修改！
```

**生成随机密钥**：
```bash
# Linux/Mac
openssl rand -base64 48

# Windows PowerShell
[Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))
```

**为什么必须修改？** CoreAPI 会检查密钥，如果使用默认密钥会拒绝启动：
```
SecurityException: 请修改 JWT 密钥！使用默认密钥极不安全！
```

### 4. 重启服务器

```
[CoreAPI] 正在启动 CoreAPI...
[CoreAPI] HTTP 服务器正在启动，端口：8080
[CoreAPI] HTTP 服务器已成功启动
```

### 5. 测试 API

```bash
# 查看所有路由
curl http://localhost:8080/routes

# 返回：
{
  "success": true,
  "data": {
    "routes": [
      {"method": "POST", "path": "/login", "plugin": "CoreAPI", "require_auth": false},
      {"method": "POST", "path": "/register", "plugin": "CoreAPI", "require_auth": false},
      {"method": "GET", "path": "/status", "plugin": "CoreAPI", "require_auth": true},
      {"method": "GET", "path": "/routes", "plugin": "CoreAPI", "require_auth": false}
    ]
  },
  "timestamp": 1702000000000
}
```

---

## 3. 配置说明

### config.yml 完整配置

```yaml
# ========================================
#  HTTP 服务器配置
# ========================================
server:
  # HTTP 监听端口
  port: 8080

  # 是否启用 HTTP 服务器（false 则完全禁用）
  enabled: true

  # ⚠️ 是否信任 X-Forwarded-For 头（反向代理）
  # 警告：仅在使用 Nginx/Cloudflare 等可信反向代理时设为 true
  # 错误配置会导致 IP 伪造攻击（攻击者可伪造任意 IP 绕过限流和黑名单）
  trust-proxy: false

  # 请求体最大字节数（防止 DoS 攻击）
  max-body-size-bytes: 1048576  # 1 MB

  # CORS 跨域策略
  # - "none": 禁用 CORS（默认）
  # - "*": 允许所有域名（仅开发环境）
  # - "https://example.com": 指定域名
  cors-origin: "none"

# ========================================
#  任务调度器配置
# ========================================
scheduler:
  # 任务队列最大容量（超过会拒绝新任务）
  max-queue-size: 500

  # 每 tick 最多处理的任务数（流量控制）
  # 建议值：20-100（取决于服务器性能）
  max-tasks-per-tick: 50

  # 慢任务警告阈值（毫秒）
  # 执行超过此时间会打印警告日志
  slow-task-threshold-ms: 10

  # 熔断 TPS 阈值
  # TPS 低于此值时停止处理新任务（避免雪上加霜）
  min-tps-threshold: 12.0

  # 任务等待超时（秒）
  # HTTP 请求等待主线程任务完成的最大时间
  task-timeout-seconds: 10

# ========================================
#  全局限流配置
# ========================================
rate-limit:
  # 是否启用限流（强烈建议启用）
  enabled: true

  # 每个 IP 每秒最大请求数
  requests-per-second: 5.0

  # 限流器缓存过期时间（小时）
  # IP 超过此时间无请求会清除缓存
  cache-expire-hours: 1

# ========================================
#  JWT 认证配置
# ========================================
jwt:
  # ⚠️ JWT 签名密钥（必须修改！）
  # 要求：
  # 1. 至少 32 字符
  # 2. 不能包含 "change", "default", "secret", "key", "test" 等弱模式
  # 3. 不能使用默认值
  # 生成命令：openssl rand -base64 48
  secret: "CHANGE-THIS-TO-A-RANDOM-SECRET-KEY-AT-LEAST-32-CHARS-LONG"

  # Token 有效期（小时）
  expiration-hours: 24

# ========================================
#  认证防护配置
# ========================================
auth:
  # 登录失败最大次数（超过会临时封禁 IP）
  max-login-attempts: 5

  # 封禁时长（分钟）
  lockout-minutes: 15
```

### 配置建议

#### 1. 生产环境推荐配置
```yaml
server:
  port: 8080
  trust-proxy: false  # ⚠️ 除非使用反向代理，否则永远 false
  cors-origin: "https://yourdomain.com"

rate-limit:
  enabled: true
  requests-per-second: 5.0  # 根据实际流量调整

scheduler:
  max-tasks-per-tick: 50
  min-tps-threshold: 12.0

auth:
  max-login-attempts: 5
  lockout-minutes: 15
```

#### 2. 开发环境推荐配置
```yaml
server:
  port: 8080
  trust-proxy: false
  cors-origin: "*"  # 允许所有域名

rate-limit:
  enabled: false  # 方便测试，但正式环境必须启用

scheduler:
  max-tasks-per-tick: 100  # 开发环境可以更激进

auth:
  max-login-attempts: 100  # 避免频繁测试被封禁
```

#### 3. 高性能服务器配置
```yaml
scheduler:
  max-queue-size: 1000
  max-tasks-per-tick: 100
  min-tps-threshold: 15.0  # 更高的熔断阈值

rate-limit:
  requests-per-second: 10.0  # 更宽松的限流
```

#### 4. 使用反向代理（Nginx）
```yaml
server:
  trust-proxy: true  # ⚠️ 仅在 Nginx 后使用
```

**Nginx 配置示例**：
```nginx
server {
    listen 80;
    server_name api.yourdomain.com;

    location / {
        proxy_pass http://127.0.0.1:8080;

        # 传递真实 IP
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Real-IP $remote_addr;

        # 超时设置
        proxy_connect_timeout 10s;
        proxy_send_timeout 10s;
        proxy_read_timeout 10s;
    }
}
```

**警告**：如果 `trust-proxy=true` 但服务器直接暴露在公网，攻击者可以伪造 `X-Forwarded-For` 头绕过限流和黑名单！

---

## 4. 内置 API 使用指南

### 1. 用户注册（POST /register）

**请求**：
```bash
curl -X POST http://localhost:8080/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "player123",
    "password": "SecurePass123"
  }'
```

**响应**（成功）：
```json
{
  "success": true,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI1NTBlODQwMC1lMjliLTQxZDQtYTcxNi00NDY2NTU0NDAwMDAiLCJleHAiOjE3MDIwODY0MDB9.abc123",
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "username": "player123"
  },
  "timestamp": 1702000000000
}
```

**响应**（失败）：
```json
{
  "success": false,
  "error": "密码不符合安全要求：必须包含字母和数字",
  "timestamp": 1702000000000
}
```

**密码要求**：
- 长度：8-128 字符
- 复杂度：必须包含字母和数字
- 禁止弱密码：`12345678`, `password`, `qwerty123` 等
- 禁止连续字符：`aaaaaaaa`

**错误码**：
- `400` - 密码不符合要求
- `409` - 用户名已存在
- `429` - 请求过于频繁（限流）
- `500` - 服务器内部错误

---

### 2. 用户登录（POST /login）

**请求**：
```bash
curl -X POST http://localhost:8080/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "player123",
    "password": "SecurePass123"
  }'
```

**响应**（成功）：
```json
{
  "success": true,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "username": "player123"
  },
  "timestamp": 1702000000000
}
```

**响应**（失败 - 密码错误）：
```json
{
  "success": false,
  "error": "用户名或密码错误 (剩余尝试次数: 4/5)",
  "timestamp": 1702000000000
}
```

**响应**（失败 - IP 被封禁）：
```json
{
  "success": false,
  "error": "登录失败次数过多，IP 已被临时封禁 15 分钟",
  "timestamp": 1702000000000
}
```

**防暴力破解机制**：
1. **独立限流**：登录接口限制为 1 req/s/IP（比全局 5 req/s 更严格）
2. **失败计数**：5 次密码错误 → IP 黑名单 15 分钟
3. **自动解封**：15 分钟后自动解除封禁

**保存 Token**：
客户端应保存返回的 `token`，用于后续请求的认证。

---

### 3. 查询服务器状态（GET /status）

**请求**（需要认证）：
```bash
curl -X GET http://localhost:8080/status \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..."
```

**响应**（成功）：
```json
{
  "success": true,
  "data": {
    "server": "online",
    "tps": "19.50",
    "queue_size": 2,
    "queue_capacity": 498,
    "online_players": 15
  },
  "timestamp": 1702000000000
}
```

**响应**（未认证）：
```json
{
  "success": false,
  "error": "未提供认证令牌",
  "timestamp": 1702000000000
}
```

**响应**（权限不足）：
```json
{
  "success": false,
  "error": "权限不足：需要权限 coreapi.route.coreapi.status",
  "timestamp": 1702000000000
}
```

**所需权限**：
```
coreapi.route.coreapi.status
```

**授予权限**（LuckPerms）：
```bash
/lp user player123 permission set coreapi.route.coreapi.status true
```

---

### 4. 查看所有路由（GET /routes）

**请求**：
```bash
curl -X GET http://localhost:8080/routes
```

**响应**：
```json
{
  "success": true,
  "data": {
    "routes": [
      {
        "method": "POST",
        "path": "/login",
        "plugin": "CoreAPI",
        "require_auth": false
      },
      {
        "method": "POST",
        "path": "/register",
        "plugin": "CoreAPI",
        "require_auth": false
      },
      {
        "method": "GET",
        "path": "/status",
        "plugin": "CoreAPI",
        "require_auth": true
      },
      {
        "method": "GET",
        "path": "/routes",
        "plugin": "CoreAPI",
        "require_auth": false
      },
      {
        "method": "GET",
        "path": "/myapi/players",
        "plugin": "MyPlugin",
        "require_auth": true
      }
    ]
  },
  "timestamp": 1702000000000
}
```

**用途**：
- 调试：查看哪些插件注册了路由
- 文档：自动生成 API 列表

---

## 5. 插件开发指南

### 1. 添加依赖

**build.gradle.kts**：
```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.yourusername:CoreAPI:1.0.0")
}
```

**plugin.yml**：
```yaml
depend: [CoreAPI]  # 确保 CoreAPI 先加载
```

---

### 2. 注册路由

#### 2.1 简单示例（不需要认证）

**MyPlugin.kt**：
```kotlin
import org.ruge.coreapi.CoreAPI
import org.ruge.coreapi.http.SyncRouteHandler
import org.ruge.coreapi.http.RequestContext
import org.ruge.coreapi.http.ApiResponse

class MyPlugin : JavaPlugin() {
    override fun onEnable() {
        val registry = CoreAPI.getRouteRegistry()

        // 注册 GET /myapi/hello
        registry.registerGet(
            plugin = this,
            path = "/myapi/hello",
            handler = HelloHandler()
        )
    }
}

class HelloHandler : SyncRouteHandler() {
    override fun handleSync(context: RequestContext): ApiResponse {
        return ApiResponse.success(mapOf("message" to "Hello, World!"))
    }
}
```

**测试**：
```bash
curl http://localhost:8080/myapi/hello

# 返回：
{
  "success": true,
  "data": {
    "message": "Hello, World!"
  },
  "timestamp": 1702000000000
}
```

---

#### 2.2 需要认证的路由

**MyPlugin.kt**：
```kotlin
registry.registerGet(
    plugin = this,
    path = "/myapi/admin",
    handler = AdminHandler(),
    requireAuth = true  // ← 启用认证
)
```

**所需权限**：
```
coreapi.route.{插件名}.{路径}
```

例如：`/myapi/admin` 路由需要权限：
```
coreapi.route.myplugin.myapi.admin
```

**授予权限**：
```bash
/lp user player123 permission set coreapi.route.myplugin.myapi.admin true

# 或授予所有 myapi 路由权限
/lp user player123 permission set coreapi.route.myplugin.myapi.* true
```

---

#### 2.3 读取请求参数

**GET 请求（查询参数）**：
```kotlin
class SearchHandler : SyncRouteHandler() {
    override fun handleSync(context: RequestContext): ApiResponse {
        // GET /myapi/search?keyword=test&limit=10
        val keyword = context.param("keyword") ?: ""
        val limit = context.param("limit")?.toIntOrNull() ?: 20

        val results = searchPlayers(keyword, limit)
        return ApiResponse.success(mapOf("results" to results))
    }
}
```

**POST 请求（JSON 请求体）**：
```kotlin
class CreateHandler : SyncRouteHandler() {
    override fun handleSync(context: RequestContext): ApiResponse {
        // POST /myapi/create
        // Body: {"name": "test", "value": 123}

        val name = context.body("name") ?: return ApiResponse.error("缺少参数: name")
        val value = context.body("value")?.toIntOrNull() ?: return ApiResponse.error("参数 value 必须是数字")

        // 处理逻辑
        createItem(name, value)

        return ApiResponse.success(mapOf("created" to true))
    }
}
```

**获取客户端 IP**：
```kotlin
val ip = context.clientIp  // 自动根据 trustProxy 配置获取真实 IP
```

---

#### 2.4 Bukkit 同步处理器（调用 Bukkit API）

**警告**：HTTP 请求在 Jetty 线程中处理，不能直接调用 Bukkit API（如 `player.sendMessage()`、`world.setBlock()` 等），否则会报错：
```
IllegalStateException: Asynchronous entity add!
```

**解决方案**：使用 `BukkitSyncRouteHandler`，自动调度到主线程。

**示例**：
```kotlin
import org.ruge.coreapi.http.BukkitSyncRouteHandler

class KickPlayerHandler : BukkitSyncRouteHandler() {
    override fun handleBukkit(context: RequestContext): ApiResponse {
        // ✅ 这里是主线程，可以安全调用 Bukkit API

        val playerName = context.body("player") ?: return ApiResponse.error("缺少参数: player")
        val reason = context.body("reason") ?: "管理员踢出"

        val player = Bukkit.getPlayer(playerName)
            ?: return ApiResponse.error("玩家不在线")

        player.kickPlayer(reason)

        return ApiResponse.success(mapOf("kicked" to true))
    }
}
```

**性能保护**：
- **熔断**：TPS < 12 时停止处理新任务
- **容量限制**：队列最大 500 个任务
- **超时**：任务等待超过 10 秒返回 503
- **慢任务监控**：执行超过 10ms 会打印警告

---

### 3. 直接提交任务到主线程

如果你需要在非路由处理器中执行 Bukkit API：

```kotlin
import org.ruge.coreapi.CoreAPI
import java.util.concurrent.CompletableFuture

fun someFunction() {
    val future: CompletableFuture<Int> = CoreAPI.submitTask {
        // ✅ 这里是主线程
        val onlineCount = Bukkit.getOnlinePlayers().size
        onlineCount
    }

    // 等待结果（带超时）
    try {
        val count = future.get(3, TimeUnit.SECONDS)
        logger.info("在线玩家数: $count")
    } catch (e: TimeoutException) {
        logger.warning("任务超时")
    } catch (e: RejectedExecutionException) {
        logger.warning("任务队列已满")
    }
}
```

---

### 4. 路由最佳实践

#### 4.1 路径命名规范
```
✅ 推荐：
/myapi/players          # 列出所有玩家
/myapi/players/123      # 获取特定玩家
/myapi/admin/kick       # 管理员操作

❌ 避免：
/MyApi/GetPlayers       # 不要大写和动词
/api                    # 太短，容易冲突
/test                   # 不要用通用名称
```

#### 4.2 HTTP 方法选择
```
GET    - 查询数据（不修改状态）
POST   - 创建资源、执行操作
PUT    - 更新整个资源
PATCH  - 更新部分资源
DELETE - 删除资源
```

#### 4.3 错误处理
```kotlin
class SafeHandler : SyncRouteHandler() {
    override fun handleSync(context: RequestContext): ApiResponse {
        return try {
            val result = doSomething()
            ApiResponse.success(result)
        } catch (e: IllegalArgumentException) {
            // 客户端错误（400）
            ApiResponse.error(e.message ?: "参数错误")
        } catch (e: Exception) {
            // 服务器错误（500）- 不要暴露详细错误信息
            logger.log(Level.SEVERE, "处理请求失败", e)
            ApiResponse.error("服务器内部错误")
        }
    }
}
```

#### 4.4 性能建议
```kotlin
// ✅ SyncRouteHandler 用于 I/O 操作（< 100ms）
class FastHandler : SyncRouteHandler() {
    override fun handleSync(context: RequestContext): ApiResponse {
        // 数据库查询、HTTP 请求、文件读取等
        val data = database.query("SELECT ...")
        return ApiResponse.success(data)
    }
}

// ✅ BukkitSyncRouteHandler 用于 Bukkit API（< 10ms）
class BukkitHandler : BukkitSyncRouteHandler() {
    override fun handleBukkit(context: RequestContext): ApiResponse {
        // 快速操作：获取玩家信息、发送消息等
        val player = Bukkit.getPlayer(...)
        return ApiResponse.success(...)
    }
}

// ❌ 避免在 BukkitSyncRouteHandler 中执行耗时操作
class SlowHandler : BukkitSyncRouteHandler() {
    override fun handleBukkit(context: RequestContext): ApiResponse {
        // ❌ 错误：会卡住整个服务器
        Thread.sleep(5000)
        // ❌ 错误：耗时的数据库操作
        database.slowQuery()
        return ApiResponse.success(...)
    }
}
```

---

## 6. 常见问题

### 1. 为什么登录失败返回 "AuthMe 未启用"？

**原因**：服务器没有安装 AuthMe 插件。

**解决方案**：
- 方案 1：安装 AuthMe 插件
- 方案 2：使用自定义认证，不使用 `/login` 和 `/register` 接口

### 2. 为什么访问 /status 返回 "权限不足"？

**原因**：用户没有对应权限。

**解决方案**：
```bash
# 授予特定路由权限
/lp user player123 permission set coreapi.route.coreapi.status true

# 或授予所有 CoreAPI 路由权限
/lp user player123 permission set coreapi.route.coreapi.* true
```

### 3. 为什么限流总是触发？

**可能原因**：
1. 客户端请求过快（> 5 req/s）
2. 多个客户端共享同一 IP（NAT）

**解决方案**：
```yaml
# 调整限流配置
rate-limit:
  requests-per-second: 10.0  # 提高到 10 req/s
```

### 4. 为什么 Bukkit API 调用报错 "Asynchronous entity add"？

**原因**：在 HTTP 线程中调用了 Bukkit API。

**解决方案**：
- 使用 `BukkitSyncRouteHandler` 替代 `SyncRouteHandler`
- 或使用 `CoreAPI.submitTask { ... }`

### 5. 为什么任务队列总是满？

**可能原因**：
1. 任务处理太慢（> 10ms）
2. 请求量过大
3. TPS 过低触发熔断

**解决方案**：
```yaml
# 增加队列容量
scheduler:
  max-queue-size: 1000

# 增加每 tick 处理数
scheduler:
  max-tasks-per-tick: 100

# 降低熔断阈值（谨慎）
scheduler:
  min-tps-threshold: 10.0
```

### 6. 为什么启动时报错 "SecurityException: 请修改 JWT 密钥"？

**原因**：使用了默认 JWT 密钥或弱密钥。

**解决方案**：
```bash
# 生成强密钥
openssl rand -base64 48

# 修改 config.yml
jwt:
  secret: "生成的密钥"
```

### 7. 为什么反向代理后限流失效？

**原因**：`trust-proxy` 配置错误。

**检查清单**：
1. 是否使用了 Nginx/Cloudflare 等反向代理？
   - 是 → `trust-proxy: true`
   - 否 → `trust-proxy: false`（默认）

2. Nginx 是否传递了 `X-Forwarded-For` 头？
   ```nginx
   proxy_set_header X-Forwarded-For $remote_addr;
   ```

3. 是否直接暴露在公网？
   - 是 → **永远不要**设置 `trust-proxy: true`（安全风险！）

---

## 7. 故障排查

### 日志位置
```
plugins/CoreAPI/logs/latest.log
```

### 常见日志消息

#### 正常启动
```
[CoreAPI] 正在启动 CoreAPI...
[CoreAPI] HTTP 服务器正在启动，端口：8080
[CoreAPI] HTTP 服务器已成功启动
[CoreAPI] 已注册路由：POST /login (CoreAPI)
[CoreAPI] 已注册路由：POST /register (CoreAPI)
[CoreAPI] 已注册路由：GET /status (CoreAPI)
[CoreAPI] 已注册路由：GET /routes (CoreAPI)
```

#### JWT 密钥错误
```
[CoreAPI] SecurityException: 请修改 JWT 密钥！使用默认密钥极不安全！
[CoreAPI] 插件加载失败
```
**解决**：修改 `config.yml` 中的 `jwt.secret`。

#### 端口占用
```
[CoreAPI] IOException: Address already in use: bind
[CoreAPI] HTTP 服务器启动失败
```
**解决**：修改 `config.yml` 中的 `server.port`，或关闭占用端口的程序。

#### 慢任务警告
```
[CoreAPI] [警告] 慢任务检测：任务执行耗时 15ms（阈值：10ms）
```
**影响**：可能导致服务器卡顿。
**解决**：优化路由处理器代码，减少主线程执行时间。

#### 熔断触发
```
[CoreAPI] [熔断] 当前 TPS 11.2 低于阈值 12.0，停止处理任务
```
**影响**：HTTP 请求返回 503。
**解决**：
1. 检查服务器性能（CPU、内存）
2. 优化卡顿的插件
3. 降低 `min-tps-threshold`（谨慎）

#### 队列满
```
[CoreAPI] RejectedExecutionException: 任务队列已满（500/500）
```
**影响**：HTTP 请求返回 503。
**解决**：增加 `max-queue-size` 或 `max-tasks-per-tick`。

---

## 8. 安全建议

### 1. JWT 密钥管理
```
✅ 推荐：
- 使用 openssl rand -base64 48 生成
- 长度 ≥ 48 字符
- 包含大小写字母、数字、特殊字符
- 定期更换（3-6 个月）

❌ 禁止：
- 使用默认密钥
- 使用简单密码（"password123"）
- 在代码中硬编码
- 分享给他人
```

### 2. 反向代理配置
```
✅ 推荐：
- 使用 Nginx/Cloudflare 反向代理
- 启用 HTTPS（SSL/TLS）
- 限制请求体大小（client_max_body_size 1m）
- 设置超时时间（proxy_read_timeout 10s）

❌ 禁止：
- trust-proxy=true 但服务器直接暴露在公网
- 信任所有 X-Forwarded-For 头
- 允许无限大请求体
```

### 3. 权限管理
```
✅ 推荐：
- 使用最小权限原则
- 为每个路由单独授权
- 使用 LuckPerms 组管理权限
- 定期审计权限

❌ 禁止：
- 授予 coreapi.route.* 给普通玩家
- 允许未认证访问敏感接口
- 共享管理员账号
```

### 4. 限流策略
```
✅ 推荐：
- 生产环境永远启用限流
- 根据实际流量调整 requests-per-second
- 监控限流日志，及时发现攻击

❌ 禁止：
- 生产环境禁用限流
- 设置过高的限流值（> 50 req/s）
```

---

## 9. 性能优化

### 1. 路由处理器优化
```kotlin
// ✅ 好：快速返回
class FastHandler : SyncRouteHandler() {
    private val cache = ConcurrentHashMap<String, String>()

    override fun handleSync(context: RequestContext): ApiResponse {
        val key = context.param("key") ?: return ApiResponse.error("缺少参数")
        val value = cache[key] ?: return ApiResponse.error("未找到")
        return ApiResponse.success(mapOf("value" to value))
    }
}

// ❌ 差：每次都查询数据库
class SlowHandler : SyncRouteHandler() {
    override fun handleSync(context: RequestContext): ApiResponse {
        val key = context.param("key") ?: return ApiResponse.error("缺少参数")
        val value = database.query("SELECT ...") // 慢！
        return ApiResponse.success(mapOf("value" to value))
    }
}
```

### 2. 配置优化（高性能服务器）
```yaml
scheduler:
  max-queue-size: 2000
  max-tasks-per-tick: 200
  min-tps-threshold: 18.0

rate-limit:
  requests-per-second: 20.0
```

### 3. JVM 参数优化
```bash
# 增加堆内存
java -Xms4G -Xmx4G -jar server.jar

# 使用 G1GC（减少卡顿）
java -XX:+UseG1GC -jar server.jar
```

---

## 10. API 测试工具

### 使用 curl
```bash
# GET 请求
curl http://localhost:8080/routes

# POST 请求
curl -X POST http://localhost:8080/login \
  -H "Content-Type: application/json" \
  -d '{"username": "test", "password": "Test123456"}'

# 带认证的请求
curl -X GET http://localhost:8080/status \
  -H "Authorization: Bearer eyJhbGci..."
```

### 使用 Postman
1. 导入 Collection：
   ```json
   {
     "info": {"name": "CoreAPI"},
     "item": [
       {
         "name": "Login",
         "request": {
           "method": "POST",
           "url": "http://localhost:8080/login",
           "body": {
             "mode": "raw",
             "raw": "{\"username\": \"test\", \"password\": \"Test123456\"}"
           }
         }
       }
     ]
   }
   ```

2. 设置环境变量：
   - `base_url`: `http://localhost:8080`
   - `token`: `{{token}}`（从登录响应中提取）

---


## 11. 总结

CoreAPI 提供了一个**生产级的 HTTP API 网关**，让 Minecraft 插件开发者专注于业务逻辑，而不是基础设施。

**关键特性**：
- ✅ 统一的 HTTP 服务器（Jetty）
- ✅ 完整的认证和权限系统（JWT + AuthMe + LuckPerms）
- ✅ 线程安全的任务调度（HTTP 线程 ↔ Bukkit 主线程）
- ✅ 防护机制（限流、熔断、防暴力破解）
- ✅ 热重载支持（插件卸载自动清理路由）
- ✅ 详尽的日志和监控

**快速开始**：
1. 安装 CoreAPI.jar
2. 修改 JWT 密钥
3. 重启服务器
4. 访问 http://localhost:8080/routes

**需要帮助？** 查看 [常见问题](#常见问题) 或 [故障排查](#故障排查)。
