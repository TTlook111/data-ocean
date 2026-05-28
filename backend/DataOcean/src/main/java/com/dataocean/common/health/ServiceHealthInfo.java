package com.dataocean.common.health;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务健康信息
 * <p>
 * 封装某个外部服务的健康状态详情，包括当前状态、最后检查时间和连续失败次数。
 * </p>
 */
@Data
public class ServiceHealthInfo {

    /** 服务名称 */
    private String serviceName;

    /** 当前健康状态 */
    private ServiceHealthStatus status;

    /** 最后一次健康检查时间 */
    private LocalDateTime lastCheckTime;

    /** 连续失败次数 */
    private int consecutiveFailures;

    /** 最后一次失败的错误信息 */
    private String lastErrorMessage;

    public ServiceHealthInfo(String serviceName) {
        this.serviceName = serviceName;
        this.status = ServiceHealthStatus.AVAILABLE;
        this.consecutiveFailures = 0;
    }
}
