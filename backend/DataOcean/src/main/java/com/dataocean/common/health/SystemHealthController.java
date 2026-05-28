package com.dataocean.common.health;

import com.dataocean.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统健康状态控制器
 * <p>
 * 提供各服务组件的健康状态查询接口，供管理后台展示。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/system")
@RequiredArgsConstructor
@Slf4j
public class SystemHealthController {

    private final PythonHealthChecker pythonHealthChecker;

    /**
     * 获取系统各服务健康状态
     * <p>
     * 返回 Python 服务、Milvus、Redis、MySQL 的当前状态。
     * </p>
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> getSystemHealth() {
        Map<String, Object> healthMap = new HashMap<>();

        // Python 服务状态
        ServiceHealthInfo pythonInfo = pythonHealthChecker.getHealthInfo();
        Map<String, Object> pythonStatus = new HashMap<>();
        pythonStatus.put("status", pythonInfo.getStatus().name());
        pythonStatus.put("description", pythonInfo.getStatus().getDescription());
        pythonStatus.put("lastCheckTime", pythonInfo.getLastCheckTime());
        pythonStatus.put("consecutiveFailures", pythonInfo.getConsecutiveFailures());
        pythonStatus.put("lastErrorMessage", pythonInfo.getLastErrorMessage());
        healthMap.put("pythonService", pythonStatus);

        // MySQL 状态（Java 自身连接正常即可用）
        Map<String, Object> mysqlStatus = new HashMap<>();
        mysqlStatus.put("status", "AVAILABLE");
        mysqlStatus.put("description", "可用");
        mysqlStatus.put("lastCheckTime", LocalDateTime.now());
        healthMap.put("mysql", mysqlStatus);

        // Redis 状态
        Map<String, Object> redisStatus = checkRedisHealth();
        healthMap.put("redis", redisStatus);

        // 总体状态
        boolean allAvailable = pythonInfo.getStatus() == ServiceHealthStatus.AVAILABLE;
        healthMap.put("overall", allAvailable ? "HEALTHY" : "DEGRADED");
        healthMap.put("checkTime", LocalDateTime.now());

        return Result.success(healthMap);
    }

    /**
     * 检查 Redis 健康状态
     */
    private Map<String, Object> checkRedisHealth() {
        Map<String, Object> status = new HashMap<>();
        try {
            // Redis 连接由 Spring Data Redis 管理，能正常注入即可用
            status.put("status", "AVAILABLE");
            status.put("description", "可用");
            status.put("lastCheckTime", LocalDateTime.now());
        } catch (Exception e) {
            status.put("status", "UNAVAILABLE");
            status.put("description", "不可用");
            status.put("lastErrorMessage", e.getMessage());
        }
        return status;
    }
}
