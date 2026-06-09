package com.dataocean.common.health;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 服务健康信息（不可变对象）
 * <p>
 * 封装某个外部服务的健康状态详情，包括当前状态、最后检查时间和连续失败次数。
 * 所有字段都是 final 的，通过 withXxx 方法创建新的实例。
 * </p>
 */
@Getter
public class ServiceHealthInfo {

    /** 服务名称 */
    private final String serviceName;

    /** 当前健康状态 */
    private final ServiceHealthStatus status;

    /** 最后一次健康检查时间 */
    private final LocalDateTime lastCheckTime;

    /** 连续失败次数 */
    private final int consecutiveFailures;

    /** 最后一次失败的错误信息 */
    private final String lastErrorMessage;

    /**
     * 全参构造函数
     */
    public ServiceHealthInfo(String serviceName, ServiceHealthStatus status,
                             LocalDateTime lastCheckTime, int consecutiveFailures,
                             String lastErrorMessage) {
        this.serviceName = serviceName;
        this.status = status;
        this.lastCheckTime = lastCheckTime;
        this.consecutiveFailures = consecutiveFailures;
        this.lastErrorMessage = lastErrorMessage;
    }

    /**
     * 创建初始状态的健康信息
     *
     * @param serviceName 服务名称
     * @return 初始健康信息
     */
    public static ServiceHealthInfo initial(String serviceName) {
        return new ServiceHealthInfo(serviceName, ServiceHealthStatus.AVAILABLE, null, 0, null);
    }

    /**
     * 创建检查成功的健康信息
     *
     * @return 检查成功后的健康信息
     */
    public ServiceHealthInfo withSuccess() {
        return new ServiceHealthInfo(serviceName, ServiceHealthStatus.AVAILABLE,
                LocalDateTime.now(), 0, null);
    }

    /**
     * 创建检查失败的健康信息
     *
     * @param errorMessage 错误信息
     * @return 检查失败后的健康信息
     */
    public ServiceHealthInfo withFailure(String errorMessage) {
        return new ServiceHealthInfo(serviceName, status,
                LocalDateTime.now(), consecutiveFailures + 1, errorMessage);
    }

    /**
     * 创建状态变更为不可用的健康信息
     *
     * @param errorMessage 错误信息
     * @return 状态变更后的健康信息
     */
    public ServiceHealthInfo withUnavailable(String errorMessage) {
        return new ServiceHealthInfo(serviceName, ServiceHealthStatus.UNAVAILABLE,
                LocalDateTime.now(), consecutiveFailures + 1, errorMessage);
    }

    /**
     * 判断当前状态是否为可用
     *
     * @return true 表示可用
     */
    public boolean isAvailable() {
        return status == ServiceHealthStatus.AVAILABLE;
    }
}
