package com.dataocean.common.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    @Test
    void newTokenContainsIdentityAndSessionClaimsButNoLegacyAuthorizationClaims() {
        JwtTokenProvider provider = new JwtTokenProvider(
                "01234567890123456789012345678901",
                3600L);
        LoginUser user = new LoginUser(
                7L,
                "alice",
                "encoded",
                "Alice",
                List.of(new SimpleGrantedAuthority("AUTHENTICATED_USER")));

        Claims claims = provider.parseClaims(provider.generateToken(user, 4L));

        assertThat(claims.get("uid", Number.class).longValue()).isEqualTo(7L);
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.get("tokenVersion", Number.class).longValue()).isEqualTo(4L);
        assertThat(claims).doesNotContainKeys("roles", "permissions");
    }

    @Test
    void rejectsMissingSecret() {
        assertThatThrownBy(() -> new JwtTokenProvider("", 3600L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret");
    }

    @Test
    void rejectsBlankSecret() {
        assertThatThrownBy(() -> new JwtTokenProvider("        ", 3600L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsSecretContainingWhitespace() {
        assertThatThrownBy(() -> new JwtTokenProvider(
                "01234567890123456789012345678901\n", 3600L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能包含空格");
    }

    @Test
    void rejectsSecretShorterThanMinimumBytes() {
        String tooShort = "x".repeat(JwtTokenProvider.MIN_SECRET_BYTES - 1);
        assertThatThrownBy(() -> new JwtTokenProvider(tooShort, 3600L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(JwtTokenProvider.MIN_SECRET_BYTES));
    }

    @Test
    void acceptsBase64EncodedMinimumLengthSecret() {
        // 与文档中的生成命令 openssl rand -base64 32 产出一致：32 随机字节的 base64
        byte[] raw = new byte[JwtTokenProvider.MIN_SECRET_BYTES];
        java.util.Arrays.fill(raw, (byte) 7);
        String base64 = Base64.getEncoder().encodeToString(raw);

        assertThatCode(() -> new JwtTokenProvider(base64, 3600L)).doesNotThrowAnyException();
    }

    @Test
    void errorMessagesNeverLeakTheSecretValue() {
        String secret = "leak-me-please-0123456789abcdefghij";
        assertThatThrownBy(() -> new JwtTokenProvider(secret + " ", 3600L))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .doesNotContain(secret)
                        .doesNotContain(secret.substring(0, 8)));
    }
}
