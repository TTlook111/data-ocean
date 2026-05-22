package com.dataocean.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置类
 * <p>
 * 配置分页插件和审计字段自动填充功能，
 * 分页插件限制单次查询最大 100 条记录，
 * 审计字段自动填充 createdAt 和 updatedAt。
 * </p>
 */
@Configuration
@Slf4j
public class MyBatisPlusConfig {

    /**
     * 配置 MyBatis-Plus 分页拦截器
     * <p>设置数据库类型为 MySQL，单页最大记录数为 100</p>
     *
     * @return MyBatis-Plus 拦截器实例
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        log.info("配置 MyBatis-Plus 分页插件 maxLimit=100");
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 创建分页内置拦截器，指定 MySQL 方言
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 限制单页最大查询 100 条，防止一次性拉取过多数据
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /**
     * 配置审计字段自动填充处理器
     * <p>
     * 插入时自动填充 createdAt 和 updatedAt，
     * 更新时自动填充 updatedAt。
     * </p>
     *
     * @return 元对象处理器实例
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        log.info("配置 MyBatis-Plus 审计字段自动填充");
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                // 插入时同时填充创建时间和更新时间
                LocalDateTime now = LocalDateTime.now();
                strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                // 更新时只填充更新时间
                strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
