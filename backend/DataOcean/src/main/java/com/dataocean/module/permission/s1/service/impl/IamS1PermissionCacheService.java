package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.module.permission.s1.IamS1Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * S1 权限快照缓存。
 * <p>
 * 仅使用既有 RedisTemplate。缓存值由 Resolver 生成的无参数原值摘要组成，
 * 写入失败不阻断数据库主流程；事务回滚时不会提前删除缓存。
 * </p>
 */
@Service
@Slf4j
public class IamS1PermissionCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final long maxTtlSeconds;

    public IamS1PermissionCacheService(RedisTemplate<String, Object> redisTemplate,
                                       @Value("${dataocean.permission.s1.cache-max-ttl-seconds:300}") long maxTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.maxTtlSeconds = Math.max(1L, maxTtlSeconds);
    }

    public <T> T read(String key, Class<T> type) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return null;
            }
            if (!type.isInstance(value)) {
                log.warn("S1 权限缓存类型不匹配，按数据库回源 key={}", key);
                return null;
            }
            return type.cast(value);
        } catch (RuntimeException exception) {
            log.warn("S1 权限缓存读取失败，按数据库回源", exception);
            return null;
        }
    }

    public void write(String key, Long datasourceId, Object value, LocalDateTimeBoundary boundary) {
        if (value == null || key == null || datasourceId == null) {
            return;
        }
        long ttlSeconds = ttlSeconds(boundary);
        if (ttlSeconds <= 0) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
            String indexKey = indexKey(datasourceId);
            redisTemplate.opsForSet().add(indexKey, key);
            redisTemplate.expire(indexKey, ttlSeconds, TimeUnit.SECONDS);
        } catch (RuntimeException exception) {
            log.warn("S1 权限缓存写入失败，不阻断已完成的数据库计算 key={}", key, exception);
        }
    }

    public void invalidateAfterCommit(Long datasourceId) {
        if (datasourceId == null) {
            return;
        }
        Runnable invalidation = () -> invalidateNow(datasourceId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidation.run();
                }
            });
        } else {
            invalidation.run();
        }
    }

    public long maxTtlSeconds() {
        return maxTtlSeconds;
    }

    public String indexKey(Long datasourceId) {
        return IamS1Constants.PERMISSION_CACHE_INDEX_PREFIX + datasourceId;
    }

    private long ttlSeconds(LocalDateTimeBoundary boundary) {
        if (boundary == null || boundary.nextEffectiveAt() == null) {
            return maxTtlSeconds;
        }
        long seconds = Duration.between(boundary.calculatedAt(), boundary.nextEffectiveAt()).getSeconds();
        return Math.min(maxTtlSeconds, seconds);
    }

    private void invalidateNow(Long datasourceId) {
        try {
            String indexKey = indexKey(datasourceId);
            Set<Object> members = redisTemplate.opsForSet().members(indexKey);
            if (members != null && !members.isEmpty()) {
                Collection<String> keys = new LinkedHashSet<>();
                for (Object member : members) {
                    if (member != null) {
                        keys.add(String.valueOf(member));
                    }
                }
                if (!keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            }
            redisTemplate.delete(indexKey);
        } catch (RuntimeException exception) {
            log.warn("S1 权限缓存事务后失效失败，不扩大数据库权限", exception);
        }
    }

    /** 避免缓存服务直接接触记录条件原值。 */
    public record LocalDateTimeBoundary(java.time.LocalDateTime calculatedAt,
                                        java.time.LocalDateTime nextEffectiveAt) {
    }
}
