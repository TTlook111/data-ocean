package com.dataocean.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内部服务令牌的启动校验与比较行为。
 * <p>
 * 该令牌是 {@code /internal/**} 的唯一防线，因此这里断言的重点是
 * "配置错误必须让启动失败"，而不是"尽量容忍"。
 */
class InternalTokenValidatorTest {

    private static final String VALID_TOKEN = "unit-test-internal-token-0123456789ab";

    @Test
    void rejectsMissingToken() {
        InternalTokenValidator validator = new InternalTokenValidator("");
        assertThatThrownBy(validator::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(InternalTokenValidator.PROPERTY_NAME);
    }

    @Test
    void rejectsNullToken() {
        InternalTokenValidator validator = new InternalTokenValidator(null);
        assertThatThrownBy(validator::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(InternalTokenValidator.PROPERTY_NAME);
    }

    @Test
    void rejectsBlankToken() {
        InternalTokenValidator validator = new InternalTokenValidator("        ");
        assertThatThrownBy(validator::validateConfiguration)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsTokenContainingWhitespace() {
        // 行尾空白或误写多行会让校验通过、比较却永远失败（内部调用全部 403 且无报错），
        // 因此直接拒绝而不是静默裁剪。
        InternalTokenValidator validator =
                new InternalTokenValidator(VALID_TOKEN + "\n");
        assertThatThrownBy(validator::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能包含空格");
    }

    @Test
    void rejectsTokenShorterThanMinimum() {
        String tooShort = "x".repeat(InternalTokenValidator.MIN_TOKEN_LENGTH - 1);
        InternalTokenValidator validator = new InternalTokenValidator(tooShort);
        assertThatThrownBy(validator::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(InternalTokenValidator.MIN_TOKEN_LENGTH));
    }

    @Test
    void acceptsTokenAtMinimumLength() {
        String exactlyMinimum = "x".repeat(InternalTokenValidator.MIN_TOKEN_LENGTH);
        InternalTokenValidator validator = new InternalTokenValidator(exactlyMinimum);
        assertThatCode(validator::validateConfiguration).doesNotThrowAnyException();
    }

    @Test
    void errorMessagesNeverLeakTheTokenValue() {
        // 校验失败信息会打到控制台和日志采集，绝不能带上令牌内容或片段。
        String secret = "leak-me-please-0123456789abcdefghij";
        InternalTokenValidator validator = new InternalTokenValidator(secret + " ");
        assertThatThrownBy(validator::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .doesNotContain(secret)
                        .doesNotContain(secret.substring(0, 8)));
    }

    @Test
    void matchesOnlyTheConfiguredToken() {
        InternalTokenValidator validator = new InternalTokenValidator(VALID_TOKEN);
        assertThat(validator.matches(VALID_TOKEN)).isTrue();
        assertThat(validator.matches(VALID_TOKEN + "x")).isFalse();
        assertThat(validator.matches(VALID_TOKEN.substring(0, 10))).isFalse();
        assertThat(validator.matches("")).isFalse();
        assertThat(validator.matches(null)).isFalse();
    }

    @Test
    void matchesHandlesNonAsciiCandidateSafely() {
        // 请求头按 latin-1 解码，攻击者可构造非 ASCII 内容。
        // 直接比较字符串的实现在这种情况下会抛异常，把 403 变成 500。
        InternalTokenValidator validator = new InternalTokenValidator(VALID_TOKEN);
        assertThatCode(() -> validator.matches("ÿþ")).doesNotThrowAnyException();
        assertThat(validator.matches("ÿþ")).isFalse();
    }

    @Test
    void exposesTheConfiguredTokenForOutboundCalls() {
        InternalTokenValidator validator = new InternalTokenValidator(VALID_TOKEN);
        assertThat(validator.token()).isEqualTo(VALID_TOKEN);
    }
}
