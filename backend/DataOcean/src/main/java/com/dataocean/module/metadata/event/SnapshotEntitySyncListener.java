package com.dataocean.module.metadata.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataocean.module.datasource.entity.po.Datasource;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.entity.po.MetadataEntity;
import com.dataocean.module.metadata.entity.po.MetadataRelationship;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.entity.po.TableRelation;
import com.dataocean.module.metadata.entity.po.MetadataChangeEvent;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import com.dataocean.module.metadata.service.MetadataChangeEventService;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import com.dataocean.module.versioning.event.SnapshotPublishedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 快照发布时同步写入实体-关系图谱。
 *
 * <p>关键边界：</p>
 * <ul>
 *   <li>只有发布事务提交后才同步，避免读到尚未提交的 PUBLISHED 状态；</li>
 *   <li>表/字段以 FQN 作为稳定身份做 upsert，仍然存在的同一逻辑对象保留原实体 ID，
 *       人工术语映射和标签不会因为重新发布而无差别断开；</li>
 *   <li>只有新快照中确实消失的实体才清理，并把引用它的术语映射/人工标签标记为 UNBOUND；</li>
 *   <li>结构关系（CONTAINS/HAS_PART/FOREIGN_KEY）随快照重建，人工作业关系保留；</li>
 *   <li>同步成功或失败写回快照，目录页不能把失败伪装成完整成功。</li>
 * </ul>
 *
 * @author dataocean
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotEntitySyncListener {

    private final MetadataEntityService entityService;
    private final MetadataRelationshipService relationshipService;
    private final MetadataChangeEventService changeEventService;
    private final DatasourceMapper datasourceMapper;
    private final DbTableMetaMapper tableMetaMapper;
    private final DbColumnMetaMapper columnMetaMapper;
    private final TableRelationMapper tableRelationMapper;
    private final MetadataSnapshotMapper snapshotMapper;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    /** 同一数据源的发布同步串行执行，避免旧快照同步覆盖新快照结果。 */
    private final Map<Long, Object> datasourceLocks = new ConcurrentHashMap<>();

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSnapshotPublished(SnapshotPublishedEvent event) {
        Long snapshotId = event.getSnapshotId();
        Long datasourceId = event.getDatasourceId();
        Object lock = datasourceLocks.computeIfAbsent(datasourceId, key -> new Object());
        synchronized (lock) {
            try {
                MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
                if (snapshot == null) {
                    log.warn("快照不存在，跳过实体同步 snapshotId={}", snapshotId);
                    return;
                }
                if (!MetadataSnapshot.STATUS_PUBLISHED.equals(snapshot.getStatus())) {
                    // 旧发布事件在新发布之后到达时，不再用旧快照覆盖当前目录。
                    log.info("快照不再是当前发布版本，跳过实体同步 snapshotId={} status={}",
                            snapshotId, snapshot.getStatus());
                    return;
                }
                updateSyncStatus(snapshotId, MetadataSnapshot.ENTITY_SYNC_SYNCING, null);
                TransactionTemplate template = new TransactionTemplate(transactionManager);
                template.executeWithoutResult(status -> syncSnapshot(snapshotId, datasourceId));
                updateSyncStatus(snapshotId, MetadataSnapshot.ENTITY_SYNC_SYNCED, null);
                log.info("快照实体同步完成 datasourceId={} snapshotId={}", datasourceId, snapshotId);
            } catch (Exception exception) {
                String message = truncate(exception.getMessage());
                updateSyncStatus(snapshotId, MetadataSnapshot.ENTITY_SYNC_FAILED, message);
                log.error("快照实体同步失败 datasourceId={} snapshotId={}: {}",
                        datasourceId, snapshotId, exception.getMessage(), exception);
            }
        }
    }

    private void syncSnapshot(Long snapshotId, Long datasourceId) {
        Datasource datasource = datasourceMapper.selectById(datasourceId);
        if (datasource == null) {
            throw new IllegalStateException("数据源不存在，无法同步目录");
        }
        String dsName = datasource.getName();
        String dbName = datasource.getDatabaseName() == null ? "" : datasource.getDatabaseName();

        List<DbTableMeta> tables = tableMetaMapper.selectList(
                new LambdaQueryWrapper<DbTableMeta>()
                        .eq(DbTableMeta::getDatasourceId, datasourceId)
                        .eq(DbTableMeta::getSnapshotId, snapshotId));
        List<DbColumnMeta> allColumns = columnMetaMapper.selectList(
                new LambdaQueryWrapper<DbColumnMeta>()
                        .eq(DbColumnMeta::getDatasourceId, datasourceId)
                        .eq(DbColumnMeta::getSnapshotId, snapshotId));
        Map<String, List<DbColumnMeta>> columnsByTable = new HashMap<>();
        for (DbColumnMeta column : allColumns) {
            columnsByTable.computeIfAbsent(column.getTableName(), key -> new ArrayList<>()).add(column);
        }

        // 旧实体元数据里可能有人工/标签流程维护的字段（如 pending_mask）。
        // 采集同步只重建结构字段，不能把这些待确认状态一并清空。
        Map<String, MetadataEntity> previousByFqn = new HashMap<>();
        for (MetadataEntity previous : entityService.getByDatasourceId(datasourceId)) {
            if (previous.getFqn() != null) {
                previousByFqn.put(previous.getFqn().toLowerCase(), previous);
            }
        }

        Set<String> retainedFqns = new HashSet<>();
        Map<String, MetadataEntity> entityByFqn = new LinkedHashMap<>();

        // 数据源实体同样按 FQN 稳定复用，不随每次发布更换 ID。
        String dsFqn = MetadataEntity.fqnDatasource(dsName);
        Map<String, Object> dsMetadata = new LinkedHashMap<>();
        dsMetadata.put("datasource_id", datasourceId);
        dsMetadata.put("database_name", datasource.getDatabaseName());
        dsMetadata.put("snapshot_id", snapshotId);
        MetadataEntity dsEntity = upsertEntity(MetadataEntity.TYPE_DATASOURCE, dsFqn, dsName, dsName,
                null, dsMetadata);
        retainedFqns.add(dsFqn.toLowerCase());
        entityByFqn.put(dsFqn.toLowerCase(), dsEntity);

        int tableCount = 0;
        int columnCount = 0;
        for (DbTableMeta table : tables) {
            String tableFqn = MetadataEntity.fqnTable(dsName, dbName, table.getTableName());
            Map<String, Object> tableMetadata = new LinkedHashMap<>();
            tableMetadata.put("datasource_id", datasourceId);
            tableMetadata.put("snapshot_id", snapshotId);
            tableMetadata.put("database_name", datasource.getDatabaseName());
            tableMetadata.put("table_name", table.getTableName());
            tableMetadata.put("table_meta_id", table.getId());
            tableMetadata.put("table_type", table.getTableType());
            tableMetadata.put("governance_status", table.getGovernanceStatus());

            MetadataEntity tableEntity = upsertEntity(MetadataEntity.TYPE_TABLE, tableFqn,
                    table.getTableName(), table.getTableComment(), table.getTableComment(), tableMetadata);
            retainedFqns.add(tableFqn.toLowerCase());
            entityByFqn.put(tableFqn.toLowerCase(), tableEntity);
            tableCount++;

            Map<String, Object> changeData = new LinkedHashMap<>();
            changeData.put("snapshot_id", snapshotId);
            changeData.put("governance_status", table.getGovernanceStatus());
            changeEventService.recordEvent(
                    MetadataChangeEvent.EVENT_PUBLISH,
                    MetadataEntity.TYPE_TABLE,
                    tableEntity.getId(),
                    tableFqn,
                    metadataJson(changeData),
                    null);

            List<DbColumnMeta> columns = columnsByTable.getOrDefault(table.getTableName(), List.of());
            for (DbColumnMeta column : columns) {
                String columnFqn = MetadataEntity.fqnColumn(dsName, dbName,
                        table.getTableName(), column.getColumnName());
                Map<String, Object> columnMetadata = new LinkedHashMap<>();
                columnMetadata.put("datasource_id", datasourceId);
                columnMetadata.put("snapshot_id", snapshotId);
                columnMetadata.put("database_name", datasource.getDatabaseName());
                columnMetadata.put("table_name", table.getTableName());
                columnMetadata.put("column_name", column.getColumnName());
                columnMetadata.put("column_meta_id", column.getId());
                columnMetadata.put("data_type", column.getDataType());
                columnMetadata.put("is_nullable", column.getIsNullable() != null && column.getIsNullable() == 1);
                columnMetadata.put("is_primary_key", column.getIsPrimaryKey() != null && column.getIsPrimaryKey() == 1);
                columnMetadata.put("governance_status", column.getGovernanceStatus());
                columnMetadata.put("tag_candidates",
                        inferTagCandidates(column.getColumnName(), column.getColumnComment()));
                MetadataEntity previous = previousByFqn.get(columnFqn.toLowerCase());
                if (previous != null) {
                    Map<String, Object> previousMetadata = parseMetadata(previous.getEntityMetadata());
                    if (previousMetadata.get("pending_mask") != null) {
                        columnMetadata.put("pending_mask", previousMetadata.get("pending_mask"));
                    }
                }
                MetadataEntity columnEntity = upsertEntity(MetadataEntity.TYPE_COLUMN, columnFqn,
                        column.getColumnName(), column.getColumnComment(), column.getColumnComment(),
                        columnMetadata);
                retainedFqns.add(columnFqn.toLowerCase());
                entityByFqn.put(columnFqn.toLowerCase(), columnEntity);
                columnCount++;
            }
        }

        // 只清理新快照中确实不存在的实体；仍然存在的实体保留 ID 和人工关联。
        relationshipService.cleanupStaleSnapshotEntities(datasourceId, retainedFqns, snapshotId);

        Set<String> currentEntityKeys = new HashSet<>();
        for (MetadataEntity entity : entityByFqn.values()) {
            String key = MetadataEntity.endpointKey(entity.getEntityType(), entity.getId());
            if (key != null) currentEntityKeys.add(key);
        }
        // 外键关系随快照重建：先清掉当前实体上的旧 FOREIGN_KEY 边，再按新快照写入。
        relationshipService.deleteRelationsForEntityKeys(currentEntityKeys,
                Set.of(MetadataRelationship.TYPE_FOREIGN_KEY));

        for (DbTableMeta table : tables) {
            MetadataEntity tableEntity = entityByFqn.get(
                    MetadataEntity.fqnTable(dsName, dbName, table.getTableName()).toLowerCase());
            if (tableEntity == null) continue;
            upsertRelation(dsEntity, tableEntity, MetadataRelationship.TYPE_CONTAINS);
            for (DbColumnMeta column : columnsByTable.getOrDefault(table.getTableName(), List.of())) {
                MetadataEntity columnEntity = entityByFqn.get(MetadataEntity.fqnColumn(
                        dsName, dbName, table.getTableName(), column.getColumnName()).toLowerCase());
                if (columnEntity != null) {
                    upsertRelation(tableEntity, columnEntity, MetadataRelationship.TYPE_HAS_PART);
                }
            }
        }

        int fkCount = 0;
        List<TableRelation> tableRelations = tableRelationMapper.selectList(
                new LambdaQueryWrapper<TableRelation>()
                        .eq(TableRelation::getDatasourceId, datasourceId)
                        .eq(TableRelation::getSnapshotId, snapshotId));
        for (TableRelation tr : tableRelations) {
            MetadataEntity sourceCol = entityByFqn.get(MetadataEntity.fqnColumn(
                    dsName, dbName, tr.getSourceTable(), tr.getSourceColumn()).toLowerCase());
            MetadataEntity targetCol = entityByFqn.get(MetadataEntity.fqnColumn(
                    dsName, dbName, tr.getTargetTable(), tr.getTargetColumn()).toLowerCase());
            if (sourceCol == null || targetCol == null) {
                continue;
            }
            MetadataRelationship fkRel = new MetadataRelationship();
            fkRel.setSourceId(sourceCol.getId());
            fkRel.setSourceType(MetadataEntity.TYPE_COLUMN);
            fkRel.setTargetId(targetCol.getId());
            fkRel.setTargetType(MetadataEntity.TYPE_COLUMN);
            fkRel.setRelationType(MetadataRelationship.TYPE_FOREIGN_KEY);
            Map<String, Object> fkMetadata = new LinkedHashMap<>();
            fkMetadata.put("confidence", tr.getConfidence());
            fkMetadata.put("original_type", tr.getRelationType());
            fkRel.setRelationMetadata(metadataJson(fkMetadata));
            relationshipService.upsert(fkRel);
            fkCount++;
        }

        // 人工/ETL 血缘以 FQN 为持久身份；端点缺失时保持 UNBOUND，不虚构新目标。
        relationshipService.rebindConfirmedLineageForSnapshot(datasourceId, snapshotId);

        log.info("快照实体同步明细 datasourceId={} snapshotId={} tables={} columns={} fkRelations={}",
                datasourceId, snapshotId, tableCount, columnCount, fkCount);
    }

    private MetadataEntity upsertEntity(String entityType, String fqn, String name,
                                        String displayName, String description,
                                        Map<String, Object> metadata) {
        MetadataEntity entity = new MetadataEntity();
        entity.setEntityType(entityType);
        entity.setEntityUuid(UUID.randomUUID().toString());
        entity.setFqn(fqn);
        entity.setName(name);
        entity.setDisplayName(displayName);
        entity.setDescription(description);
        entity.setEntityMetadata(metadataJson(metadata));
        return entityService.upsert(entity);
    }

    private void upsertRelation(MetadataEntity source, MetadataEntity target, String relationType) {
        MetadataRelationship relation = new MetadataRelationship();
        relation.setSourceId(source.getId());
        relation.setSourceType(source.getEntityType());
        relation.setTargetId(target.getId());
        relation.setTargetType(target.getEntityType());
        relation.setRelationType(relationType);
        relationshipService.upsert(relation);
    }

    private String metadataJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception exception) {
            throw new IllegalStateException("目录元数据序列化失败", exception);
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private void updateSyncStatus(Long snapshotId, String status, String error) {
        try {
            LambdaUpdateWrapper<MetadataSnapshot> update = new LambdaUpdateWrapper<MetadataSnapshot>()
                    .eq(MetadataSnapshot::getId, snapshotId)
                    .set(MetadataSnapshot::getEntitySyncStatus, status)
                    .set(MetadataSnapshot::getEntitySyncError, error)
                    .set(MetadataSnapshot::getEntitySyncedAt,
                            MetadataSnapshot.ENTITY_SYNC_SYNCED.equals(status) ? LocalDateTime.now() : null);
            snapshotMapper.update(null, update);
        } catch (Exception exception) {
            // 状态写回失败不能吞掉主流程异常日志；下一次读取会显示“未记录”。
            log.error("写回目录同步状态失败 snapshotId={} status={}: {}",
                    snapshotId, status, exception.getMessage());
        }
    }

    private String truncate(String message) {
        if (message == null) return null;
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    /**
     * 标签推断：基于列名和注释匹配 PII/业务域模式。
     *
     * <p>与 Python auto_tagger.py 的模式对齐，返回候选列表。
     * 标签不自动生效，存储为 tag_candidates 供管理员确认。</p>
     */
    private List<String> inferTagCandidates(String columnName, String columnComment) {
        String text = (columnName + " " + (columnComment != null ? columnComment : "")).toLowerCase();
        List<String> candidates = new ArrayList<>();

        // PII 标签
        if (matches(text, "id_card", "身份证", "identity", "sfz")) candidates.add("PII.身份证号");
        if (matches(text, "phone", "手机", "电话", "mobile", "tel")) candidates.add("PII.手机号");
        if (matches(text, "real.?name", "user.?name", "customer.?name", "姓名")) candidates.add("PII.姓名");
        if (matches(text, "email", "邮箱", "mail")) candidates.add("PII.邮箱");
        if (matches(text, "bank.?card", "银行卡")) candidates.add("PII.银行卡号");
        if (matches(text, "address", "地址", "住址")) candidates.add("PII.地址");

        // 业务域标签
        if (matches(text, "amount", "金额", "price", "cost", "revenue", "income", "balance")) candidates.add("业务域.财务");
        if (matches(text, "order", "订单", "sale", "sell", "customer", "客户", "product", "商品")) candidates.add("业务域.销售");
        if (matches(text, "employee", "员工", "salary", "工资", "dept", "部门", "position", "岗位")) candidates.add("业务域.人力");
        if (matches(text, "supplier", "供应商", "inventory", "库存", "warehouse", "物流")) candidates.add("业务域.供应链");
        return candidates;
    }

    private boolean matches(String text, String... patterns) {
        for (String pattern : patterns) {
            if (pattern.contains(".?")) {
                if (java.util.regex.Pattern.compile(pattern).matcher(text).find()) return true;
            } else if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
