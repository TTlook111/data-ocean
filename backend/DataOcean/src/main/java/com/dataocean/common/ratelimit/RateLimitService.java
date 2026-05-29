package com.dataocean.common.ratelimit;

import com.dataocean.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 基于 Redis ZSET 的滑动窗口限流服务。
 * <p>
 * 使用有序集合记录每次请求的时间戳，通过窗口内的请求计数判断是否超限。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;

    /** 默认窗口大小：60 秒 */
    private static final Duration DEFAULT_WINDOW = Duration.ofSeconds(60);

    /** 默认限制：每窗口 10 次 */
    private static final int DEFAULT_MAX_REQUESTS = 10;

    /**
     * 检查并记录请求，超限时抛出异常。
     *
     * @param key 限流键（如 "rate:query:userId:123"）
     */
    public void checkAndRecord(String key) {
        checkAndRecord(key, DEFAULT_MAX_REQUESTS, DEFAULT_WINDOW);
    }

    /**
     * 检查并记录请求，超限时抛出异常。
     *
     * @param key         限流键
     * @param maxRequests 窗口内最大请求数
     * @param window      滑动窗口时长
     */
    public void checkAndRecord(String key, int maxRequests, Duration window) {
        long now = System.currentTimeMillis();
        long windowStart = now - window.toMillis();

        ZSetOperations<String, String> zSet = redisTemplate.opsForZSet();

        // 移除窗口外的过期记录
        zSet.removeRangeByScore(key, 0, windowStart);

        // 统计窗口内的请求数
        Long count = zSet.zCard(key);
        if (count != null && count >= maxRequests) {
            log.warn("限流触发 key={} count={} limit={}", key, count, maxRequests);
            throw new BusinessException(429, "查询过于频繁，请稍后再试");
        }

        // 记录本次请求
        zSet.add(key, String.valueOf(now), now);

        // 设置 key 过期时间（窗口大小 + 1 秒缓冲），防止 key 永不过期
        redisTemplate.expire(key, window.plusSeconds(1));
    }
}
