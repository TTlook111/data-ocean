package com.dataocean.module.datasource.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.DatasourceHealthCheck;
import com.dataocean.module.datasource.entity.DatasourceSecret;
import com.dataocean.module.datasource.entity.vo.DatasourceConnectionTestResult;
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

    @Scheduled(fixedDelayString = "${dataocean.datasource.health-check-interval-ms:300000}")
    public void checkEnabledDatasources() {
        List<Datasource> datasources = datasourceMapper.selectList(new LambdaQueryWrapper<Datasource>()
                .eq(Datasource::getDeleted, 0L)
                .eq(Datasource::getStatus, Datasource.STATUS_ENABLED));
        if (datasources.isEmpty()) {
            return;
        }
        log.debug("开始定时检查数据源健康状态 count={}", datasources.size());
        for (Datasource datasource : datasources) {
            checkOne(datasource);
        }
    }

    private void checkOne(Datasource datasource) {
        DatasourceSecret secret = secretMapper.selectOne(new LambdaQueryWrapper<DatasourceSecret>()
                .eq(DatasourceSecret::getDatasourceId, datasource.getId()));
        if (secret == null) {
            log.warn("跳过健康检查：数据源凭证不存在 datasourceId={}", datasource.getId());
            return;
        }
        DatasourceConnectionTestResult result = connectionService.testConnection(
                datasource,
                secret.getUsername(),
                secretService.decrypt(secret.getEncryptedPassword()),
                DatasourceHealthCheck.TYPE_SCHEDULED
        );
        if (Boolean.TRUE.equals(result.getSuccess())) {
            updateHealth(datasource, Datasource.HEALTH_HEALTHY);
            return;
        }
        if (hasThreeConsecutiveFailures(datasource.getId())) {
            updateHealth(datasource, Datasource.HEALTH_UNHEALTHY);
        }
    }

    private boolean hasThreeConsecutiveFailures(Long datasourceId) {
        List<DatasourceHealthCheck> checks = healthCheckMapper.selectList(new LambdaQueryWrapper<DatasourceHealthCheck>()
                .eq(DatasourceHealthCheck::getDatasourceId, datasourceId)
                .orderByDesc(DatasourceHealthCheck::getCheckedAt)
                .last("LIMIT 3"));
        return checks.size() == 3 && checks.stream().allMatch(check -> Integer.valueOf(0).equals(check.getSuccess()));
    }

    private void updateHealth(Datasource datasource, String healthStatus) {
        if (healthStatus.equals(datasource.getHealthStatus())) {
            return;
        }
        datasource.setHealthStatus(healthStatus);
        datasourceMapper.updateById(datasource);
        log.info("数据源健康状态更新 datasourceId={} healthStatus={}", datasource.getId(), healthStatus);
    }
}
