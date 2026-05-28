package com.dataocean.common.health;

import lombok.Getter;

/**
 * 服务健康状态枚举
 * <p>
 * 定义外部依赖服务的三种健康状态，用于健康检查器判断服务可用性。
 * </p>
 */
@Getter
public enum ServiceHealthStatus {

    /** 服务正常可用 */
    AVAILABLE("可用"),

    /** 服务不可用（连续多次健康检查失败） */
    UNAVAILABLE("不可用"),

    /** 服务降级运行（部分功能受限） */
    DEGRADED("降级");

    /** 状态的中文描述 */
    private final String description;

    ServiceHealthStatus(String description) {
        this.description = description;
    }
}
