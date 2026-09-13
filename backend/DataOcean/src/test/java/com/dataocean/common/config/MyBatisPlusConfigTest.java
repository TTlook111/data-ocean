package com.dataocean.common.config;

import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MyBatisPlusConfigTest {

    @Test
    void registersOptimisticLockerBeforePagination() {
        var interceptors = new MyBatisPlusConfig().mybatisPlusInterceptor().getInterceptors();

        assertThat(interceptors)
                .extracting(Object::getClass)
                .containsExactly(OptimisticLockerInnerInterceptor.class, PaginationInnerInterceptor.class);
    }
}
