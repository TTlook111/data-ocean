package com.dataocean.common.persistence;

import com.dataocean.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimisticLockSupportTest {

    @Test
    void mapsZeroAffectedRowsToConflict() {
        assertThatThrownBy(() -> OptimisticLockSupport.requireUpdated(0, "版本冲突，请刷新后重试"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("版本冲突，请刷新后重试")
                .satisfies(exception -> assertThat(((BusinessException) exception).getCode()).isEqualTo(409));
    }

    @Test
    void acceptsSuccessfulUpdate() {
        OptimisticLockSupport.requireUpdated(1, "不会返回");
    }
}
