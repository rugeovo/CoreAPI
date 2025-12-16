package org.ruge.coreapi.auth

import java.util.*

/**
 * Token 解析器
 *
 * 职责：
 * - 从 HTTP 请求头中提取 token
 * - 解析 token 获取用户 UUID
 */
class TokenParser(
    private val jwtManager: JwtManager?
) {

    /**
     * 从 token 中解析 UUID
     *
     * @param token 认证 token（JWT token）
     * @return UUID 或 null（解析失败）
     */
    fun parseUUID(token: String?): UUID? {
        if (token.isNullOrBlank()) {
            return null
        }

        // 使用 JwtManager 提取 UUID（不验证签名，仅快速提取）
        if (jwtManager != null) {
            try {
                val uuid = jwtManager.extractUUID(token)
                if (uuid != null) {
                    return uuid
                }
            } catch (e: Exception) {
                // 解析失败，返回null
            }
        }
        return null
    }

    /**
     * 验证 JWT token 的完整性和有效性
     *
     * @param token JWT token
     * @return JwtPayload 或 null（验证失败）
     */
    fun validateJwt(token: String?): JwtPayload? {
        if (token.isNullOrBlank() || jwtManager == null) {
            return null
        }

        return jwtManager.validateToken(token)
    }
}

