package com.dataocean.module.metadata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.entity.SchemaChangeEvent;
import com.dataocean.module.metadata.entity.vo.SchemaDiffVO;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.mapper.SchemaChangeEventMapper;
import com.dataocean.module.metadata.service.SchemaDiffService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SchemaDiffServiceImpl implements SchemaDiffService {

    private final MetadataSnapshotMapper snapshotMapper;
    private final DbTableMetaMapper tableMetaMapper;
    private final DbColumnMetaMapper columnMetaMapper;
    private final SchemaChangeEventMapper changeEventMapper;

    @Transactional
    @Override
    public SchemaDiffVO compareSnapshots(Long oldSnapshotId, Long newSnapshotId) {
        MetadataSnapshot oldSnapshot = snapshotMapper.selectById(oldSnapshotId);
        MetadataSnapshot newSnapshot = snapshotMapper.selectById(newSnapshotId);

        List<DbTableMeta> oldTables = getTablesBySnapshot(oldSnapshotId);
        List<DbTableMeta> newTables = getTablesBySnapshot(newSnapshotId);

        Set<String> oldTableNames = oldTables.stream().map(DbTableMeta::getTableName).collect(Collectors.toSet());
        Set<String> newTableNames = newTables.stream().map(DbTableMeta::getTableName).collect(Collectors.toSet());

        SchemaDiffVO diff = new SchemaDiffVO();
        diff.setAddedTables(newTableNames.stream().filter(n -> !oldTableNames.contains(n)).toList());
        diff.setRemovedTables(oldTableNames.stream().filter(n -> !newTableNames.contains(n)).toList());

        List<SchemaDiffVO.ColumnChange> addedColumns = new ArrayList<>();
        List<SchemaDiffVO.ColumnChange> removedColumns = new ArrayList<>();
        List<SchemaDiffVO.ColumnChange> modifiedColumns = new ArrayList<>();

        Set<String> commonTables = newTableNames.stream().filter(oldTableNames::contains).collect(Collectors.toSet());
        for (String tableName : commonTables) {
            Map<String, DbColumnMeta> oldCols = getColumnsBySnapshotAndTable(oldSnapshotId, tableName)
                    .stream().collect(Collectors.toMap(DbColumnMeta::getColumnName, c -> c));
            Map<String, DbColumnMeta> newCols = getColumnsBySnapshotAndTable(newSnapshotId, tableName)
                    .stream().collect(Collectors.toMap(DbColumnMeta::getColumnName, c -> c));

            for (String colName : newCols.keySet()) {
                if (!oldCols.containsKey(colName)) {
                    SchemaDiffVO.ColumnChange change = new SchemaDiffVO.ColumnChange();
                    change.setTableName(tableName);
                    change.setColumnName(colName);
                    change.setNewType(newCols.get(colName).getDataType());
                    addedColumns.add(change);
                }
            }
            for (String colName : oldCols.keySet()) {
                if (!newCols.containsKey(colName)) {
                    SchemaDiffVO.ColumnChange change = new SchemaDiffVO.ColumnChange();
                    change.setTableName(tableName);
                    change.setColumnName(colName);
                    change.setOldType(oldCols.get(colName).getDataType());
                    removedColumns.add(change);
                }
            }
            for (String colName : newCols.keySet()) {
                if (oldCols.containsKey(colName)) {
                    DbColumnMeta oldCol = oldCols.get(colName);
                    DbColumnMeta newCol = newCols.get(colName);
                    boolean typeChanged = !oldCol.getDataType().equals(newCol.getDataType());
                    boolean commentChanged = !equalsNullSafe(oldCol.getColumnComment(), newCol.getColumnComment());
                    if (typeChanged || commentChanged) {
                        SchemaDiffVO.ColumnChange change = new SchemaDiffVO.ColumnChange();
                        change.setTableName(tableName);
                        change.setColumnName(colName);
                        change.setOldType(oldCol.getDataType());
                        change.setNewType(newCol.getDataType());
                        change.setOldComment(oldCol.getColumnComment());
                        change.setNewComment(newCol.getColumnComment());
                        modifiedColumns.add(change);
                    }
                }
            }
        }

        diff.setAddedColumns(addedColumns);
        diff.setRemovedColumns(removedColumns);
        diff.setModifiedColumns(modifiedColumns);

        generateChangeEvents(oldSnapshot, newSnapshot, diff);
        return diff;
    }

    private void generateChangeEvents(MetadataSnapshot oldSnapshot, MetadataSnapshot newSnapshot, SchemaDiffVO diff) {
        Long datasourceId = newSnapshot.getDatasourceId();
        Long oldId = oldSnapshot.getId();
        Long newId = newSnapshot.getId();

        for (String table : diff.getAddedTables()) {
            saveEvent(datasourceId, oldId, newId, SchemaChangeEvent.CHANGE_TABLE_ADDED, table, null, null, null, SchemaChangeEvent.RISK_MEDIUM);
        }
        for (String table : diff.getRemovedTables()) {
            saveEvent(datasourceId, oldId, newId, SchemaChangeEvent.CHANGE_TABLE_REMOVED, table, null, null, null, SchemaChangeEvent.RISK_HIGH);
        }
        for (SchemaDiffVO.ColumnChange col : diff.getAddedColumns()) {
            saveEvent(datasourceId, oldId, newId, SchemaChangeEvent.CHANGE_COLUMN_ADDED, col.getTableName(), col.getColumnName(), null, col.getNewType(), SchemaChangeEvent.RISK_LOW);
        }
        for (SchemaDiffVO.ColumnChange col : diff.getRemovedColumns()) {
            saveEvent(datasourceId, oldId, newId, SchemaChangeEvent.CHANGE_COLUMN_REMOVED, col.getTableName(), col.getColumnName(), col.getOldType(), null, SchemaChangeEvent.RISK_HIGH);
        }
        for (SchemaDiffVO.ColumnChange col : diff.getModifiedColumns()) {
            if (!equalsNullSafe(col.getOldType(), col.getNewType())) {
                saveEvent(datasourceId, oldId, newId, SchemaChangeEvent.CHANGE_COLUMN_TYPE_CHANGED, col.getTableName(), col.getColumnName(), col.getOldType(), col.getNewType(), SchemaChangeEvent.RISK_MEDIUM);
            }
            if (!equalsNullSafe(col.getOldComment(), col.getNewComment())) {
                saveEvent(datasourceId, oldId, newId, SchemaChangeEvent.CHANGE_COMMENT_CHANGED, col.getTableName(), col.getColumnName(), col.getOldComment(), col.getNewComment(), SchemaChangeEvent.RISK_LOW);
            }
        }
    }

    private void saveEvent(Long datasourceId, Long oldSnapshotId, Long newSnapshotId,
                           String changeType, String tableName, String columnName,
                           String oldValue, String newValue, String riskLevel) {
        SchemaChangeEvent event = new SchemaChangeEvent();
        event.setDatasourceId(datasourceId);
        event.setOldSnapshotId(oldSnapshotId);
        event.setNewSnapshotId(newSnapshotId);
        event.setChangeType(changeType);
        event.setTableName(tableName);
        event.setColumnName(columnName);
        event.setOldValue(oldValue);
        event.setNewValue(newValue);
        event.setRiskLevel(riskLevel);
        changeEventMapper.insert(event);
    }

    private List<DbTableMeta> getTablesBySnapshot(Long snapshotId) {
        return tableMetaMapper.selectList(
                new LambdaQueryWrapper<DbTableMeta>().eq(DbTableMeta::getSnapshotId, snapshotId));
    }

    private List<DbColumnMeta> getColumnsBySnapshotAndTable(Long snapshotId, String tableName) {
        return columnMetaMapper.selectList(
                new LambdaQueryWrapper<DbColumnMeta>()
                        .eq(DbColumnMeta::getSnapshotId, snapshotId)
                        .eq(DbColumnMeta::getTableName, tableName));
    }

    private boolean equalsNullSafe(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
