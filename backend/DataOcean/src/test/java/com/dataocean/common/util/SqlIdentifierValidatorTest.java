package com.dataocean.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlIdentifierValidatorTest {

    @Test
    void acceptsNormalAsciiAndChineseIdentifiers() {
        assertThat(SqlIdentifierValidator.validate("order_items_2026")).isEqualTo("order_items_2026");
        assertThat(SqlIdentifierValidator.validate("订单金额")).isEqualTo("订单金额");
        assertThat(SqlIdentifierValidator.validate("$internal")).isEqualTo("$internal");
    }

    @Test
    void rejectsUnsafeOrOversizedIdentifiers() {
        assertThatThrownBy(() -> SqlIdentifierValidator.validate("users;DROP_TABLE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlIdentifierValidator.validate("bad-name"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SqlIdentifierValidator.validate("a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
