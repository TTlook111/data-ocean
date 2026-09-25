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
 * 令牌中携带用户 ID、用户名和令牌版本号等身份/会话信息；不携带业务角色或权限。
 * </p>
 */
@Component
public class JwtTokenProvider {

    /** HS256 要求的最小密钥字节数（256 位） */
    static final int MIN_SECRET_BYTES = 32;

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
     * <p>
     * 优先尝试 Base64 解码，失败则按 UTF-8 字节处理。
     * 校验在 Bean 构造期执行，配置不合格会直接导致启动失败——签名密钥决定登录令牌的
     * 可信度，用它换取"能启动"是不划算的：持有该密钥即可伪造任意用户的登录态。
     * </p>
     *
     * @param secret 密钥字符串
     * @return HMAC 签名密钥
     */
    private SecretKey buildSecretKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret 未配置。签名密钥不存在可用的默认值——依赖公开默认值意味着"
                            + "任何人都能伪造任意用户的登录态。请通过环境变量 JWT_SECRET 提供，"
                            + "或在被 Git 忽略的 config/application-local.yml 中设置 jwt.secret；"
                            + "生成方式：openssl rand -base64 32");
        }
        // 拒绝任何空白：配置里混入行尾空白或换行会让密钥与预期不符，且难以排查
        if (secret.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException(
                    "jwt.secret 不能包含空格、制表符或换行，请检查配置是否误写成多行");
        }
        try {
            // 尝试 Base64 解码
            byte[] decoded = Decoders.BASE64.decode(secret);
            if (decoded.length >= MIN_SECRET_BYTES) {
                return Keys.hmacShaKeyFor(decoded);
            }
        } catch (RuntimeException ignored) {
            // 非 Base64 内容：按 UTF-8 明文密钥处理
        }
        // 按 UTF-8 编码处理明文密钥
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret 长度不足：HS256 至少需要 " + MIN_SECRET_BYTES + " 字节（当前 "
                            + bytes.length + " 字节）。生成方式：openssl rand -base64 32");
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
