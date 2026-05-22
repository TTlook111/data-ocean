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

/**
 * Schema 差异对比服务实现类。
 * <p>
 * 对比两个快照之间的表和字段变更，生成差异报告，并将变更事件持久化到数据库。
 * 变更事件附带风险等级评估：删除表/字段为高风险，新增表/类型变更为中风险，
 * 新增字段/注释变更为低风险。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class SchemaDiffServiceImpl implements SchemaDiffService {

    private final MetadataSnapshotMapper snapshotMapper;
    private final DbTableMetaMapper tableMetaMapper;
    private final DbColumnMetaMapper columnMetaMapper;
    private final SchemaChangeEventMapper changeEventMapper;

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public SchemaDiffVO compareSnapshots(Long oldSnapshotId, Long newSnapshotId) {
        MetadataSnapshot oldSnapshot = snapshotMapper.selectById(oldSnapshotId);
        MetadataSnapshot newSnapshot = snapshotMapper.selectById(newSnapshotId);

        // 获取新旧快照的表列表
        List<DbTableMeta> oldTables = getTablesBySnapshot(oldSnapshotId);
        List<DbTableMeta> newTables = getTablesBySnapshot(newSnapshotId);

        Set<String> oldTableNames = oldTables.stream().map(DbTableMeta::getTableName).collect(Collectors.toSet());
        Set<String> newTableNames = newTables.stream().map(DbTableMeta::getTableName).collect(Collectors.toSet());

        // 计算表级别的新增和删除
        SchemaDiffVO diff = new SchemaDiffVO();
        diff.setAddedTables(newTableNames.stream().filter(n -> !oldTableNames.contains(n)).toList());
        diff.setRemovedTables(oldTableNames.stream().filter(n -> !newTableNames.contains(n)).toList());

        // 对比共有表的字段级别变更
        List<SchemaDiffVO.ColumnChange> addedColumns = new ArrayList<>();
        List<SchemaDiffVO.ColumnChange> removedColumns = new ArrayList<>();
        List<SchemaDiffVO.ColumnChange> modifiedColumns = new ArrayList<>();

        Set<String> commonTables = newTableNames.stream().filter(oldTableNames::contains).collect(Collectors.toSet());
        for (String tableName : commonTables) {
            // 构建新旧字段映射
            Map<String, DbColumnMeta> oldCols = getColumnsBySnapshotAndTable(oldSnapshotId, tableName)
                    .stream().collect(Collectors.toMap(DbColumnMeta::getColumnName, c -> c));
            Map<String, DbColumnMeta> newCols = getColumnsBySnapshotAndTable(newSnapshotId, tableName)
                    .stream().collect(Collectors.toMap(DbColumnMeta::getColumnName, c -> c));

            // 检测新增字段
            for (String colName : newCols.keySet()) {
                if (!oldCols.containsKey(colName)) {
                    SchemaDiffVO.ColumnChange change = new SchemaDiffVO.ColumnChange();
                    change.setTableName(tableName);
                    change.setColumnName(colName);
                    change.setNewType(newCols.get(colName).getDataType());
                    addedColumns.add(change);
                }
            }
            // 检测删除字段
            for (String colName : oldCols.keySet()) {
                if (!newCols.containsKey(colName)) {
                    SchemaDiffVO.ColumnChange change = new SchemaDiffVO.ColumnChange();
                    change.setTableName(tableName);
                    change.setColumnName(colName);
                    change.setOldType(oldCols.get(colName).getDataType());
                    removedColumns.add(change);
                }
            }
            // 检测修改字段（类型或注释变更）
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

        // 将差异结果持久化为变更事件
        generateChangeEvents(oldSnapshot, newSnapshot, diff);
        return diff;
    }

    /**
     * 根据差异结果生成变更事件并持久化。
     *
     * @param oldSnapshot 旧快照
     * @param newSnapshot 新快照
     * @param diff        差异对比结果
     */
    private void generateChangeEvents(MetadataSnapshot oldSnapshot, MetadataSnapshot newSnapshot, SchemaDiffVO diff) {
        Long datasourceId = newSnapshot.getDatasourceId();
        Long oldId = oldSnapshot.getId();
        Long newId = newSnapshot.getId();

        // 新增表事件（中风险）
        for (String table : diff.getAddedTables()) {
            saveEvent(datasourceId, oldId, newId, SchemaChangeEvent.CHANGE_TABLE_ADDED, table, null, null, null, SchemaChangeEvent.RISK_MEDIUM);
        }
        // 删除表事件（高风险）
        for (String table : diff.getRemovedTables()) {
            saveEvent(datasourceId, oldId, newId, SchemaChangeEvent.CHANGE_TABLE_REMOVED, table, null, null, null, SchemaChangeEvent.RISK_HIGH);
        }
        // 新增字段事件（低风险）
        for (SchemaDiffVO.ColumnChange col : diff.getAddedColumns()) {
            saveEvent(datasourceId, oldId, newId, SchemaChangeEvent.CHANGE_COLUMN_ADDED, col.getTableName(), col.getColumnName(), null, col.getNewType(), SchemaChangeEvent.RISK_LOW);
        }
        // 删除字段事件（高风险）
        for (SchemaDiffVO.ColumnChange col : diff.getRemovedColumns()) {
            saveEvent(datasourceId, oldId, newId, SchemaChangeEvent.CHANGE_COLUMN_REMOVED, col.getTableName(), col.getColumnName(), col.getOldType(), null, SchemaChangeEvent.RISK_HIGH);
        }
        // 修改字段事件（类型变更为中风险，注释变更为低风险）
        for (SchemaDiffVO.ColumnChange col : diff.getModifiedColumns()) {
            if (!equalsNullSafe(col.getOldType(), col.getNewType())) {
                saveEvent(datasourceId, oldId, newId, SchemaChangeEvent.CHANGE_COLUMN_TYPE_CHANGED, col.getTableName(), col.getColumnName(), col.getOldType(), col.getNewType(), SchemaChangeEvent.RISK_MEDIUM);
            }
            if (!equalsNullSafe(col.getOldComment(), col.getNewComment())) {
                saveEvent(datasourceId, oldId, newId, SchemaChangeEvent.CHANGE_COMMENT_CHANGED, col.getTableName(), col.getColumnName(), col.getOldComment(), col.getNewComment(), SchemaChangeEvent.RISK_LOW);
            }
        }
    }

    /**
     * 保存单条变更事件到数据库。
     *
     * @param datasourceId  数据源ID
     * @param oldSnapshotId 旧快照ID
     * @param newSnapshotId 新快照ID
     * @param changeType    变更类型
     * @param tableName     表名
     * @param columnName    字段名（可为 null）
     * @param oldValue      旧值
     * @param newValue      新值
     * @param riskLevel     风险等级
     */
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

    /**
     * 根据快照ID查询所有表元数据。
     *
     * @param snapshotId 快照ID
     * @return 表元数据列表
     */
    private List<DbTableMeta> getTablesBySnapshot(Long snapshotId) {
        return tableMetaMapper.selectList(
                new LambdaQueryWrapper<DbTableMeta>().eq(DbTableMeta::getSnapshotId, snapshotId));
    }

    /**
     * 根据快照ID和表名查询字段元数据。
     *
     * @param snapshotId 快照ID
     * @param tableName  表名
     * @return 字段元数据列表
     */
    private List<DbColumnMeta> getColumnsBySnapshotAndTable(Long snapshotId, String tableName) {
        return columnMetaMapper.selectList(
                new LambdaQueryWrapper<DbColumnMeta>()
                        .eq(DbColumnMeta::getSnapshotId, snapshotId)
                        .eq(DbColumnMeta::getTableName, tableName));
    }

    /**
     * 空安全的字符串比较。
     *
     * @param a 字符串a
     * @param b 字符串b
     * @return 两者相等返回 true
     */
    private boolean equalsNullSafe(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
