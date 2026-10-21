package com.dataocean.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.dataocean.common.entity.query.PageQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MyBatisPlusConfigTest {

    @Test
    void registersOptimisticLockerBeforePagination() {
        var interceptors = new MyBatisPlusConfig().mybatisPlusInterceptor().getInterceptors();

        assertThat(interceptors)
                .extracting(Object::getClass)
                .containsExactly(OptimisticLockerInnerInterceptor.class, PaginationInnerInterceptor.class);
        PaginationInnerInterceptor pagination = (PaginationInnerInterceptor) interceptors.get(1);
        assertThat(pagination.getDbType()).isEqualTo(DbType.MYSQL);
        assertThat(pagination.getMaxLimit()).isEqualTo(PageQuery.MAX_PAGE_SIZE);
    }
}
