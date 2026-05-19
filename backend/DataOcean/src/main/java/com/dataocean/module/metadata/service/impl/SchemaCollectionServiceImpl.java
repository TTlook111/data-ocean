package com.dataocean.module.metadata.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.DatasourceSecret;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceSecretMapper;
import com.dataocean.module.datasource.service.DatasourceSecretService;
import com.dataocean.module.metadata.collector.ColumnCollector;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

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
    private final DbTableMetaMapper tableMetaMapper;
    private final DbColumnMetaMapper columnMetaMapper;
    private final TableRelationMapper relationMapper;
    private final SchemaStatisticsService statisticsService;

    @Async
    @Override
    public void executeFullSync(Long datasourceId, Long triggeredBy, boolean includeStatistics) {
        SchemaSyncTask running = syncTaskService.getLatestTask(datasourceId);
        if (running != null && SchemaSyncTask.STATUS_RUNNING.equals(running.getStatus())) {
            log.warn("数据源 {} 已有同步任务运行中，跳过本次触发", datasourceId);
            return;
        }

        SchemaSyncTask task = syncTaskService.createTask(datasourceId, SchemaSyncTask.TRIGGER_MANUAL, triggeredBy);
        try {
            syncTaskService.updateStatus(task.getId(), SchemaSyncTask.STATUS_RUNNING, null);
            doSync(datasourceId, task, includeStatistics);
            syncTaskService.updateStatus(task.getId(), SchemaSyncTask.STATUS_SUCCESS, null);
        } catch (Exception e) {
            log.error("元数据同步失败 datasourceId={}", datasourceId, e);
            syncTaskService.updateStatus(task.getId(), SchemaSyncTask.STATUS_FAILED, e.getMessage());
        }
    }

    private void doSync(Long datasourceId, SchemaSyncTask task, boolean includeStatistics) throws Exception {
        Datasource datasource = datasourceMapper.selectById(datasourceId);
        if (datasource == null) {
            throw new BusinessException("数据源不存在");
        }

        DatasourceSecret secret = secretMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DatasourceSecret>()
                        .eq(DatasourceSecret::getDatasourceId, datasourceId)
        );
        if (secret == null) {
            throw new BusinessException("数据源密钥信息不存在");
        }

        String jdbcUrl = "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=%s&connectTimeout=10000&socketTimeout=60000"
                .formatted(datasource.getHost(), datasource.getPort(),
                        datasource.getDatabaseName(), datasource.getCharset());
        String username = secret.getUsername();
        String password = secretService.decrypt(secret.getEncryptedPassword());

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            MetadataSnapshot snapshot = snapshotService.createSnapshot(datasourceId, task.getId());
            syncTaskService.linkSnapshot(task.getId(), snapshot.getId());

            List<DbTableMeta> tables = tableCollector.collect(connection, datasourceId, snapshot.getId());
            syncTaskService.updateProgress(task.getId(), tables.size(), 0);

            int totalColumns = 0;
            StringBuilder hashBuilder = new StringBuilder();
            List<TableRelation> allRelations = new ArrayList<>();

            for (int i = 0; i < tables.size(); i++) {
                DbTableMeta table = tables.get(i);
                tableMetaMapper.insert(table);

                List<DbColumnMeta> columns = columnCollector.collect(
                        connection, datasourceId, snapshot.getId(), table.getTableName(), table.getId());
                for (DbColumnMeta column : columns) {
                    columnMetaMapper.insert(column);
                }
                totalColumns += columns.size();

                List<TableRelation> relations = relationCollector.collect(
                        connection, datasourceId, snapshot.getId(), table.getTableName());
                allRelations.addAll(relations);

                hashBuilder.append(table.getTableName()).append(":");
                for (DbColumnMeta col : columns) {
                    hashBuilder.append(col.getColumnName()).append(",").append(col.getDataType()).append(";");
                }

                syncTaskService.updateProgress(task.getId(), tables.size(), i + 1);
            }

            for (TableRelation relation : allRelations) {
                relationMapper.insert(relation);
            }

            String schemaHash = md5(hashBuilder.toString());
            snapshotService.updateStats(snapshot.getId(), tables.size(), totalColumns, schemaHash);

            if (includeStatistics) {
                statisticsService.collectStatistics(datasourceId, snapshot.getId());
            }

            log.info("元数据同步完成 datasourceId={} 表数={} 字段数={}", datasourceId, tables.size(), totalColumns);
        }
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
