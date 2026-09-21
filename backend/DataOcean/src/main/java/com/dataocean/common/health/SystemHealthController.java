package com.dataocean.common.health;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.health.dto.SqlPoolResetDTO;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;

import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolverRegistry;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.dataocean.module.permission.s1.annotation.IamS1Global;
import com.dataocean.module.datasource.client.PythonPoolClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
@Slf4j
public class SystemHealthController {

    /** 查看服务状态与连接池。 */
    private static final String VIEW_FUNCTION = "system:runtime:view";
    /** 维护运行监控：重置连接池属于高影响操作。 */
    private static final String MANAGE_FUNCTION = "system:runtime:manage";

    private final PythonHealthChecker pythonHealthChecker;
    private final IamS1AdminGuard adminGuard;
    private final IamS1ResourceResolverRegistry datasourceResolverRegistry;
    private final PythonPoolClient pythonPoolClient;
    private final DataSource dataSource;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 获取系统各服务健康状态
     */
    @GetMapping("/health")
    @IamS1Global(VIEW_FUNCTION)
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
    @IamS1Global(VIEW_FUNCTION)
    public Result<Map<String, Object>> getSqlPoolDashboard() {
        return Result.success(pythonPoolClient.getPoolDashboard());
    }

    /**
     * 重置指定数据源的 SQL 连接池。
     *
     * <p>重置会中断该数据源**正在执行**的查询，属于高影响操作，因此除
     * `system:runtime:manage` + 目标数据源负责范围外，还要求：</p>
     *
     * <ul>
     *   <li>受保护 S1 系统管理员——只负责一个数据源的人不应能重置别人的连接池；</li>
     *   <li>请求体里显式的确认标记与原因，避免误触与无据可查；</li>
     *   <li>审计记录数据源 ID、原因与影响说明。</li>
     * </ul>
     *
     * <p>响应与日志都不包含连接串或密码。</p>
     */
    @PostMapping("/sql-pools/{datasourceId}/reset")
    @IamS1Global(MANAGE_FUNCTION)
    public Result<Void> resetSqlPool(@PathVariable Long datasourceId,
                                     @RequestBody(required = false) SqlPoolResetDTO request) {
        if (request == null || !Boolean.TRUE.equals(request.getConfirmed())) {
            throw new BusinessException(400, "重置连接池会中断该数据源正在执行的查询，必须显式确认");
        }
        if (!StringUtils.hasText(request.getReason())) {
            throw new BusinessException(400, "必须说明重置原因");
        }
        // `system:runtime:manage` 在 B0 冻结为**全局**功能，因此负责源校验不能靠注解表达，
        // 这里显式走 DATASOURCE 解析器：数据源不存在 404、归属断链 409，一律先 fail-closed。
        IamS1ResolvedResource resolved = datasourceResolverRegistry
                .require(IamS1ResourceType.DATASOURCE)
                .resolve(datasourceId);
        if (resolved == null || !resolved.hasDatasource()) {
            throw new BusinessException(409, "数据源归属不完整，无法判定重置范围");
        }
        if (!adminGuard.isSystemAdmin(UserContext.currentUserId())) {
            throw new BusinessException(403, "重置连接池只能由受保护的系统管理员执行");
        }
        // 审计：谁在什么时候为什么重置了哪个源。不记录连接串或密码。
        log.warn("重置 SQL 连接池 datasourceId={} 原因={}", resolved.datasourceId(), request.getReason());
        pythonPoolClient.resetPool(resolved.datasourceId());
        return Result.success("连接池已重置；该数据源正在执行的查询已被中断", null);
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
