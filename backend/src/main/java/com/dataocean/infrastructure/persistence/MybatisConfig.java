package com.dataocean.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.dataocean.common.PageQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisConfig {
    /** 沿用基础示例的分页插件，单页上限与公共分页类使用同一常量。 */
    @Bean public MybatisPlusInterceptor mybatisPlusInterceptor() {
        var pagination=new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(PageQuery.MAX_PAGE_SIZE);
        pagination.setOverflow(true);
        var interceptor=new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
