package com.dataocean.module.metadata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.DatasourceSecret;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceSecretMapper;
import com.dataocean.module.datasource.service.DatasourceSecretService;
import com.dataocean.module.metadata.collector.StatisticsCollector;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.service.SchemaStatisticsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaStatisticsServiceImpl implements SchemaStatisticsService {

    private final DatasourceMapper datasourceMapper;
    private final DatasourceSecretMapper secretMapper;
    private final DatasourceSecretService secretService;
    private final DbTableMetaMapper tableMetaMapper;
    private final DbColumnMetaMapper columnMetaMapper;
    private final StatisticsCollector statisticsCollector;
    private final ObjectMapper objectMapper;

    @Override
    public void collectStatistics(Long datasourceId, Long snapshotId) {
        Datasource datasource = datasourceMapper.selectById(datasourceId);
        DatasourceSecret secret = secretMapper.selectOne(
                new LambdaQueryWrapper<DatasourceSecret>()
                        .eq(DatasourceSecret::getDatasourceId, datasourceId)
        );

        String jdbcUrl = "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=%s"
                .formatted(datasource.getHost(), datasource.getPort(),
                        datasource.getDatabaseName(), datasource.getCharset());

        try (Connection connection = DriverManager.getConnection(
                jdbcUrl, secret.getUsername(), secretService.decrypt(secret.getEncryptedPassword()))) {

            List<DbTableMeta> tables = tableMetaMapper.selectList(
                    new LambdaQueryWrapper<DbTableMeta>()
                            .eq(DbTableMeta::getSnapshotId, snapshotId)
            );

            for (DbTableMeta table : tables) {
                Long rowCount = statisticsCollector.collectRowCount(connection, table.getTableName());
                if (rowCount != null) {
                    table.setRowCountEstimate(rowCount);
                    tableMetaMapper.updateById(table);
                }

                List<DbColumnMeta> columns = columnMetaMapper.selectList(
                        new LambdaQueryWrapper<DbColumnMeta>()
                                .eq(DbColumnMeta::getTableMetaId, table.getId())
                );

                for (DbColumnMeta column : columns) {
                    BigDecimal nullRate = statisticsCollector.collectNullRate(
                            connection, table.getTableName(), column.getColumnName());
                    if (nullRate != null) {
                        column.setNullRate(nullRate);
                    }

                    List<Map<String, Object>> topN = statisticsCollector.collectTopNValues(
                            connection, table.getTableName(), column.getColumnName(), 20);
                    if (!topN.isEmpty()) {
                        column.setEnumTopValues(objectMapper.writeValueAsString(topN));
                        column.setDistinctCount((long) topN.size());
                    }

                    columnMetaMapper.updateById(column);
                }
            }
            log.info("统计信息采集完成 datasourceId={} snapshotId={}", datasourceId, snapshotId);
        } catch (Exception e) {
            log.error("统计信息采集失败 datasourceId={}", datasourceId, e);
        }
    }
}
