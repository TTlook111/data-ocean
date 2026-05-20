package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.MetadataReviewRecord;
import com.dataocean.module.governance.mapper.MetadataReviewRecordMapper;
import com.dataocean.module.governance.service.GovernanceStatusService;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceStatusServiceImpl implements GovernanceStatusService {

    private final DbTableMetaMapper tableMetaMapper;
    private final DbColumnMetaMapper columnMetaMapper;
    private final MetadataReviewRecordMapper reviewRecordMapper;

    // RAG 准入状态白名单
    private static final Set<String> RAG_ELIGIBLE = Set.of("NORMAL", "RECOMMENDED", "SENSITIVE");
    private static final Set<String> VALID_STATUSES = Set.of(
            "DISCOVERED", "NORMAL", "RECOMMENDED", "DEPRECATED", "SENSITIVE", "BLOCKED");

    @Transactional
    @Override
    public Map<String, String> updateTableStatus(Long snapshotId, String tableName,
                                                 String newStatus, Long operatorId, String remark) {
        validateStatus(newStatus);

        DbTableMeta table = tableMetaMapper.selectOne(
                new LambdaQueryWrapper<DbTableMeta>()
                        .eq(DbTableMeta::getSnapshotId, snapshotId)
                        .eq(DbTableMeta::getTableName, tableName));
        if (table == null) {
            throw new BusinessException(404, "表不存在");
        }

        String oldStatus = table.getGovernanceStatus() != null ? table.getGovernanceStatus() : "DISCOVERED";
        table.setGovernanceStatus(newStatus);
        tableMetaMapper.updateById(table);

        recordReview(snapshotId, table.getDatasourceId(), MetadataReviewRecord.TARGET_TABLE,
                tableName, null, MetadataReviewRecord.ACTION_STATUS_CHANGE,
                oldStatus, newStatus, operatorId, remark);

        log.info("表治理状态变更 snapshot={} table={} {} → {}", snapshotId, tableName, oldStatus, newStatus);
        Map<String, String> result = new HashMap<>();
        result.put("tableName", tableName);
        result.put("oldStatus", oldStatus);
        result.put("newStatus", newStatus);
        return result;
    }

    @Transactional
    @Override
    public Map<String, String> updateColumnStatus(Long snapshotId, Long columnId,
                                                  String newStatus, Long operatorId, String remark) {
        validateStatus(newStatus);

        DbColumnMeta column = columnMetaMapper.selectById(columnId);
        if (column == null || !column.getSnapshotId().equals(snapshotId)) {
            throw new BusinessException(404, "字段不存在");
        }

        String oldStatus = column.getGovernanceStatus() != null ? column.getGovernanceStatus() : "DISCOVERED";
        column.setGovernanceStatus(newStatus);
        columnMetaMapper.updateById(column);

        recordReview(snapshotId, column.getDatasourceId(), MetadataReviewRecord.TARGET_COLUMN,
                column.getTableName(), column.getColumnName(), MetadataReviewRecord.ACTION_STATUS_CHANGE,
                oldStatus, newStatus, operatorId, remark);

        log.info("字段治理状态变更 snapshot={} {}.{} {} → {}",
                snapshotId, column.getTableName(), column.getColumnName(), oldStatus, newStatus);
        Map<String, String> result = new HashMap<>();
        result.put("tableName", column.getTableName());
        result.put("columnName", column.getColumnName());
        result.put("oldStatus", oldStatus);
        result.put("newStatus", newStatus);
        return result;
    }

    @Transactional
    @Override
    public Map<String, Object> batchUpdateColumnStatus(Long snapshotId, String tableName,
                                                       String newStatus, Long operatorId,
                                                       String remark, List<String> excludeColumns) {
        validateStatus(newStatus);

        LambdaQueryWrapper<DbColumnMeta> qw = new LambdaQueryWrapper<DbColumnMeta>()
                .eq(DbColumnMeta::getSnapshotId, snapshotId)
                .eq(DbColumnMeta::getTableName, tableName);
        if (!CollectionUtils.isEmpty(excludeColumns)) {
            qw.notIn(DbColumnMeta::getColumnName, excludeColumns);
        }

        List<DbColumnMeta> columns = columnMetaMapper.selectList(qw);
        int updated = 0;
        for (DbColumnMeta col : columns) {
            if (!newStatus.equals(col.getGovernanceStatus())) {
                String oldStatus = col.getGovernanceStatus();
                col.setGovernanceStatus(newStatus);
                columnMetaMapper.updateById(col);
                updated++;

                recordReview(snapshotId, col.getDatasourceId(), MetadataReviewRecord.TARGET_COLUMN,
                        tableName, col.getColumnName(), MetadataReviewRecord.ACTION_BATCH_STATUS_CHANGE,
                        oldStatus, newStatus, operatorId, remark);
            }
        }

        int excluded = CollectionUtils.isEmpty(excludeColumns) ? 0 : excludeColumns.size();
        log.info("批量字段状态变更 snapshot={} table={} updated={} excluded={}", snapshotId, tableName, updated, excluded);
        return Map.of("updated", updated, "excluded", excluded);
    }

    @Override
    public boolean isEligibleForRag(String governanceStatus) {
        return RAG_ELIGIBLE.contains(governanceStatus);
    }

    private void validateStatus(String status) {
        if (!VALID_STATUSES.contains(status)) {
            throw new BusinessException(400, "无效的治理状态: " + status);
        }
    }

    private void recordReview(Long snapshotId, Long datasourceId, String targetType,
                              String tableName, String columnName, String action,
                              String oldStatus, String newStatus, Long operatorId, String remark) {
        MetadataReviewRecord record = new MetadataReviewRecord();
        record.setSnapshotId(snapshotId);
        record.setDatasourceId(datasourceId);
        record.setTargetType(targetType);
        record.setTableName(tableName);
        record.setColumnName(columnName);
        record.setAction(action);
        record.setOldStatus(oldStatus);
        record.setNewStatus(newStatus);
        record.setOperatorId(operatorId);
        record.setRemark(remark);
        reviewRecordMapper.insert(record);
    }
}
