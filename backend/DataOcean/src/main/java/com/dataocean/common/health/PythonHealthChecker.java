package com.dataocean.common.health;

import com.dataocean.module.system.service.NotificationService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Python 服务健康检查器
 * <p>
 * 每 30 秒调用 Python /health 端点，连续 3 次失败标记为不可用，
 * 恢复后自动标记为可用。使用 AtomicReference 保证线程安全。
 * 状态变更时通知超级管理员。
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PythonHealthChecker {

    /** 连续失败多少次后标记为不可用 */
    private static final int FAILURE_THRESHOLD = 3;

    /** 健康检查超时时间（毫秒） */
    private static final int HEALTH_CHECK_TIMEOUT_MS = 5_000;

    @Value("${dataocean.python-service.base-url:http://localhost:8000}")
    private String pythonServiceBaseUrl;

    private final NotificationService notificationService;

    /** 服务健康状态（线程安全） */
    private final AtomicReference<ServiceHealthInfo> healthInfo = new AtomicReference<>();

    private RestClient healthClient;

    /**
     * 初始化健康检查专用 RestClient（短超时）
     */
    @PostConstruct
    void init() {
        ServiceHealthInfo info = new ServiceHealthInfo("python-service");
        healthInfo.set(info);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(HEALTH_CHECK_TIMEOUT_MS);
        factory.setReadTimeout(HEALTH_CHECK_TIMEOUT_MS);
        this.healthClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(pythonServiceBaseUrl)
                .build();
        log.info("Python 健康检查器初始化完成 baseUrl={}", pythonServiceBaseUrl);
    }

    /**
     * 定时健康检查（每 30 秒执行一次）
     * <p>
     * 调用 Python /health 端点，成功则重置失败计数，
     * 失败则累加计数，达到阈值后标记为不可用。
     * 状态变更时发送通知给超级管理员。
     * </p>
     */
    @Scheduled(fixedRate = 30_000, initialDelay = 10_000)
    public void checkHealth() {
        ServiceHealthInfo current = healthInfo.get();
        ServiceHealthStatus previousStatus = current.getStatus();

        try {
            healthClient.get()
                    .uri("/health")
                    .retrieve()
                    .toBodilessEntity();

            // 检查成功
            current.setStatus(ServiceHealthStatus.AVAILABLE);
            current.setConsecutiveFailures(0);
            current.setLastCheckTime(LocalDateTime.now());
            current.setLastErrorMessage(null);

            // 状态从不可用恢复为可用时通知
            if (previousStatus == ServiceHealthStatus.UNAVAILABLE) {
                log.info("Python 服务已恢复，从 UNAVAILABLE 切换为 AVAILABLE");
                sendStatusChangeNotification("Python AI 服务已恢复正常");
            }

        } catch (Exception e) {
            int failures = current.getConsecutiveFailures() + 1;
            current.setConsecutiveFailures(failures);
            current.setLastCheckTime(LocalDateTime.now());
            current.setLastErrorMessage(e.getMessage());

            if (failures >= FAILURE_THRESHOLD && previousStatus == ServiceHealthStatus.AVAILABLE) {
                current.setStatus(ServiceHealthStatus.UNAVAILABLE);
                log.warn("Python 服务连续 {} 次健康检查失败，标记为不可用 error={}",
                        failures, e.getMessage());
                sendStatusChangeNotification("Python AI 服务不可用，连续 " + failures + " 次健康检查失败");
            } else {
                log.warn("Python 服务健康检查失败 consecutiveFailures={} error={}",
                        failures, e.getMessage());
            }
        }
    }

    /**
     * 判断 Python 服务是否可用
     *
     * @return true 表示服务可用
     */
    public boolean isAvailable() {
        return healthInfo.get().getStatus() == ServiceHealthStatus.AVAILABLE;
    }

    /**
     * 获取当前健康状态信息
     *
     * @return 服务健康信息快照
     */
    public ServiceHealthInfo getHealthInfo() {
        return healthInfo.get();
    }

    /**
     * 发送服务状态变更通知给超级管理员（userId=1）
     */
    private void sendStatusChangeNotification(String content) {
        try {
            notificationService.send("SYSTEM_ALERT", "服务状态变更", content, 1L);
        } catch (Exception e) {
            log.warn("发送服务状态变更通知失败: {}", e.getMessage());
        }
    }
}
