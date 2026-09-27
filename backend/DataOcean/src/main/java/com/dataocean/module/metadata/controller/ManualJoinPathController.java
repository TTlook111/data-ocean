package com.dataocean.module.metadata.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.entity.TableRelation;
import com.dataocean.module.metadata.entity.dto.ManualJoinPathRequest;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import com.dataocean.module.permission.s1.annotation.IamS1Resource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicitly confirmed manual Join facts, separate from ETL/table/column lineage. */
@RestController
@RequestMapping("/api/admin/catalog/datasources/{datasourceId}/join-paths")
@RequiredArgsConstructor
public class ManualJoinPathController {
    private final TableRelationMapper relationMapper;
    private final MetadataSnapshotMapper snapshotMapper;
    private final DbTableMetaMapper tableMapper;
    private final DbColumnMetaMapper columnMapper;

    @GetMapping
    @IamS1Resource(function = "lineage:view", resourceType = IamS1ResourceType.DATASOURCE,
            resourceIds = "#datasourceId")
    public Result<List<TableRelation>> list(@PathVariable Long datasourceId,
                                            @RequestParam Long snapshotId) {
        requirePublishedSnapshot(datasourceId, snapshotId);
        return Result.success(relationMapper.selectList(new LambdaQueryWrapper<TableRelation>()
                .eq(TableRelation::getDatasourceId, datasourceId)
                .eq(TableRelation::getSnapshotId, snapshotId)
                .orderByAsc(TableRelation::getId)));
    }

    @GetMapping("/options")
    @IamS1Resource(function = "lineage:view", resourceType = IamS1ResourceType.DATASOURCE,
            resourceIds = "#datasourceId")
    public Result<Map<String, Object>> options(@PathVariable Long datasourceId,
                                               @RequestParam Long snapshotId) {
        requirePublishedSnapshot(datasourceId, snapshotId);
        List<DbTableMeta> tables = tableMapper.selectList(new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getDatasourceId, datasourceId)
                .eq(DbTableMeta::getSnapshotId, snapshotId)
                .orderByAsc(DbTableMeta::getTableName));
        List<Long> tableIds = tables.stream().map(DbTableMeta::getId).toList();
        List<DbColumnMeta> columns = tableIds.isEmpty() ? List.of()
                : columnMapper.selectList(new LambdaQueryWrapper<DbColumnMeta>()
                        .eq(DbColumnMeta::getDatasourceId, datasourceId)
                        .eq(DbColumnMeta::getSnapshotId, snapshotId)
                        .in(DbColumnMeta::getTableMetaId, tableIds)
                        .orderByAsc(DbColumnMeta::getOrdinalPosition));
        Map<Long, List<Map<String, Object>>> columnsByTable = new LinkedHashMap<>();
        for (DbColumnMeta column : columns) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", column.getId());
            value.put("columnName", column.getColumnName());
            value.put("dataType", column.getDataType());
            value.put("governanceStatus", column.getGovernanceStatus());
            columnsByTable.computeIfAbsent(column.getTableMetaId(), ignored -> new ArrayList<>()).add(value);
        }
        List<Map<String, Object>> options = new ArrayList<>();
        for (DbTableMeta table : tables) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", table.getId());
            value.put("tableName", table.getTableName());
            value.put("columns", columnsByTable.getOrDefault(table.getId(), List.of()));
            options.add(value);
        }
        return Result.success(Map.of("snapshotId", snapshotId, "tables", options));
    }

    @PostMapping
    @IamS1Resource(function = "lineage:manage", resourceType = IamS1ResourceType.DATASOURCE,
            resourceIds = "#datasourceId")
    public Result<TableRelation> create(@PathVariable Long datasourceId,
                                        @Valid @RequestBody ManualJoinPathRequest request) {
        requirePublishedSnapshot(datasourceId, request.getSnapshotId());
        DbTableMeta sourceTable = requireTable(datasourceId, request.getSnapshotId(), request.getSourceTable());
        DbTableMeta targetTable = requireTable(datasourceId, request.getSnapshotId(), request.getTargetTable());
        requireColumn(datasourceId, request.getSnapshotId(), sourceTable, request.getSourceColumn());
        requireColumn(datasourceId, request.getSnapshotId(), targetTable, request.getTargetColumn());

        List<TableRelation> existing = relationMapper.selectList(new LambdaQueryWrapper<TableRelation>()
                .eq(TableRelation::getDatasourceId, datasourceId)
                .eq(TableRelation::getSnapshotId, request.getSnapshotId())
                .eq(TableRelation::getSourceTable, request.getSourceTable())
                .eq(TableRelation::getSourceColumn, request.getSourceColumn())
                .eq(TableRelation::getTargetTable, request.getTargetTable())
                .eq(TableRelation::getTargetColumn, request.getTargetColumn()));
        if (existing != null && !existing.isEmpty()) {
            TableRelation exact = existing.stream()
                    .filter(item -> TableRelation.TYPE_MANUAL.equals(item.getRelationType()))
                    .findFirst().orElse(null);
            if (exact != null && "CONFIRMED".equals(exact.getReviewStatus())) return Result.success(exact);
            throw new BusinessException(409, "该快照已有相同关系事实，请审核现有记录");
        }

        TableRelation relation = new TableRelation();
        relation.setDatasourceId(datasourceId);
        relation.setSnapshotId(request.getSnapshotId());
        relation.setSourceTable(request.getSourceTable());
        relation.setSourceColumn(request.getSourceColumn());
        relation.setTargetTable(request.getTargetTable());
        relation.setTargetColumn(request.getTargetColumn());
        relation.setRelationType(TableRelation.TYPE_MANUAL);
        relation.setConfidence(BigDecimal.ONE);
        relation.setReviewStatus("CONFIRMED");
        relation.setReviewedBy(UserContext.currentUserId());
        relation.setReviewedAt(LocalDateTime.now());
        relationMapper.insert(relation);
        return Result.success("已明确确认人工 Join Path", relation);
    }

    @DeleteMapping("/{relationId}")
    @IamS1Resource(function = "lineage:manage", resourceType = IamS1ResourceType.DATASOURCE,
            resourceIds = "#datasourceId")
    public Result<Void> delete(@PathVariable Long datasourceId, @PathVariable Long relationId) {
        TableRelation relation = relationMapper.selectById(relationId);
        if (relation == null || !datasourceId.equals(relation.getDatasourceId())
                || !TableRelation.TYPE_MANUAL.equals(relation.getRelationType())) {
            throw new BusinessException("人工 Join Path 不存在或不属于该数据源");
        }
        relationMapper.deleteById(relationId);
        return Result.success("人工 Join Path 已删除", null);
    }

    private void requirePublishedSnapshot(Long datasourceId, Long snapshotId) {
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null || !datasourceId.equals(snapshot.getDatasourceId())
                || !MetadataSnapshot.STATUS_PUBLISHED.equals(snapshot.getStatus())) {
            throw new BusinessException("Join Path 只能绑定该数据源当前已发布快照");
        }
    }

    private DbTableMeta requireTable(Long datasourceId, Long snapshotId, String tableName) {
        DbTableMeta table = tableMapper.selectOne(new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getDatasourceId, datasourceId)
                .eq(DbTableMeta::getSnapshotId, snapshotId)
                .eq(DbTableMeta::getTableName, tableName));
        if (table == null) throw new BusinessException("Join Path 引用了本快照不存在的表：" + tableName);
        return table;
    }

    private void requireColumn(Long datasourceId, Long snapshotId, DbTableMeta table, String columnName) {
        Long count = columnMapper.selectCount(new LambdaQueryWrapper<DbColumnMeta>()
                .eq(DbColumnMeta::getDatasourceId, datasourceId)
                .eq(DbColumnMeta::getSnapshotId, snapshotId)
                .eq(DbColumnMeta::getTableMetaId, table.getId())
                .eq(DbColumnMeta::getColumnName, columnName));
        if (count == null || count == 0) {
            throw new BusinessException("Join Path 引用了本快照不存在的字段：" + table.getTableName() + "." + columnName);
        }
    }
}
