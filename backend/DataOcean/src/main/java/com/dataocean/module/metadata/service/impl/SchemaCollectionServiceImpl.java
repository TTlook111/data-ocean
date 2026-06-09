package com.dataocean.module.metadata.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.common.util.HashUtils;
import com.dataocean.common.util.JdbcUrlBuilder;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.DatasourceSecret;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceSecretMapper;
import com.dataocean.module.datasource.service.DatasourceSecretService;
import com.dataocean.module.metadata.collector.ColumnCollector;
import com.dataocean.module.metadata.collector.IndexCollector;
import com.dataocean.module.metadata.collector.RelationCollector;
import com.dataocean.module.metadata.collector.TableCollector;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.entity.SchemaSyncTask;
import com.dataocean.module.metadata.entity.TableRelation;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import com.dataocean.module.metadata.service.SchemaCollectionService;
import com.dataocean.module.metadata.service.SchemaSnapshotService;
import com.dataocean.module.metadata.service.SchemaStatisticsService;
import com.dataocean.module.metadata.service.SchemaSyncTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Schema 采集服务实现类。
 * <p>
 * 编排元数据全量同步的完整流程：校验并发 → 创建任务 → 异步执行采集 →
 * 建立连接 → 采集表/字段/索引/关系 → 生成快照 → 可选采集统计信息。
 * 采集过程异步执行，通过任务状态和进度实时反馈前端。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaCollectionServiceImpl implements SchemaCollectionService {

    private final DatasourceMapper datasourceMapper;
    private final DatasourceSecretMapper secretMapper;
    private final DatasourceSecretService secretService;
    private final SchemaSyncTaskService syncTaskService;
    private final SchemaSnapshotService snapshotService;
    private final TableCollector tableCollector;
    private final ColumnCollector columnCollector;
    private final RelationCollector relationCollector;
    private final IndexCollector indexCollector;
    private final DbTableMetaMapper tableMetaMapper;
    private final DbColumnMetaMapper columnMetaMapper;
    private final TableRelationMapper relationMapper;
    private final SchemaStatisticsService statisticsService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /** 自身代理引用，用于调用 @Async 方法使代理生效 */
    @Lazy
    @Autowired
    private SchemaCollectionServiceImpl self;

    /**
     * {@inheritDoc}
     */
    @Override
    public Long executeFullSync(Long datasourceId, boolean includeStatistics) {
        return startFullSync(datasourceId, SchemaSyncTask.TRIGGER_MANUAL, UserContext.currentUserId(), includeStatistics);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long executeScheduledFullSync(Long datasourceId, boolean includeStatistics) {
        return startFullSync(datasourceId, SchemaSyncTask.TRIGGER_SCHEDULED, null, includeStatistics);
    }

    /**
     * 启动全量同步流程。
     * <p>
     * 先检查是否有运行中的任务（防止并发），然后创建任务并异步执行采集。
     * </p>
     *
     * @param datasourceId      数据源ID
     * @param triggerType       触发方式
     * @param triggeredBy       触发人ID
     * @param includeStatistics 是否采集统计信息
     * @return 任务ID
     */
    private Long startFullSync(Long datasourceId, String triggerType, Long triggeredBy, boolean includeStatistics) {
        // 检查是否有运行中的同步任务，防止并发采集
        SchemaSyncTask running = syncTaskService.getLatestTask(datasourceId);
        if (running != null && SchemaSyncTask.STATUS_RUNNING.equals(running.getStatus())) {
            throw new BusinessException(409, "该数据源已有同步任务运行中");
        }

        // 创建任务并通过代理调用异步方法
        SchemaSyncTask task = syncTaskService.createTask(datasourceId, triggerType, triggeredBy);
        self.doSyncAsync(datasourceId, task, includeStatistics);
        return task.getId();
    }

    /**
     * 异步执行同步任务。
     * <p>
     * 更新任务状态为运行中，执行采集逻辑，完成后更新为成功或失败。
     * </p>
     *
     * @param datasourceId      数据源ID
     * @param task              同步任务实体
     * @param includeStatistics 是否采集统计信息
     */
    @Async
    public void doSyncAsync(Long datasourceId, SchemaSyncTask task, boolean includeStatistics) {
        try {
            syncTaskService.updateStatus(task.getId(), SchemaSyncTask.STATUS_RUNNING, null);
            doSync(datasourceId, task, includeStatistics);
            syncTaskService.updateStatus(task.getId(), SchemaSyncTask.STATUS_SUCCESS, null);
        } catch (Exception e) {
            log.error("元数据同步失败 datasourceId={}", datasourceId, e);
            syncTaskService.updateStatus(task.getId(), SchemaSyncTask.STATUS_FAILED, e.getMessage());
        }
    }

    /**
     * 执行实际的元数据采集逻辑。
     * <p>
     * 流程：获取数据源信息 → 建立 JDBC 连接 → 创建快照 → 逐表采集（表/字段/索引/关系）
     * → 更新快照统计 → 可选采集统计信息。
     * </p>
     *
     * @param datasourceId      数据源ID
     * @param task              同步任务实体
     * @param includeStatistics 是否采集统计信息
     * @throws Exception 采集过程中的异常
     */
    private void doSync(Long datasourceId, SchemaSyncTask task, boolean includeStatistics) throws Exception {
        // 获取数据源基本信息
        Datasource datasource = datasourceMapper.selectById(datasourceId);
        if (datasource == null) {
            throw new BusinessException("数据源不存在");
        }

        // 获取数据源密钥信息
        DatasourceSecret secret = secretMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DatasourceSecret>()
                        .eq(DatasourceSecret::getDatasourceId, datasourceId)
        );
        if (secret == null) {
            throw new BusinessException("数据源密钥信息不存在");
        }

        // 构建 JDBC 连接 URL（使用统一的 URL 构建器，确保超时参数一致）
        String jdbcUrl = JdbcUrlBuilder.metadataMysqlUrl(
                datasource.getHost(), datasource.getPort(),
                datasource.getDatabaseName(), datasource.getCharset());
        String username = secret.getUsername();
        String password = secretService.decrypt(secret.getEncryptedPassword());

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            // 创建采集上下文，避免重复传递参数
            var ctx = com.dataocean.module.metadata.collector.CollectorContext.of(
                    connection, datasourceId, null);

            // 在事务内执行所有元数据写入，失败时整体回滚，避免残缺快照
            transactionTemplate.executeWithoutResult(status -> {
                try {
                    // 创建快照并关联到任务
                    MetadataSnapshot snapshot = snapshotService.createSnapshot(datasourceId, task.getId());
                    syncTaskService.linkSnapshot(task.getId(), snapshot.getId());

                    // 更新上下文中的 snapshotId
                    var snapshotCtx = com.dataocean.module.metadata.collector.CollectorContext.of(
                            connection, datasourceId, snapshot.getId());

                    // 采集所有表的基本信息
                    List<DbTableMeta> tables = tableCollector.collect(snapshotCtx);
                    syncTaskService.updateProgress(task.getId(), tables.size(), 0);

                    int totalColumns = 0;
                    StringBuilder hashBuilder = new StringBuilder();
                    List<TableRelation> allRelations = new ArrayList<>();

                    // 逐表采集字段、索引和关系信息
                    for (int i = 0; i < tables.size(); i++) {
                        DbTableMeta table = tables.get(i);

                        // 采集索引信息并序列化为 JSON
                        List<IndexCollector.IndexInfo> indexes = indexCollector.collect(snapshotCtx, table.getTableName());
                        if (!indexes.isEmpty()) {
                            try {
                                table.setIndexesInfo(objectMapper.writeValueAsString(indexes));
                            } catch (Exception e) {
                                log.warn("序列化索引信息失败 table={}", table.getTableName(), e);
                            }
                        }
                        tableMetaMapper.insert(table);

                        // 采集字段信息
                        List<DbColumnMeta> columns = columnCollector.collect(
                                snapshotCtx, table.getTableName(), table.getId());
                        for (DbColumnMeta column : columns) {
                            columnMetaMapper.insert(column);
                        }
                        totalColumns += columns.size();

                        // 采集外键关系
                        List<TableRelation> relations = relationCollector.collect(
                                snapshotCtx, table.getTableName());
                        allRelations.addAll(relations);

                        // 构建 Schema 哈希内容（表名:字段名,类型;...）
                        hashBuilder.append(table.getTableName()).append(":");
                        for (DbColumnMeta col : columns) {
                            hashBuilder.append(col.getColumnName()).append(",").append(col.getDataType()).append(";");
                        }

                        // 更新任务进度
                        syncTaskService.updateProgress(task.getId(), tables.size(), i + 1);
                    }

                    // 批量保存所有表关系
                    for (TableRelation relation : allRelations) {
                        relationMapper.insert(relation);
                    }

                    // 计算 Schema 哈希并更新快照统计信息
                    String schemaHash = md5(hashBuilder.toString());
                    snapshotService.updateStats(snapshot.getId(), tables.size(), totalColumns, schemaHash);

                    // 可选：采集统计信息（空值率、去重计数等）
                    if (includeStatistics) {
                        statisticsService.collectStatistics(datasourceId, snapshot.getId());
                    }

                    log.info("元数据同步完成 datasourceId={} 表数={} 字段数={}", datasourceId, tables.size(), totalColumns);
                } catch (Exception e) {
                    // 标记事务回滚
                    status.setRollbackOnly();
                    throw new RuntimeException("元数据采集事务内异常", e);
                }
            });
        }
    }

    /**
     * 计算字符串的 MD5 哈希值。
     *
     * @param input 输入字符串
     * @return 32位小写十六进制 MD5 值，异常时返回空字符串
     */
    private String md5(String input) {
        return HashUtils.md5Hex(input);
    }
}
