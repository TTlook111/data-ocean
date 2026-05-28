package com.dataocean.common.resilience;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 重试配置
 * <p>
 * 配置 Java 调用 Python 服务的重试策略。
 * 仅对 5xx 和超时错误重试 1 次，非幂等请求不重试。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "dataocean.python-service.retry")
public class RetryConfig {

    /** 最大重试次数（默认 1 次） */
    private int maxAttempts = 1;

    /** 重试间隔（毫秒） */
    private long backoffMs = 2_000;

    /** 是否对非幂等请求重试（默认 false） */
    private boolean retryNonIdempotent = false;

    /**
     * 判断是否应该重试
     *
     * @param statusCode HTTP 状态码
     * @param isIdempotent 请求是否幂等
     * @return 是否应该重试
     */
    public boolean shouldRetry(int statusCode, boolean isIdempotent) {
        if (!isIdempotent && !retryNonIdempotent) {
            return false;
        }
        return statusCode >= 500 || statusCode == 408 || statusCode == 429;
    }
}
