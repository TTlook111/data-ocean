package com.dataocean.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long expirationSeconds;

    public JwtTokenProvider(@Value("${jwt.secret}") String secret,
                            @Value("${jwt.expiration}") long expirationSeconds) {
        this.secretKey = buildSecretKey(secret);
        this.expirationSeconds = expirationSeconds;
    }

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

    public boolean validateToken(String token) {
        parseClaims(token);
        return true;
    }

    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    public Date getExpiration(String token) {
        return parseClaims(token).getExpiration();
    }

    public String getTokenId(String token) {
        return parseClaims(token).getId();
    }

    public Long getUserId(String token) {
        Number userId = parseClaims(token).get("uid", Number.class);
        return userId == null ? null : userId.longValue();
    }

    public Long getTokenVersion(String token) {
        Number version = parseClaims(token).get("tokenVersion", Number.class);
        return version == null ? 0L : version.longValue();
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey buildSecretKey(String secret) {
        try {
            byte[] decoded = Decoders.BASE64.decode(secret);
            if (decoded.length >= 32) {
                return Keys.hmacShaKeyFor(decoded);
            }
        } catch (RuntimeException ignored) {
            // Plain text development secrets are accepted below.
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("jwt.secret must be at least 32 bytes for HS256");
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    @SuppressWarnings("unchecked")
    public List<String> getRoles(String token) {
        Object roles = parseClaims(token).get("roles");
        return roles instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }
}
