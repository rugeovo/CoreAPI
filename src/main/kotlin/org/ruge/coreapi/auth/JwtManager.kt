package org.ruge.coreapi.auth

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import taboolib.common.platform.function.warning
import java.util.*
import javax.crypto.SecretKey

/**
 * JWT (JSON Web Token) 管理器
 *
 * 职责：
 * - 生成安全的 JWT token
 * - 验证和解析 JWT token
 * - 管理 token 过期时间
 *
 * 安全特性：
 * - 使用 HS256 算法签名
 * - 自动 token 过期
 * - 防止 token 伪造
 * - 密钥长度验证
 */
class JwtManager(
    secretKey: String,
    private val expirationHours: Int = 24
) {
    private val key: SecretKey

    init {
        // ✅ 安全修复：严格检查禁止使用默认或弱密钥
        val defaultKey = "CHANGE-THIS-TO-A-RANDOM-SECRET-KEY-AT-LEAST-32-CHARS-LONG"

        // 1. 检查是否是默认密钥（精确匹配）
        if (secretKey == defaultKey) {
            throw SecurityException(
                "===== 致命安全错误 =====\n" +
                "检测到使用默认 JWT 密钥！\n" +
                "这将导致任何人都能伪造认证 token，获取服务器完全控制权！\n\n" +
                "修复步骤：\n" +
                "1. 生成随机密钥：\n" +
                "   Linux/Mac: openssl rand -base64 48\n" +
                "   Windows: 访问 https://www.random.org/strings/\n" +
                "2. 编辑 plugins/CoreAPI/config.yml\n" +
                "3. 修改 jwt.secret 为生成的随机字符串\n" +
                "4. 重启服务器\n" +
                "========================"
            )
        }

        // 2. 检查是否包含弱模式
        val weakPatterns = listOf("CHANGE", "DEFAULT", "SECRET", "123456", "PASSWORD", "EXAMPLE", "TEST")
        val containsWeakPattern = weakPatterns.any { secretKey.contains(it, ignoreCase = true) }

        if (containsWeakPattern) {
            throw SecurityException(
                "===== 安全警告 =====\n" +
                "JWT 密钥包含常见弱模式（如 'CHANGE', 'DEFAULT' 等）\n" +
                "这极易被猜测或暴力破解！\n\n" +
                "请使用真正随机的密钥（至少 64 位字符）\n" +
                "生成方法：openssl rand -base64 48\n" +
                "==================="
            )
        }

        // 3. 检查密钥长度
        if (secretKey.length < 32) {
            throw SecurityException(
                "===== 安全错误 =====\n" +
                "JWT 密钥长度不足！\n" +
                "当前长度: ${secretKey.length} 字符\n" +
                "最低要求: 32 字符\n" +
                "推荐长度: 64 字符\n\n" +
                "HS256 算法需要至少 256 位（32 字节）密钥才安全。\n" +
                "==================="
            )
        }

        // 验证密钥字节长度（防止多字节字符导致的长度误判）
        val keyBytes = secretKey.toByteArray()
        if (keyBytes.size < 32) {
            throw SecurityException(
                "JWT 密钥字节长度不足！需要至少 32 字节，当前只有 ${keyBytes.size} 字节。"
            )
        }

        key = Keys.hmacShaKeyFor(keyBytes)
        warning("JWT 管理器已初始化，密钥长度: ${secretKey.length} 字符 / ${keyBytes.size} 字节")
    }

    /**
     * 生成 JWT token
     *
     * Token payload 只包含：
     * - sub（subject）: 玩家 UUID
     * - iat（issued at）: 签发时间
     * - exp（expiration）: 过期时间
     *
     * 设计原则：Token只存储UUID，因为权限验证（高频操作）需要UUID
     * 这避免了每次请求都要username→UUID的查询开销
     *
     * @param uuid 玩家 UUID
     * @return JWT token 字符串
     */
    fun generateToken(uuid: UUID): String {
        val now = Date()
        val expirationDate = Date(now.time + expirationHours * 3600 * 1000L)

        return Jwts.builder()
            .subject(uuid.toString())
            .issuedAt(now)
            .expiration(expirationDate)
            .signWith(key)
            .compact()
    }

    /**
     * 验证并解析 JWT token
     *
     * @param token JWT token 字符串
     * @return JwtPayload 或 null（验证失败）
     */
    fun validateToken(token: String): JwtPayload? {
        try {
            val claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload

            return JwtPayload(
                uuid = UUID.fromString(claims.subject),
                issuedAt = claims.issuedAt,
                expiration = claims.expiration
            )

        } catch (e: Exception) {
            // Token 无效（签名错误、过期、格式错误等）
            warning("JWT token 验证失败: ${e.message}")
            return null
        }
    }

    /**
     * 从 token 中提取 UUID（不验证签名，仅用于快速提取）
     *
     * 注意：此方法不验证 token 的有效性，仅用于快速提取 UUID
     * 权限验证时应使用 validateToken() 进行完整验证
     *
     * @param token JWT token 字符串
     * @return UUID 或 null
     */
    fun extractUUID(token: String): UUID? {
        try {
            // 不验证签名，仅解析 payload
            val jwt = Jwts.parser()
                .unsecured()
                .build()
                .parseUnsecuredClaims(token)
                .payload

            return UUID.fromString(jwt.subject)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 检查 token 是否即将过期（剩余时间 < 1小时）
     *
     * @param token JWT token 字符串
     * @return true 如果即将过期
     */
    fun isTokenExpiringSoon(token: String): Boolean {
        val payload = validateToken(token) ?: return true

        val now = Date()
        val remainingTime = payload.expiration.time - now.time
        val oneHour = 3600 * 1000L

        return remainingTime < oneHour
    }
}

/**
 * JWT Payload 数据类
 */
data class JwtPayload(
    val uuid: UUID,
    val issuedAt: Date,
    val expiration: Date
) {
    /**
     * 检查 token 是否已过期
     */
    fun isExpired(): Boolean {
        return Date().after(expiration)
    }

    /**
     * 获取剩余有效时间（秒）
     */
    fun getRemainingSeconds(): Long {
        val remaining = expiration.time - Date().time
        return if (remaining > 0) remaining / 1000 else 0
    }
}
