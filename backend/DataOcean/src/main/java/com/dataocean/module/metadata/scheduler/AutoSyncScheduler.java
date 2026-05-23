package com.dataocean.module.metadata.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.metadata.service.SchemaCollectionService;
import com.dataocean.module.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * 元数据自动同步调度器。
 * <p>
 * 根据系统配置动态注册或取消定时同步任务，对启用的数据源触发定时全量采集。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoSyncScheduler {

    private static final String KEY_ENABLED = "metadata.auto-sync.enabled";
    private static final String KEY_CRON = "metadata.auto-sync.cron";
    private static final String DEFAULT_CRON = "0 0 2 * * ?";

    private final DatasourceMapper datasourceMapper;
    private final SchemaCollectionService collectionService;
    private final SysConfigService configService;
    private final TaskScheduler taskScheduler;

    private volatile ScheduledFuture<?> scheduledFuture;

    /**
     * 应用启动后根据配置初始化自动同步任务。
     */
    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * 重新加载自动同步配置并刷新调度任务。
     */
    public synchronized void refresh() {
        String enabled = configService.getValue(KEY_ENABLED, "false");
        String cron = configService.getValue(KEY_CRON, DEFAULT_CRON);

        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }

        if (!"true".equalsIgnoreCase(enabled)) {
            log.info("元数据自动同步已禁用");
            return;
        }

        scheduledFuture = taskScheduler.schedule(this::executeAutoSync, new CronTrigger(cron));
        log.info("元数据自动同步已启用，cron={}", cron);
    }

    /**
     * 判断自动同步任务是否正在运行。
     *
     * @return true 表示已注册有效调度任务
     */
    public boolean isRunning() {
        return scheduledFuture != null && !scheduledFuture.isCancelled();
    }

    private void executeAutoSync() {
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
