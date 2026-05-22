package com.dataocean.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 令牌提供者
 * <p>
 * 负责 JWT 令牌的生成、解析和验证，使用 HS256 算法签名。
 * 令牌中携带用户 ID、用户名、角色、权限和令牌版本号等信息。
 * </p>
 */
@Component
public class JwtTokenProvider {

    /** 签名密钥 */
    private final SecretKey secretKey;

    /** 令牌过期时间（秒） */
    @Getter
    private final long expirationSeconds;

    /**
     * 构造 JWT 令牌提供者
     *
     * @param secret            密钥字符串（支持 Base64 编码或明文）
     * @param expirationSeconds 令牌有效期（秒）
     */
    public JwtTokenProvider(@Value("${jwt.secret}") String secret,
                            @Value("${jwt.expiration}") long expirationSeconds) {
        this.secretKey = buildSecretKey(secret);
        this.expirationSeconds = expirationSeconds;
    }

    /**
     * 生成 JWT 令牌
     *
     * @param user         登录用户信息
     * @param tokenVersion 令牌版本号（用于强制失效旧令牌）
     * @return 签名后的 JWT 字符串
     */
    public String generateToken(LoginUser user, long tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getUsername())
                .claim("uid", user.getUserId())
                .claim("tokenVersion", tokenVersion)
                .claim("realName", user.getRealName())
                .claim("roles", user.getRoles())
                .claim("permissions", user.getPermissions())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 验证令牌是否有效（签名正确且未过期）
     *
     * @param token JWT 字符串
     * @return true 表示有效
     */
    public boolean validateToken(String token) {
        parseClaims(token);
        return true;
    }

    /**
     * 从令牌中提取用户名
     *
     * @param token JWT 字符串
     * @return 用户名
     */
    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * 从令牌中提取过期时间
     *
     * @param token JWT 字符串
     * @return 过期时间
     */
    public Date getExpiration(String token) {
        return parseClaims(token).getExpiration();
    }

    /**
     * 从令牌中提取令牌 ID（JTI）
     *
     * @param token JWT 字符串
     * @return 令牌唯一标识
     */
    public String getTokenId(String token) {
        return parseClaims(token).getId();
    }

    /**
     * 从令牌中提取用户 ID
     *
     * @param token JWT 字符串
     * @return 用户 ID，解析失败返回 null
     */
    public Long getUserId(String token) {
        Number userId = parseClaims(token).get("uid", Number.class);
        return userId == null ? null : userId.longValue();
    }

    /**
     * 从令牌中提取令牌版本号
     *
     * @param token JWT 字符串
     * @return 令牌版本号，解析失败返回 0
     */
    public Long getTokenVersion(String token) {
        Number version = parseClaims(token).get("tokenVersion", Number.class);
        return version == null ? 0L : version.longValue();
    }

    /**
     * 解析令牌获取 Claims 载荷
     *
     * @param token JWT 字符串
     * @return Claims 对象
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 构建签名密钥
     * <p>优先尝试 Base64 解码，失败则按 UTF-8 字节处理</p>
     *
     * @param secret 密钥字符串
     * @return HMAC 签名密钥
     */
    private SecretKey buildSecretKey(String secret) {
        try {
            // 尝试 Base64 解码
            byte[] decoded = Decoders.BASE64.decode(secret);
            if (decoded.length >= 32) {
                return Keys.hmacShaKeyFor(decoded);
            }
        } catch (RuntimeException ignored) {
            // 本地开发允许使用明文密钥，后续会按 UTF-8 字节处理
        }
        // 按 UTF-8 编码处理明文密钥
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("令牌密钥长度至少需要 32 字节才能用于 HS256");
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
