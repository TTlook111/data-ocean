package com.dataocean.module.metadata.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.metadata.service.SchemaCollectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dataocean.metadata.auto-sync.enabled", havingValue = "true", matchIfMissing = false)
public class AutoSyncScheduler {

    private final DatasourceMapper datasourceMapper;
    private final SchemaCollectionService collectionService;

    @Scheduled(cron = "${dataocean.metadata.auto-sync.cron:0 0 2 * * ?}")
    public void executeAutoSync() {
        log.info("开始执行定时元数据同步");
        List<Datasource> datasources = datasourceMapper.selectList(
                new LambdaQueryWrapper<Datasource>()
                        .eq(Datasource::getStatus, Datasource.STATUS_ENABLED)
                        .eq(Datasource::getDeleted, 0L)
        );

        for (Datasource ds : datasources) {
            try {
                collectionService.executeScheduledFullSync(ds.getId(), false);
                log.info("定时同步已触发 datasourceId={} name={}", ds.getId(), ds.getName());
            } catch (Exception e) {
                log.error("定时同步触发失败 datasourceId={}", ds.getId(), e);
            }
        }
        log.info("定时元数据同步触发完成，共 {} 个数据源", datasources.size());
    }
}
