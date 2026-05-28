package com.dataocean.common.resilience;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 超时配置
 * <p>
 * 配置 Java 调用 Python 服务的超时时间。
 * Java 超时（120s）大于 Python 总预算（100s），确保 Python 先超时返回。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "dataocean.python-service.timeout")
public class TimeoutConfig {

    /** 连接超时（毫秒），默认 5 秒 */
    private int connectMs = 5_000;

    /** 读取超时（毫秒），默认 120 秒（大于 Python 100s 预算） */
    private int readMs = 120_000;

    /** 健康检查超时（毫秒），默认 5 秒 */
    private int healthCheckMs = 5_000;
}
