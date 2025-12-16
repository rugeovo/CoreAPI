# CoreAPI

**Minecraft Bukkit/Spigot HTTP API 框架**

为 Minecraft 服务器提供 RESTful API 接口，支持 JWT 认证、权限管理、限流保护和 TPS 自适应调度。

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/minecraft-1.12+-green.svg)](https://www.spigotmc.org/)
[![Java](https://img.shields.io/badge/java-8+-orange.svg)](https://adoptium.net/)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2+-purple.svg)](https://kotlinlang.org/)

---

## ⚠️ 安全警告

**部署前必读：**

1. **JWT 密钥必须修改** - 默认配置无法启动，必须生成随机密钥
2. **HTTP 明文传输** - 密码和 token 可被窃听，生产环境必须使用 HTTPS
3. **代理配置** - 错误的 `trust-proxy` 设置会导致限流被绕过

详见 [生产部署检查清单](#生产部署检查清单)

---

## 核心特性

### TPS 自适应调度

Minecraft 主线程每 tick 只有 50ms，传统 API 插件会阻塞主线程导致卡顿。CoreAPI 使用队列 + 看门狗机制：

- **定量处理**：每 tick 最多处理 N 个任务（可配置）
- **熔断保护**：TPS < 12 时自动停止 API 处理
- **慢任务监控**：超过 10ms 的任务会触发告警
- **队列积压警告**：防止内存溢出

### 安全机制

| 防护层级 | 机制 | 配置 |
|---------|-----|------|
| **网络层** | HTTPS (需外部配置) | Nginx/Cloudflare |
| **传输层** | IP 限流 | 5 req/s/IP (全局) |
| **认证层** | JWT Token | HS256 签名，24h 过期 |
| **登录保护** | 独立限流 + 失败锁定 | 1 req/s/IP，5 次失败锁 15 分钟 |
| **授权层** | LuckPerms 权限 | 细粒度路由权限 |
| **应用层** | 输入验证 + DoS 防护 | 请求体 1MB 限制 |

### 技术栈

- **HTTP 服务器**：Jetty 11.0.20 (5-20 线程池，3s 超时)
- **JWT 实现**：JJWT 0.12.3 (HS256 签名)
- **限流**：Guava RateLimiter
- **认证集成**：AuthMe 5.6.1
- **权限集成**：LuckPerms 5.4

### 内置 API

| 路径 | 方法 | 认证 | 说明 |
|-----|------|-----|------|
| `/login` | POST | 否 | 用户登录，返回 JWT Token |
| `/register` | POST | 否 | 用户注册，自动登录 |
| `/status` | GET | 是 | 服务器状态 (TPS/队列/在线人数) |
| `/routes` | GET | 否 | 所有已注册的路由列表 |

---

## 快速开始

### 依赖要求

| 组件 | 版本 | 必需 | 说明 |
|-----|------|-----|------|
| Minecraft | Bukkit/Spigot 1.12+ | 是 | 推荐 Paper |
| Java | JDK 8+ | 是 | |
| TabooLib | 6.2.4+ | 是 | 自动下载 |
| AuthMe | 5.6.1+ | 否 | 不装则无法使用 /login /register |
| LuckPerms | 5.4+ | 否 | 不装则 requireAuth 无效 |

### 安装

```bash
# 1. 下载插件到 plugins/ 目录
wget https://github.com/your-repo/CoreAPI/releases/latest/download/CoreAPI.jar -P plugins/

# 2. 启动服务器（首次启动会生成默认配置）
java -jar server.jar

# 3. 停止服务器，修改配置文件
```

### 核心配置

编辑 `plugins/CoreAPI/config.yml`：

```yaml
# JWT 密钥 - 必须修改！
jwt:
  secret: "生成的随机字符串"  # 使用 openssl rand -base64 48 生成
  expiration-hours: 24

# HTTP 服务器
server:
  port: 8080
  enabled: true
  trust-proxy: false        # 仅在 Nginx/Cloudflare 后设为 true
  max-body-size-bytes: 1048576
  cors-origin: "none"       # 生产环境保持 "none"

# 限流
rate-limit:
  enabled: true
  requests-per-second: 5.0  # 每 IP 每秒请求数

# 安全
auth:
  max-login-attempts: 5     # 登录失败次数
  lockout-minutes: 15       # 锁定时长
```

**生成 JWT 密钥**：
```bash
# Linux/Mac/WSL
openssl rand -base64 48

# Windows PowerShell
[Convert]::ToBase64String((1..48 | %{ Get-Random -Max 256 }))
```

### 验证安装

```bash
# 测试注册
curl -X POST http://localhost:8080/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"Test12345"}'

# 应返回：
# {"success":true,"data":{"token":"eyJ...","uuid":"...","username":"test"},"timestamp":...}

# 测试状态接口（需要 token）
TOKEN="上一步返回的token"
curl http://localhost:8080/status -H "Authorization: Bearer $TOKEN"
```

---

## 生产部署检查清单

### 必须完成项 (无法启动/严重安全风险)

- [ ] **修改 JWT 密钥**
  ```bash
  # 生成密钥
  openssl rand -base64 48
  # 替换 config.yml 中的 jwt.secret
  ```

- [ ] **配置 HTTPS**

  **方案 A: Nginx 反向代理**
  ```nginx
  server {
      listen 443 ssl http2;
      server_name api.yourserver.com;

      ssl_certificate /path/to/fullchain.pem;
      ssl_certificate_key /path/to/privkey.pem;

      location / {
          proxy_pass http://localhost:8080;
          proxy_set_header X-Forwarded-For $remote_addr;
          proxy_set_header X-Real-IP $remote_addr;
          proxy_set_header Host $host;
      }
  }
  ```
  然后设置 `trust-proxy: true`

  **方案 B: Cloudflare**
  1. 域名接入 Cloudflare
  2. SSL/TLS 模式设为 "完全(严格)"
  3. 设置 `trust-proxy: true`

- [ ] **验证 CORS 配置**
  - 生产环境：`cors-origin: "none"` 或具体域名
  - 开发环境：`cors-origin: "*"`（测试完立即改回）

### 强烈建议项 (生产环境标准)

- [ ] **限制服务器访问**
  ```bash
  # 方案 A: 防火墙限制
  # 仅允许 Nginx/Cloudflare 的 IP 访问 8080 端口

  # 方案 B: 绑定内网 IP
  # 修改 Jetty 监听地址（需改代码）
  ```

- [ ] **配置日志轮转**
  - 使用 logback 或 log4j2
  - 按日期/大小分割日志
  - 保留 30 天

- [ ] **监控告警**
  - 定时检查 `/status` 接口
  - 监控 TPS 和队列积压
  - 设置告警阈值

- [ ] **备份配置文件**
  ```bash
  cp plugins/CoreAPI/config.yml plugins/CoreAPI/config.yml.bak
  ```

### 可选优化项

- [ ] 根据服务器性能调整参数
  ```yaml
  scheduler:
    max-queue-size: 500      # 高配可调到 1000
    max-tasks-per-tick: 50   # 高配可调到 100
  rate-limit:
    requests-per-second: 5.0 # 根据实际需求调整
  ```

- [ ] 安装依赖插件以启用完整功能
  - AuthMe: 启用 /login /register
  - LuckPerms: 启用权限验证

---

## 开发者指南

### 路由处理器类型

CoreAPI 提供三种处理器，根据操作类型选择：

| 类型 | 适用场景 | 线程 | 示例 |
|-----|---------|------|------|
| `SyncRouteHandler` | 只读操作，不修改游戏状态 | HTTP 线程 | 查询统计、获取配置 |
| `BukkitSyncRouteHandler` | 修改游戏状态 | Bukkit 主线程 | 踢出玩家、发送消息 |
| 自定义 | 完全控制线程调度 | 自定义 | 异步数据库查询 |

### 示例 1: 只读接口

**场景**：查询插件信息（不需要访问 Bukkit API）

```kotlin
import org.ruge.coreapi.CoreAPI
import org.ruge.coreapi.http.*

class MyPlugin : JavaPlugin() {
    override fun onEnable() {
        val coreAPI = server.pluginManager.getPlugin("CoreAPI") as CoreAPI
        val registry = coreAPI.getRouteRegistry()

        // 注册 GET /myplugin/info
        registry.registerGet(this, "/myplugin/info", object : SyncRouteHandler() {
            override fun handleSync(context: RequestContext): ApiResponse {
                // 在 HTTP 线程执行，只能读取线程安全的数据
                return ApiResponse.success(mapOf(
                    "name" to description.name,
                    "version" to description.version,
                    "author" to description.authors.joinToString()
                ))
            }
        }, requireAuth = false)
    }
}
```

**测试**：
```bash
curl http://localhost:8080/myplugin/info
```

### 示例 2: 修改游戏状态

**场景**：广播消息（需要调用 Bukkit API）

```kotlin
import org.bukkit.Bukkit

registry.registerPost(this, "/myplugin/broadcast", object : BukkitSyncRouteHandler() {
    override fun handleBukkit(context: RequestContext): ApiResponse {
        // 自动在 Bukkit 主线程执行，可以安全调用 Bukkit API

        val message = context.getParam("message")
            ?: return ApiResponse.error("缺少参数: message")

        Bukkit.broadcastMessage("§e[API] §f$message")

        return ApiResponse.success(mapOf(
            "recipients" to Bukkit.getOnlinePlayers().size
        ))
    }
}, requireAuth = true)  // 需要认证
```

**权限配置** (需要 LuckPerms):
```bash
lp user steve permission set coreapi.route.myplugin.broadcast
```

**测试**：
```bash
# 1. 登录获取 token
TOKEN=$(curl -s -X POST http://localhost:8080/login \
  -H "Content-Type: application/json" \
  -d '{"username":"steve","password":"yourpass"}' | jq -r '.data.token')

# 2. 调用接口
curl -X POST "http://localhost:8080/myplugin/broadcast?message=Hello" \
  -H "Authorization: Bearer $TOKEN"
```

### 示例 3: JSON 请求体

```kotlin
import com.google.gson.Gson

data class KickRequest(val player: String, val reason: String)

registry.registerPost(this, "/myplugin/kick", object : BukkitSyncRouteHandler() {
    override fun handleBukkit(context: RequestContext): ApiResponse {
        val body = context.body ?: return ApiResponse.error("请求体不能为空")

        val req = try {
            Gson().fromJson(body, KickRequest::class.java)
        } catch (e: Exception) {
            return ApiResponse.error("JSON 格式错误")
        }

        val player = Bukkit.getPlayerExact(req.player)
            ?: return ApiResponse.error("玩家不在线")

        player.kickPlayer(req.reason)

        return ApiResponse.success(mapOf("kicked" to req.player))
    }
}, requireAuth = true)
```

**测试**：
```bash
curl -X POST http://localhost:8080/myplugin/kick \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"player":"Alex","reason":"违规"}'
```

### RequestContext API

```kotlin
val context: RequestContext

context.method          // HttpMethod: GET/POST/PUT/DELETE
context.uri             // String: "/myplugin/test"
context.headers         // Map<String, String>
context.params          // Map<String, String>  (URL 参数)
context.body            // String? (仅 POST/PUT)
context.clientIp        // String (尊重 trust-proxy 配置)

context.getHeader("content-type")
context.getParam("id")
context.getAuthToken()  // 自动解析 "Bearer xxx"
```

### 权限节点规则

默认格式：`coreapi.route.<plugin>.<path>`

| 路径 | 权限节点 |
|-----|---------|
| `/myplugin/info` | `coreapi.route.myplugin.info` |
| `/myplugin/admin/ban` | `coreapi.route.myplugin.admin.ban` |

**自定义权限节点**：
```kotlin
registry.registerPost(
    this,
    "/admin/op",
    handler,
    requireAuth = true,
    permission = "myplugin.admin.super"  // 覆盖默认规则
)
```

---

## 架构设计

### 请求处理流程

```
HTTP Request
    │
    ▼
[IP 限流] ───→ 429 Too Many Requests
    │
    ▼
[路由查找] ───→ 404 Not Found
    │
    ▼
[JWT 验证] ───→ 401 Unauthorized / 403 Forbidden
    │
    ▼
[执行处理器]
    ├─→ SyncRouteHandler ─────→ 直接返回
    └─→ BukkitSyncRouteHandler
            │
            ▼
      [TaskScheduler 队列]
            │
            ├─→ 队列满 ───→ 503 Service Unavailable
            ├─→ TPS < 12 ─→ 503 (熔断)
            │
            ▼
      [Bukkit 主线程执行]
            │
            ├─→ 成功 ───→ 200 OK
            ├─→ 超时 ───→ 500 Timeout
            └─→ 异常 ───→ 500 Internal Server Error
```

### TPS 自适应调度

```kotlin
每个 Tick (50ms):
    1. 获取当前 TPS
    2. 熔断检查：
       - TPS < 12.0 → 停止处理，避免雪上加霜
       - TPS >= 12.0 → 继续处理
    3. 定量处理：
       - 每 tick 最多处理 N 个任务（默认 50）
       - 避免单 tick 处理数千任务导致瞬时卡顿
    4. 看门狗监控：
       - 任务超过 10ms → 告警
       - 帮助发现慢任务
```

### 核心组件

| 组件 | 职责 | 关键实现 |
|-----|------|---------|
| **CoreHttpServer** | HTTP 服务器 | Jetty 11.0.20, 5-20 线程池 |
| **RouteRegistry** | 路由管理 | ConcurrentHashMap, 插件隔离 |
| **TaskScheduler** | 主线程调度 | TPS 熔断, 定量处理, 看门狗 |
| **JwtManager** | JWT 认证 | HS256 签名, 密钥强度验证 |
| **AuthService** | 登录服务 | AuthMe 集成, 防暴力破解 |
| **AuthManager** | 权限验证 | LuckPerms 集成 |
| **RateLimitManager** | 限流 | Guava RateLimiter, 每 IP 独立 |

---

## 性能与限制

### 性能指标

| 场景 | TPS 影响 | 吞吐量 | 延迟 |
|------|---------|-------|------|
| 低负载 (<10 req/s) | 无影响 | 50-100 req/s | <50ms |
| 中负载 (10-50 req/s) | <0.1 TPS | 100-200 req/s | <100ms |
| 高负载 (>100 req/s) | <0.5 TPS | 200-500 req/s | <200ms |

*测试环境：Intel i5-9400F（6核），16GB RAM，Paper 1.20.1*

### 系统限制

| 限制项 | 默认值 | 说明 |
|-------|--------|-----|
| 队列容量 | 500 | 超出返回 503 |
| HTTP 超时 | 3 秒 | Jetty 层 |
| 任务超时 | 10 秒 | TaskScheduler 层 |
| 请求体大小 | 1MB | 防止 DoS |
| 限流速率 | 5 req/s/IP | 可配置 |

### 最佳实践

**✅ 推荐**
- 只读操作用 `SyncRouteHandler`（更快，无需排队）
- 修改游戏状态用 `BukkitSyncRouteHandler`（自动主线程调度）
- 批量操作代替大量单次请求
- 客户端实现 503 错误重试逻辑

**❌ 不推荐**
- 在 `SyncRouteHandler` 中调用 Bukkit API（会报错）
- 在处理器中执行长时间阻塞操作（数据库查询、HTTP 请求）
- 忽略 503 错误（应实现重试+退避算法）

---

## 常见问题

### Q: 如何获取在线玩家列表？

```kotlin
registry.registerGet(this, "/players", object : SyncRouteHandler() {
    override fun handleSync(context: RequestContext): ApiResponse {
        // 注意：Bukkit.getOnlinePlayers() 是线程安全的
        val players = Bukkit.getOnlinePlayers().map {
            mapOf("name" to it.name, "uuid" to it.uniqueId.toString())
        }
        return ApiResponse.success(mapOf("players" to players))
    }
}, requireAuth = true)
```

### Q: 如何处理长时间任务（如数据库查询）？

使用自定义线程池，不要阻塞 Bukkit 主线程：

```kotlin
val executor = Executors.newFixedThreadPool(4)

registry.registerGet(this, "/stats", object : RouteHandler {
    override fun handle(context: RequestContext): CompletableFuture<ApiResponse> {
        return CompletableFuture.supplyAsync({
            // 在独立线程执行数据库查询
            val stats = database.query("SELECT * FROM stats")
            ApiResponse.success(stats)
        }, executor)
    }
}, requireAuth = true)
```

### Q: 服务器启动失败，提示 JWT 密钥错误？

检查 `config.yml` 中的 `jwt.secret`，必须：
- 不能是默认值
- 不能包含 CHANGE/DEFAULT/SECRET 等关键词
- 长度至少 32 字符

使用 `openssl rand -base64 48` 生成新密钥。

### Q: 为什么返回 503 Service Unavailable？

两种可能：
1. **队列满了**：任务积压超过 500 个，说明处理速度跟不上请求速度
2. **TPS 过低**：服务器卡顿（TPS < 12），触发熔断保护

解决方案：
- 检查日志中的慢任务告警
- 调整 `max-queue-size` 和 `max-tasks-per-tick`
- 优化插件代码，减少主线程阻塞

### Q: 如何调试权限问题？

```bash
# 查看用户权限
lp user steve permission info

# 添加权限
lp user steve permission set coreapi.route.myplugin.test

# 查看权限节点生成规则
# 路径: /myplugin/admin/ban
# 权限节点: coreapi.route.myplugin.admin.ban
```

---

## 构建与开发

```bash
# 构建
./gradlew build

# 输出在 build/libs/CoreAPI-*.jar

# 本地测试
java -Xms2G -Xmx2G -jar paper-1.20.1.jar
```

**代码风格**：
- 4 空格缩进
- 函数保持简短（<50 行）
- 避免深层嵌套（<3 层）
- 优先考虑数据结构设计

---

## 许可证

MIT License - 详见 [LICENSE](LICENSE)

---

## 致谢

- **TabooLib** - Bukkit 插件开发框架
- **Jetty** - HTTP 服务器
- **JJWT** - JWT 实现
- **Guava** - 限流器
- **AuthMe** - 认证系统
- **LuckPerms** - 权限管理

---

## 支持

- **Issues**: [GitHub Issues](https://github.com/your-repo/CoreAPI/issues)
- **Discussions**: [GitHub Discussions](https://github.com/your-repo/CoreAPI/discussions)

---

**Built for the Minecraft community.**
