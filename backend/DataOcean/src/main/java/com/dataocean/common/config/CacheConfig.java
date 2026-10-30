package com.dataocean.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 缓存配置类
 * <p>
 * 使用 Caffeine 作为本地缓存实现，提供高性能的进程内缓存。
 * </p>
 *
 * @author dataocean
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * 配置缓存管理器
     * <p>
     * 默认缓存策略：
     * - 最大缓存条目数：1000
     * - 写入后过期时间：10 秒
     * - 访问后过期时间：5 秒
     * </p>
     *
     * @return CacheManager 实例
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(10, TimeUnit.SECONDS)
                .expireAfterAccess(5, TimeUnit.SECONDS)
                .recordStats());
        return cacheManager;
    }
}
