package com.dataocean.common.logging;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogValueSanitizerTest {

    @Test
    void hidesRawStringsAndSensitiveMapValues() {
        String summary = LogValueSanitizer.summarize(Map.of(
                "password", "very-secret-password",
                "accessToken", "very-secret-token",
                "query", "select * from users"));

        assertThat(summary).contains("password=***", "accessToken=***", "query=***")
                .doesNotContain("very-secret-password", "very-secret-token", "select * from users");
    }

    @Test
    void keepsOnlyLowRiskScalarValues() {
        assertThat(LogValueSanitizer.summarize(new Object[]{42, true, "raw-secret"}))
                .isEqualTo("[42, true, ***]");
    }
}
