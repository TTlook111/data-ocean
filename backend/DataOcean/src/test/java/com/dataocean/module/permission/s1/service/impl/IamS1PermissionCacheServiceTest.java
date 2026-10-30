package com.dataocean.module.permission.s1.service.impl;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot;
import com.dataocean.module.permission.s1.entity.vo.IamS1FieldProtectionVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1GrantSourceVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1TablePermissionVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S1 Redis 读写故障、TTL 和事务提交/回滚时序测试。 */
class IamS1PermissionCacheServiceTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void redisReadFailureReturnsNullForDatabaseFallback() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("iam-s1:key")).thenThrow(new IllegalStateException("redis down"));
        IamS1PermissionCacheService cache = new IamS1PermissionCacheService(redis, 300L);
        assertThat(cache.read("iam-s1:key", String.class)).isNull();
    }

    @Test
    void redisWriteFailureDoesNotEscape() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new IllegalStateException("redis down")).when(values)
                .set(anyString(), any(), anyLong(), eq(TimeUnit.SECONDS));
        IamS1PermissionCacheService cache = new IamS1PermissionCacheService(redis, 300L);
        cache.write("iam-s1:key", 1L, "safe-summary",
                new IamS1PermissionCacheService.LocalDateTimeBoundary(
                        LocalDateTime.of(2026, 9, 17, 10, 0), null));
        verify(values).set(eq("iam-s1:key"), eq("safe-summary"), eq(300L), eq(TimeUnit.SECONDS));
    }

    @Test
    void ttlUsesNearestFutureBoundaryAndMaximum() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        SetOperations<String, Object> sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        IamS1PermissionCacheService cache = new IamS1PermissionCacheService(redis, 300L);
        LocalDateTime at = LocalDateTime.of(2026, 9, 17, 10, 0);
        cache.write("iam-s1:key", 1L, "summary",
                new IamS1PermissionCacheService.LocalDateTimeBoundary(at, at.plusSeconds(80)));
        verify(values).set(eq("iam-s1:key"), eq("summary"), eq(80L), eq(TimeUnit.SECONDS));
    }

    @Test
    void invalidationRunsAfterCommitNotBefore() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        SetOperations<String, Object> sets = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.members(anyString())).thenReturn(Set.of("iam-s1:permission:IAM-SIMPLE-1:7:1:9:hash"));
        IamS1PermissionCacheService cache = new IamS1PermissionCacheService(redis, 300L);
        TransactionSynchronizationManager.initSynchronization();
        cache.invalidateAfterCommit(1L);
        verify(redis, never()).delete(anyString());
        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(redis).delete(anyCollection());
        verify(redis).delete(eq("iam-s1:permission:index:1"));
    }

    @Test
    void rollbackDoesNotInvalidate() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        IamS1PermissionCacheService cache = new IamS1PermissionCacheService(redis, 300L);
        TransactionSynchronizationManager.initSynchronization();
        cache.invalidateAfterCommit(1L);
        TransactionSynchronizationUtils.triggerAfterCompletion(
                org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(redis, never()).delete(anyString());
    }

    @Test
    void permissionSnapshotRoundTripsThroughCurrentRedisSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer serializer =
                new org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer(mapper);
        IamS1DataAuthorizationSnapshot snapshot = new IamS1DataAuthorizationSnapshot(true, "ALLOWED",
                "IAM-SIMPLE-1", 7L, 1L, "销售库", 88L, 9L,
                LocalDateTime.of(2026, 9, 17, 10, 0), null,
                List.of(new IamS1TablePermissionVO(true, "ALLOWED", "orders", List.of("phone"),
                        List.of(new IamS1GrantSourceVO(1L, "USER", 7L, "用户个人授权", null,
                                "MANUAL", null, LocalDateTime.of(2026, 9, 17, 9, 0), null,
                                List.of("phone"), null)),
                        List.of(new IamS1FieldProtectionVO(105L, "orders", "phone", "MASKED", "PHONE", "字段保护为脱敏")),
                        List.of("safe"))));
        byte[] bytes = serializer.serialize(snapshot);
        Object restored = serializer.deserialize(bytes);
        assertThat(restored).isInstanceOf(IamS1DataAuthorizationSnapshot.class);
        IamS1DataAuthorizationSnapshot roundTrip = (IamS1DataAuthorizationSnapshot) restored;
        assertThat(roundTrip.getPermissionRevision()).isEqualTo(9L);
        assertThat(roundTrip.getTables().get(0).getGrantSources().get(0).getExplicitColumns())
                .containsExactly("phone");
    }
}
