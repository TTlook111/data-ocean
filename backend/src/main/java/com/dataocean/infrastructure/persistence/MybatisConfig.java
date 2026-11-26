package com.dataocean.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.dataocean.common.PageQuery;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置 MyBatis-Plus 分页及共用页大小上限。
 */
@Configuration
public class MybatisConfig {

    /**
     * 使用 MySQL 分页，单页上限与公共分页类使用同一常量。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        var pagination = new PaginationInnerInterceptor(DbType.MYSQL) {
            /**
             * 在同一次计数与查询之间校正页码，越界定位末页，空结果定位第一页。
             */
            @Override
            protected boolean continuePage(IPage<?> page) {
                page.setCurrent(Math.min(page.getCurrent(), Math.max(1L, page.getPages())));
                return super.continuePage(page);
            }
        };
        pagination.setMaxLimit(PageQuery.MAX_PAGE_SIZE);
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
