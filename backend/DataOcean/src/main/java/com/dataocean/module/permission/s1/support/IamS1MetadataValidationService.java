package com.dataocean.module.permission.s1.support;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.entity.IamS1ColumnFact;
import com.dataocean.module.permission.s1.entity.IamS1MetadataSnapshotFact;
import com.dataocean.module.permission.s1.entity.IamS1TableFact;
import com.dataocean.module.permission.s1.mapper.IamS1MetadataResourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** S1 授权写入共用的快照、表、字段存在性和治理准入校验。 */
@Service
@RequiredArgsConstructor
public class IamS1MetadataValidationService {

    private final IamS1MetadataResourceMapper metadataResourceMapper;

    public IamS1MetadataSnapshotFact requirePublishedSnapshot(Long datasourceId, Long snapshotId) {
        if (datasourceId == null || snapshotId == null) {
            throw new BusinessException("数据源和元数据快照不能为空");
        }
        IamS1MetadataSnapshotFact snapshot = metadataResourceMapper.selectSnapshot(snapshotId, datasourceId);
        if (snapshot == null || !"PUBLISHED".equals(snapshot.getStatus())) {
            throw new BusinessException("元数据快照不存在或未发布");
        }
        return snapshot;
    }

    public IamS1TableFact requireQueryableTable(Long datasourceId, Long snapshotId, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new BusinessException("表名不能为空");
        }
        requirePublishedSnapshot(datasourceId, snapshotId);
        IamS1TableFact table = metadataResourceMapper.selectTable(snapshotId, datasourceId, tableName.trim());
        if (table == null || table.getGovernanceStatus() == null) {
            throw new BusinessException("表不存在或治理状态不完整");
        }
        if ("BLOCKED".equals(table.getGovernanceStatus()) || "DEPRECATED".equals(table.getGovernanceStatus())) {
            throw new BusinessException("表当前治理状态不允许授权");
        }
        return table;
    }

    public Map<String, IamS1ColumnFact> requireColumns(Long datasourceId, Long snapshotId,
                                                        String tableName, Collection<String> columnNames) {
        if (columnNames == null || columnNames.isEmpty()) {
            throw new BusinessException("授权字段不能为空，空字段不表示全部字段");
        }
        IamS1TableFact table = requireQueryableTable(datasourceId, snapshotId, tableName);
        List<IamS1ColumnFact> columns = metadataResourceMapper.selectColumns(snapshotId, datasourceId, table.getTableName());
        if (columns == null) {
            throw new BusinessException("字段元数据读取失败");
        }
        Map<String, IamS1ColumnFact> available = new LinkedHashMap<>();
        for (IamS1ColumnFact column : columns) {
            if (column != null && column.getColumnName() != null) {
                available.put(column.getColumnName(), column);
            }
        }
        Map<String, IamS1ColumnFact> selected = new LinkedHashMap<>();
        for (String columnName : columnNames) {
            if (columnName == null || columnName.isBlank() || "*".equals(columnName)) {
                throw new BusinessException("授权字段必须明确选择，不能使用通配符");
            }
            IamS1ColumnFact column = available.get(columnName.trim());
            if (column == null || column.getId() == null || column.getGovernanceStatus() == null) {
                throw new BusinessException("字段不存在或治理状态不完整");
            }
            if ("BLOCKED".equals(column.getGovernanceStatus()) || "DEPRECATED".equals(column.getGovernanceStatus())) {
                throw new BusinessException("字段当前治理状态不允许授权");
            }
            selected.put(column.getColumnName(), column);
        }
        if (selected.isEmpty()) {
            throw new BusinessException("授权字段不能为空，空字段不表示全部字段");
        }
        return selected;
    }

    public IamS1ColumnFact requireColumn(Long datasourceId, Long snapshotId, String tableName,
                                         Long columnMetaId, String columnName) {
        if (columnMetaId == null || columnName == null || columnName.isBlank()) {
            throw new BusinessException("字段标识和字段名不能为空");
        }
        IamS1ColumnFact column = requireColumns(datasourceId, snapshotId, tableName, List.of(columnName.trim()))
                .get(columnName.trim());
        if (column == null || !columnMetaId.equals(column.getId())) {
            throw new BusinessException("字段标识与当前已发布快照不匹配");
        }
        return column;
    }
}
