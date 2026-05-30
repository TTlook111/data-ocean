package com.dataocean.common.health;

import com.dataocean.common.result.Result;
import com.dataocean.module.datasource.client.PythonPoolClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统健康状态与运维操作控制器。
 * <p>
 * 提供各服务组件的健康状态查询和 SQL 连接池管理接口，供管理后台展示。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/system")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('*')")
@Slf4j
public class SystemHealthController {

    private final PythonHealthChecker pythonHealthChecker;
    private final PythonPoolClient pythonPoolClient;
    private final DataSource dataSource;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 获取系统各服务健康状态
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

        // MySQL 状态（真实探测：尝试获取连接并校验有效性）
        Map<String, Object> mysqlStatus = checkMysqlHealth();
        healthMap.put("mysql", mysqlStatus);

        // Redis 状态（真实探测：执行 PING 命令）
        Map<String, Object> redisStatus = checkRedisHealth();
        healthMap.put("redis", redisStatus);

        // 总体状态：三项全部可用才算 HEALTHY，否则降级
        boolean pythonOk = pythonInfo.getStatus() == ServiceHealthStatus.AVAILABLE;
        boolean mysqlOk = "AVAILABLE".equals(mysqlStatus.get("status"));
        boolean redisOk = "AVAILABLE".equals(redisStatus.get("status"));
        healthMap.put("overall", (pythonOk && mysqlOk && redisOk) ? "HEALTHY" : "DEGRADED");
        healthMap.put("checkTime", LocalDateTime.now());

        return Result.success(healthMap);
    }

    /**
     * 获取 Python 侧 SQL 连接池仪表盘
     */
    @GetMapping("/sql-pools")
    public Result<Map<String, Object>> getSqlPoolDashboard() {
        return Result.success(pythonPoolClient.getPoolDashboard());
    }

    /**
     * 重置指定数据源的 SQL 连接池
     */
    @PostMapping("/sql-pools/{datasourceId}/reset")
    public Result<Void> resetSqlPool(@PathVariable Long datasourceId) {
        pythonPoolClient.resetPool(datasourceId);
        return Result.success("连接池已重置", null);
    }

    /** 检查 MySQL 健康状态：尝试获取连接并校验有效性（2 秒超时） */
    private Map<String, Object> checkMysqlHealth() {
        Map<String, Object> status = new HashMap<>();
        status.put("lastCheckTime", LocalDateTime.now());
        // try-with-resources 确保探测用的连接被归还连接池
        try (Connection connection = dataSource.getConnection()) {
            // isValid 在 2 秒内向数据库发起校验，避免连接虽存在但实际不可用的假阳性
            boolean valid = connection.isValid(2);
            if (valid) {
                status.put("status", "AVAILABLE");
                status.put("description", "可用");
            } else {
                status.put("status", "UNAVAILABLE");
                status.put("description", "不可用");
                status.put("lastErrorMessage", "连接有效性校验失败");
            }
        } catch (Exception e) {
            log.warn("MySQL 健康探测失败：{}", e.getMessage());
            status.put("status", "UNAVAILABLE");
            status.put("description", "不可用");
            status.put("lastErrorMessage", e.getMessage());
        }
        return status;
    }

    /** 检查 Redis 健康状态：执行 PING 命令真实探测 */
    private Map<String, Object> checkRedisHealth() {
        Map<String, Object> status = new HashMap<>();
        status.put("lastCheckTime", LocalDateTime.now());
        try {
            // 通过底层连接执行 PING，返回 PONG 表示 Redis 正常响应
            String pong = stringRedisTemplate.execute(connection -> connection.ping(), true);
            if (pong != null) {
                status.put("status", "AVAILABLE");
                status.put("description", "可用");
            } else {
                status.put("status", "UNAVAILABLE");
                status.put("description", "不可用");
                status.put("lastErrorMessage", "PING 无响应");
            }
        } catch (Exception e) {
            log.warn("Redis 健康探测失败：{}", e.getMessage());
            status.put("status", "UNAVAILABLE");
            status.put("description", "不可用");
            status.put("lastErrorMessage", e.getMessage());
        }
        return status;
    }
}
