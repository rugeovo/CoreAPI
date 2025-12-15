# 贡献指南

感谢你考虑为 CoreAPI 做出贡献！本指南将帮助你了解如何参与项目开发。

---

## 📋 目录

- [行为准则](#行为准则)
- [如何贡献](#如何贡献)
  - [报告 Bug](#报告-bug)
  - [提出功能建议](#提出功能建议)
  - [提交代码](#提交代码)
- [开发环境设置](#开发环境设置)
- [代码风格指南](#代码风格指南)
- [提交规范](#提交规范)
- [Pull Request 流程](#pull-request-流程)
- [社区资源](#社区资源)

---

## 行为准则

### 我们的承诺

为了营造一个开放和友好的环境，我们承诺让参与项目和社区的每个人都不受骚扰，无论其年龄、体型、残疾、种族、性别、经验水平、国籍、个人外貌、种族、宗教或性别认同和取向。

### 我们的标准

**积极行为包括**：
- 使用友好和包容的语言
- 尊重不同的观点和经验
- 优雅地接受建设性批评
- 专注于对社区最有利的事情
- 对其他社区成员表示同理心

**不可接受的行为包括**：
- 使用性暗示的语言或图像
- 挑衅、侮辱/贬损性评论，人身或政治攻击
- 公开或私下骚扰
- 未经明确许可发布他人的私人信息
- 其他在专业环境中可能被认为不适当的行为

---

## 如何贡献

### 报告 Bug

在报告 Bug 前，请先：
1. 检查 [Issues](https://github.com/your-repo/CoreAPI/issues) 看是否已有人报告
2. 确保使用最新版本的 CoreAPI 和 TabooLib
3. 尝试在干净的测试环境中重现问题

**Bug 报告应包含**：

```markdown
**描述**
清晰简洁地描述 Bug

**复现步骤**
1. 启动服务器
2. 访问 '/xxx' 接口
3. 看到错误

**预期行为**
应该返回成功响应

**实际行为**
返回 500 错误

**环境信息**
- CoreAPI 版本: [如 v1.0.0]
- Minecraft 版本: [如 1.20.1]
- 服务器核心: [如 Spigot/Paper]
- Java 版本: [如 Java 8]
- TabooLib 版本: [如 6.2.4]

**日志输出**
```
[粘贴相关日志]
```

**额外信息**
其他可能有帮助的信息
```

### 提出功能建议

**功能建议应包含**：

```markdown
**功能描述**
清晰描述你希望添加的功能

**使用场景**
描述这个功能解决什么问题，为什么需要它

**期望的API设计**
```kotlin
// 示例代码展示你期望的使用方式
registry.registerRoute(...)
```

**替代方案**
是否考虑过其他实现方式？

**优先级**
- [ ] 必须有（服务器无法正常运行）
- [ ] 重要（显著改善体验）
- [x] 建议（锦上添花）
```

### 提交代码

我们欢迎以下类型的贡献：

- **Bug 修复**：修复已知问题
- **功能增强**：添加新功能或改进现有功能
- **性能优化**：提升代码性能
- **文档改进**：完善文档、注释、示例
- **测试用例**：增加测试覆盖率
- **代码重构**：提升代码质量（不改变功能）

---

## 开发环境设置

### 前置要求

- **JDK 8 或更高版本**
- **IntelliJ IDEA**（推荐）或其他 Kotlin IDE
- **Git**
- **Gradle**（项目自带 wrapper）

### 克隆仓库

```bash
# Fork 仓库后克隆你的 fork
git clone https://github.com/YOUR_USERNAME/CoreAPI.git
cd CoreAPI

# 添加上游仓库
git remote add upstream https://github.com/your-repo/CoreAPI.git
```

### 构建项目

```bash
# 构建项目
./gradlew build

# 运行测试（如果有）
./gradlew test
```

### 导入 IDE

**IntelliJ IDEA**：
1. File → Open → 选择项目根目录
2. 等待 Gradle 同步完成
3. 右键 `build.gradle.kts` → Link Gradle Project

**Eclipse**：
```bash
./gradlew eclipse
```

---

## 代码风格指南

### Kotlin 风格

遵循 [Kotlin 官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)。

**关键原则**：

1. **命名规范**
   ```kotlin
   // 类名：大驼峰
   class TaskScheduler

   // 函数/变量：小驼峰
   fun registerRoute()
   val queueSize = 10

   // 常量：大写下划线
   const val MAX_QUEUE_SIZE = 500

   // 私有成员：小驼峰（不用下划线）
   private val taskQueue = ...
   ```

2. **函数长度**
   - 保持函数简短（理想 <20 行，最多 <50 行）
   - 一个函数只做一件事
   - 超过 3 层缩进时考虑提取函数

3. **注释规范**
   ```kotlin
   /**
    * 公开 API 必须有 KDoc 注释
    *
    * @param plugin 注册路由的插件
    * @param path 路由路径
    * @return 注册是否成功
    */
   fun registerRoute(plugin: Plugin, path: String): Boolean {
       // 内部逻辑使用单行注释解释"为什么"，而不是"是什么"
       // 好注释：检查路由冲突，确保每个路径只有一个处理器
       // 坏注释：检查 routes 是否包含 key
       if (routes.containsKey(routeKey)) {
           throw IllegalStateException("路由冲突")
       }
   }
   ```

4. **Kotlin 特性使用**
   ```kotlin
   // ✅ 使用数据类
   data class RouteInfo(val path: String, val handler: RouteHandler)

   // ✅ 使用 object 声明单例
   object TPSMonitor : Runnable { ... }

   // ✅ 使用 elvis 操作符
   val ip = req.getHeader("X-Forwarded-For") ?: req.remoteAddr

   // ✅ 使用作用域函数
   val server = Server(threadPool).apply {
       addConnector(connector)
   }

   // ❌ 避免过度使用 !! 操作符
   val plugin = Bukkit.getPlugin("CoreAPI")!! // 不好
   val plugin = Bukkit.getPlugin("CoreAPI") ?: error("Plugin not found") // 更好
   ```

### "Good Taste" 原则

参考 Linus Torvalds 的编程哲学：

1. **数据结构优先于算法**
   ```kotlin
   // 好：用正确的数据结构消除特殊情况
   private val routes = ConcurrentHashMap<String, RouteInfo>()

   // 而不是：用 if/else 处理各种边界情况
   ```

2. **消除特殊情况**
   ```kotlin
   // 好：统一的路径规范化
   private fun normalizePath(path: String): String {
       return path.trim().lowercase()
           .let { if (!it.startsWith("/")) "/$it" else it }
           .let { if (it.length > 1 && it.endsWith("/")) it.dropLast(1) else it }
   }

   // 坏：到处检查路径格式
   if (!path.startsWith("/")) { ... }
   if (path.endsWith("/")) { ... }
   ```

3. **简洁优于聪明**
   ```kotlin
   // 好：清晰明了
   return when {
       tps < 18.0 -> 0
       tps < 19.0 -> 3
       else -> maxMsPerTick
   }

   // 坏：过度优化
   return ((20.0 - tps) * magicConstant).toInt().coerceIn(0, maxMsPerTick)
   ```

---

## 提交规范

### Commit Message 格式

```
<type>(<scope>): <subject>

<body>

<footer>
```

**Type（必需）**：
- `feat`: 新功能
- `fix`: Bug 修复
- `docs`: 仅文档更改
- `style`: 代码格式（不影响功能）
- `refactor`: 重构（不新增功能也不修复 bug）
- `perf`: 性能优化
- `test`: 添加测试
- `chore`: 构建/工具相关

**Scope（可选）**：
- `http`: HTTP 服务器
- `scheduler`: 任务调度器
- `route`: 路由系统
- `ratelimit`: 限流
- `config`: 配置

**Subject（必需）**：
- 使用祈使句，首字母小写
- 不超过 50 字符
- 不加句号

**示例**：

```
feat(scheduler): 添加队列容量监控

- 新增 getAvailableCapacity() 方法
- 在 /status 接口中暴露剩余容量
- 添加容量告警日志

Closes #42
```

```
fix(http): 修复 CORS 头重复设置问题

将重复的 CORS 代码抽取到 applyCorsHeaders() 方法。

Fixes #123
```

---

## Pull Request 流程

### 1. 创建功能分支

```bash
# 同步上游最新代码
git fetch upstream
git checkout master
git merge upstream/master

# 创建功能分支
git checkout -b feature/your-feature-name
```

### 2. 开发和测试

```bash
# 开发过程中频繁提交
git add .
git commit -m "feat: xxx"

# 构建测试
./gradlew build

# 在测试服务器上验证
```

### 3. 推送分支

```bash
git push origin feature/your-feature-name
```

### 4. 创建 Pull Request

在 GitHub 上创建 PR，填写以下信息：

**PR 标题**：遵循 commit message 格式
```
feat(scheduler): 添加队列容量监控
```

**PR 描述模板**：
```markdown
## 变更类型
- [ ] Bug 修复
- [x] 新功能
- [ ] 重构
- [ ] 文档更新

## 变更说明
清晰描述你做了什么改动

## 相关 Issue
Closes #42

## 测试
- [x] 在本地测试环境验证
- [x] 添加了相关注释
- [x] 代码遵循项目风格
- [ ] 添加了测试用例

## 截图/日志
（如果适用）

## 额外说明
其他需要说明的信息
```

### 5. Code Review

- 响应审查意见，进行必要的修改
- 使用 `git commit --amend` 修改最后一次提交（如果只有一次提交）
- 或者添加新的 commit 修复问题

```bash
# 修改后推送
git push origin feature/your-feature-name --force-with-lease
```

### 6. 合并

- PR 通过审查后，维护者会合并到主分支
- 合并后可以删除功能分支

---

## 开发最佳实践

### 性能考虑

1. **避免在主线程阻塞**
   ```kotlin
   // ❌ 不要在 SyncRouteHandler 中调用 Bukkit API
   class BadHandler : SyncRouteHandler() {
       override fun handleSync(context: RequestContext): ApiResponse {
           Bukkit.getPlayer("Steve") // 错误！主线程调用
       }
   }

   // ✅ 使用 AsyncRouteHandler
   class GoodHandler : AsyncRouteHandler() {
       override fun handle(context: RequestContext): CompletableFuture<ApiResponse> {
           return coreAPI.submitTask {
               Bukkit.getPlayer("Steve") // 正确，在主线程执行
           }
       }
   }
   ```

2. **注意内存使用**
   ```kotlin
   // ❌ 不要创建大量临时对象
   fun processRequests() {
       while (true) {
           val list = mutableListOf<Task>() // 每次循环创建
       }
   }

   // ✅ 重用对象
   private val reusableList = mutableListOf<Task>()
   fun processRequests() {
       reusableList.clear()
       // 使用 reusableList
   }
   ```

### 安全考虑

1. **验证输入**
   ```kotlin
   // ✅ 始终验证用户输入
   val playerName = context.getParam("player")
   if (playerName.isNullOrBlank()) {
       return ApiResponse.error("参数 player 不能为空")
   }
   if (!playerName.matches(Regex("[a-zA-Z0-9_]{1,16}"))) {
       return ApiResponse.error("无效的玩家名")
   }
   ```

2. **避免路径穿越**
   ```kotlin
   // ❌ 不安全
   val file = File(dataFolder, context.getParam("file"))

   // ✅ 安全
   val fileName = context.getParam("file")?.let {
       it.replace("..", "").replace("/", "")
   }
   val file = File(dataFolder, fileName)
   ```

---

## 常见问题

### Q: 我应该在哪个分支开发？

A: 始终基于 `master` 分支创建新的功能分支。不要直接在 `master` 上开发。

### Q: Commit 写错了怎么办？

A: 如果还没推送，使用 `git commit --amend` 修改。如果已推送，添加新的 commit 修复。

### Q: PR 合并冲突怎么办？

A:
```bash
git fetch upstream
git merge upstream/master
# 解决冲突
git add .
git commit -m "merge: 解决冲突"
git push origin feature/xxx
```

### Q: 如何运行测试？

A: 当前项目暂无自动化测试。请在真实的 Minecraft 服务器环境中手动测试。

---

## 社区资源

- **文档**: [README.md](README.md)
- **Issue Tracker**: [GitHub Issues](https://github.com/your-repo/CoreAPI/issues)
- **讨论区**: [GitHub Discussions](https://github.com/your-repo/CoreAPI/discussions)

---

## 致谢

感谢所有为 CoreAPI 做出贡献的开发者！

你的名字会出现在 [贡献者列表](https://github.com/your-repo/CoreAPI/graphs/contributors) 中。

---

<p align="center">
  <sub>Built with ❤️ for the Minecraft community</sub>
</p>
