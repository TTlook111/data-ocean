package com.dataocean.module.datasource.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.DatasourceHealthCheck;
import com.dataocean.module.datasource.entity.DatasourceSecret;
import com.dataocean.module.datasource.entity.vo.DatasourceConnectionTestVO;
import com.dataocean.module.datasource.mapper.DatasourceHealthCheckMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceSecretMapper;
import com.dataocean.module.datasource.service.DatasourceConnectionService;
import com.dataocean.module.datasource.service.DatasourceSecretService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据源健康检查定时任务
 * <p>
 * 定期对所有已启用的数据源执行连接健康检查。
 * 单次检查成功立即标记为 HEALTHY；连续 3 次失败则标记为 UNHEALTHY。
 * 可通过配置 dataocean.datasource.health-check.enabled=false 关闭。
 * </p>
 *
 * @author dataocean
 */
@Component
@ConditionalOnProperty(prefix = "dataocean.datasource.health-check", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class DatasourceHealthCheckScheduler {

    private final DatasourceMapper datasourceMapper;
    private final DatasourceSecretMapper secretMapper;
    private final DatasourceHealthCheckMapper healthCheckMapper;
    private final DatasourceSecretService secretService;
    private final DatasourceConnectionService connectionService;

    /**
     * 定时检查所有已启用数据源的健康状态
     * <p>
     * 执行间隔通过配置项 dataocean.datasource.health-check-interval-ms 控制，默认 5 分钟。
     * 遍历所有已启用且未删除的数据源，逐个执行连接测试。
     * </p>
     */
    @Scheduled(fixedDelayString = "${dataocean.datasource.health-check-interval-ms:300000}")
    public void checkEnabledDatasources() {
        // 查询所有已启用且未删除的数据源
        List<Datasource> datasources = datasourceMapper.selectList(new LambdaQueryWrapper<Datasource>()
                .eq(Datasource::getDeleted, 0L)
                .eq(Datasource::getStatus, Datasource.STATUS_ENABLED));
        if (datasources.isEmpty()) {
            return;
        }
        log.debug("开始定时检查数据源健康状态 count={}", datasources.size());
        // 逐个检查每个数据源
        for (Datasource datasource : datasources) {
            checkOne(datasource);
        }
    }

    /**
     * 对单个数据源执行健康检查
     * <p>
     * 获取凭证后执行连接测试，成功则标记为 HEALTHY，
     * 连续 3 次失败则标记为 UNHEALTHY。
     * </p>
     *
     * @param datasource 待检查的数据源实体
     */
    private void checkOne(Datasource datasource) {
        // 获取数据源凭证
        DatasourceSecret secret = secretMapper.selectOne(new LambdaQueryWrapper<DatasourceSecret>()
                .eq(DatasourceSecret::getDatasourceId, datasource.getId()));
        if (secret == null) {
            log.warn("跳过健康检查：数据源凭证不存在 datasourceId={}", datasource.getId());
            return;
        }
        // 执行连接测试
        DatasourceConnectionTestVO result = connectionService.testConnection(
                datasource,
                secret.getUsername(),
                secretService.decrypt(secret.getEncryptedPassword()),
                DatasourceHealthCheck.TYPE_SCHEDULED
        );
        if (Boolean.TRUE.equals(result.getSuccess())) {
            // 单次成功即恢复为健康状态
            updateHealth(datasource, Datasource.HEALTH_HEALTHY);
            return;
        }
        // 检查是否连续 3 次失败，是则标记为不健康
        if (hasThreeConsecutiveFailures(datasource.getId())) {
            updateHealth(datasource, Datasource.HEALTH_UNHEALTHY);
        }
    }

    /**
     * 判断数据源是否连续 3 次健康检查失败
     *
     * @param datasourceId 数据源 ID
     * @return true-连续 3 次失败，false-否
     */
    private boolean hasThreeConsecutiveFailures(Long datasourceId) {
        // 查询最近 3 条健康检查记录
        List<DatasourceHealthCheck> checks = healthCheckMapper.selectList(new LambdaQueryWrapper<DatasourceHealthCheck>()
                .eq(DatasourceHealthCheck::getDatasourceId, datasourceId)
                .orderByDesc(DatasourceHealthCheck::getCheckedAt)
                .last("LIMIT 3"));
        // 3 条记录全部失败才判定为连续失败
        return checks.size() == 3 && checks.stream().allMatch(check -> Integer.valueOf(0).equals(check.getSuccess()));
    }

    /**
     * 更新数据源健康状态（仅在状态变化时执行更新）
     *
     * @param datasource   数据源实体
     * @param healthStatus 新的健康状态
     */
    private void updateHealth(Datasource datasource, String healthStatus) {
        // 状态未变化时跳过更新
        if (healthStatus.equals(datasource.getHealthStatus())) {
            return;
        }
        datasource.setHealthStatus(healthStatus);
        datasourceMapper.updateById(datasource);
        log.info("数据源健康状态更新 datasourceId={} healthStatus={}", datasource.getId(), healthStatus);
    }
}
