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

/**
 * Schema 统计信息采集服务实现类。
 * <p>
 * 连接业务数据源，逐表逐字段采集统计信息（行数、空值率、去重计数、TopN 值），
 * 并将结果更新到对应的表/字段元数据记录中。
 * </p>
 */
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void collectStatistics(Long datasourceId, Long snapshotId) {
        // 获取数据源连接信息
        Datasource datasource = datasourceMapper.selectById(datasourceId);
        DatasourceSecret secret = secretMapper.selectOne(
                new LambdaQueryWrapper<DatasourceSecret>()
                        .eq(DatasourceSecret::getDatasourceId, datasourceId)
        );

        // 构建 JDBC 连接 URL
        String jdbcUrl = "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=%s"
                .formatted(datasource.getHost(), datasource.getPort(),
                        datasource.getDatabaseName(), datasource.getCharset());

        try (Connection connection = DriverManager.getConnection(
                jdbcUrl, secret.getUsername(), secretService.decrypt(secret.getEncryptedPassword()))) {

            // 查询该快照下的所有表
            List<DbTableMeta> tables = tableMetaMapper.selectList(
                    new LambdaQueryWrapper<DbTableMeta>()
                            .eq(DbTableMeta::getSnapshotId, snapshotId)
            );

            // 逐表采集统计信息
            for (DbTableMeta table : tables) {
                // 采集表行数估算
                Long rowCount = statisticsCollector.collectRowCount(connection, table.getTableName());
                if (rowCount != null) {
                    table.setRowCountEstimate(rowCount);
                    tableMetaMapper.updateById(table);
                }

                // 查询该表下的所有字段
                List<DbColumnMeta> columns = columnMetaMapper.selectList(
                        new LambdaQueryWrapper<DbColumnMeta>()
                                .eq(DbColumnMeta::getTableMetaId, table.getId())
                );

                // 逐字段采集统计信息
                for (DbColumnMeta column : columns) {
                    // 采集空值率
                    BigDecimal nullRate = statisticsCollector.collectNullRate(
                            connection, table.getTableName(), column.getColumnName());
                    if (nullRate != null) {
                        column.setNullRate(nullRate);
                    }

                    // 采集去重计数
                    Long distinctCount = statisticsCollector.collectDistinctCount(
                            connection, table.getTableName(), column.getColumnName());
                    if (distinctCount != null) {
                        column.setDistinctCount(distinctCount);
                    }

                    // 采集 TopN 高频值
                    List<Map<String, Object>> topN = statisticsCollector.collectTopNValues(
                            connection, table.getTableName(), column.getColumnName(), 20);
                    if (!topN.isEmpty()) {
                        column.setEnumTopValues(objectMapper.writeValueAsString(topN));
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
