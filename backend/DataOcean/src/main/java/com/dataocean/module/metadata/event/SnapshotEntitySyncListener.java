package com.dataocean.module.metadata.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.entity.TableRelation;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import com.dataocean.module.metadata.entity.MetadataChangeEvent;
import com.dataocean.module.metadata.service.MetadataChangeEventService;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import com.dataocean.module.versioning.event.SnapshotPublishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 快照发布时同步写入实体-关系图谱。
 * <p>
 * 监听 {@link SnapshotPublishedEvent}，将快照中的表/列元数据
 * 写入 metadata_entity 和 metadata_relationship 表，建立统一实体图谱。
 * </p>
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

    /**
     * 快照发布后，将表/列元数据写入实体-关系图谱。
     * <p>
     * 流程：
     * 1. 清理该数据源旧的实体和关系数据
     * 2. 加载快照中所有表和列
     * 3. 为每张表创建 TABLE 类型实体
     * 4. 为每列创建 COLUMN 类型实体
     * 5. 建立 CONTAINS（数据源→表）、HAS_PART（表→列）关系
     * 6. 迁移 TableRelation 中的外键关系到 metadata_relationship
     * </p>
     */
    @Async
    @EventListener
    public void onSnapshotPublished(SnapshotPublishedEvent event) {
        Long snapshotId = event.getSnapshotId();
        Long datasourceId = event.getDatasourceId();

        try {
            // 获取数据源信息（用于 FQN 构建）
            Datasource datasource = datasourceMapper.selectById(datasourceId);
            if (datasource == null) {
                log.warn("数据源不存在，跳过实体同步 datasourceId={}", datasourceId);
                return;
            }
            String dsName = datasource.getName();
            String dbName = datasource.getDatabaseName();

            // 1. 清理旧实体和关系
            cleanupOldEntities(datasourceId);

            // 2. 创建数据源级实体
            MetadataEntity dsEntity = createDatasourceEntity(datasourceId, dsName, dbName);
            Long dsEntityId = dsEntity.getId();

            // 3. 加载快照中所有表
            List<DbTableMeta> tables = tableMetaMapper.selectList(
                    new LambdaQueryWrapper<DbTableMeta>()
                            .eq(DbTableMeta::getDatasourceId, datasourceId)
                            .eq(DbTableMeta::getSnapshotId, snapshotId));

            int tableCount = 0;
            int columnCount = 0;

            for (DbTableMeta table : tables) {
                // 4. 创建 TABLE 实体
                String tableFqn = MetadataEntity.fqnTable(dsName, dbName, table.getTableName());
                MetadataEntity tableEntity = new MetadataEntity();
                tableEntity.setEntityType(MetadataEntity.TYPE_TABLE);
                tableEntity.setEntityUuid(UUID.randomUUID().toString());
                tableEntity.setFqn(tableFqn);
                tableEntity.setName(table.getTableName());
                tableEntity.setDisplayName(table.getTableComment());
                tableEntity.setDescription(table.getTableComment());
                tableEntity.setEntityMetadata("{\"datasource_id\":" + datasourceId
                        + ",\"snapshot_id\":" + snapshotId
                        + ",\"table_type\":\"" + table.getTableType()
                        + "\",\"governance_status\":\"" + table.getGovernanceStatus() + "\"}");
                tableEntity = entityService.upsert(tableEntity);
                tableCount++;

                // 记录元数据变更事件
                changeEventService.recordEvent(
                        MetadataChangeEvent.EVENT_PUBLISH,
                        MetadataEntity.TYPE_TABLE,
                        tableEntity.getId(),
                        tableFqn,
                        "{\"snapshot_id\":" + snapshotId + ",\"governance_status\":\"" + table.getGovernanceStatus() + "\"}",
                        null);

                // 5. 建立 CONTAINS 关系（数据源 → 表）
                MetadataRelationship contains = new MetadataRelationship();
                contains.setSourceId(dsEntityId);
                contains.setSourceType(MetadataEntity.TYPE_DATASOURCE);
                contains.setTargetId(tableEntity.getId());
                contains.setTargetType(MetadataEntity.TYPE_TABLE);
                contains.setRelationType(MetadataRelationship.TYPE_CONTAINS);
                relationshipService.upsert(contains);

                // 6. 加载该表的所有列
                List<DbColumnMeta> columns = columnMetaMapper.selectList(
                        new LambdaQueryWrapper<DbColumnMeta>()
                                .eq(DbColumnMeta::getDatasourceId, datasourceId)
                                .eq(DbColumnMeta::getSnapshotId, snapshotId)
                                .eq(DbColumnMeta::getTableName, table.getTableName()));

                for (DbColumnMeta column : columns) {
                    // 7. 创建 COLUMN 实体
                    String colFqn = MetadataEntity.fqnColumn(dsName, dbName, table.getTableName(), column.getColumnName());
                    MetadataEntity colEntity = new MetadataEntity();
                    colEntity.setEntityType(MetadataEntity.TYPE_COLUMN);
                    colEntity.setEntityUuid(UUID.randomUUID().toString());
                    colEntity.setFqn(colFqn);
                    colEntity.setName(column.getColumnName());
                    colEntity.setDisplayName(column.getColumnComment());
                    colEntity.setDescription(column.getColumnComment());
                    // 标签推断：基于列名和注释匹配 PII/业务域模式
                    String tagCandidates = inferTagCandidates(column.getColumnName(), column.getColumnComment());
                    colEntity.setEntityMetadata("{\"datasource_id\":" + datasourceId
                            + ",\"snapshot_id\":" + snapshotId
                            + ",\"data_type\":\"" + column.getDataType()
                            + "\",\"is_primary_key\":" + (column.getIsPrimaryKey() != null && column.getIsPrimaryKey() == 1)
                            + ",\"governance_status\":\"" + column.getGovernanceStatus() + "\""
                            + ",\"tag_candidates\":" + tagCandidates + "}");
                    colEntity = entityService.upsert(colEntity);
                    columnCount++;

                    // 8. 建立 HAS_PART 关系（表 → 列）
                    MetadataRelationship hasPart = new MetadataRelationship();
                    hasPart.setSourceId(tableEntity.getId());
                    hasPart.setSourceType(MetadataEntity.TYPE_TABLE);
                    hasPart.setTargetId(colEntity.getId());
                    hasPart.setTargetType(MetadataEntity.TYPE_COLUMN);
                    hasPart.setRelationType(MetadataRelationship.TYPE_HAS_PART);
                    relationshipService.upsert(hasPart);
                }
            }

            // 9. 迁移 TableRelation 中的外键关系到 metadata_relationship
            List<TableRelation> tableRelations = tableRelationMapper.selectList(
                    new LambdaQueryWrapper<TableRelation>()
                            .eq(TableRelation::getDatasourceId, datasourceId)
                            .eq(TableRelation::getSnapshotId, snapshotId));

            int fkCount = 0;
            for (TableRelation tr : tableRelations) {
                // 查找源列实体
                String sourceColFqn = MetadataEntity.fqnColumn(dsName, dbName, tr.getSourceTable(), tr.getSourceColumn());
                MetadataEntity sourceCol = entityService.getByFqn(sourceColFqn);
                // 查找目标列实体
                String targetColFqn = MetadataEntity.fqnColumn(dsName, dbName, tr.getTargetTable(), tr.getTargetColumn());
                MetadataEntity targetCol = entityService.getByFqn(targetColFqn);

                if (sourceCol != null && targetCol != null) {
                    MetadataRelationship fkRel = new MetadataRelationship();
                    fkRel.setSourceId(sourceCol.getId());
                    fkRel.setSourceType(MetadataEntity.TYPE_COLUMN);
                    fkRel.setTargetId(targetCol.getId());
                    fkRel.setTargetType(MetadataEntity.TYPE_COLUMN);
                    fkRel.setRelationType(MetadataRelationship.TYPE_FOREIGN_KEY);
                    fkRel.setRelationMetadata("{\"confidence\":" + tr.getConfidence()
                            + ",\"original_type\":\"" + tr.getRelationType() + "\"}");
                    relationshipService.upsert(fkRel);
                    fkCount++;
                }
            }

            log.info("快照实体同步完成 datasourceId={} tables={} columns={} fkRelations={}",
                    datasourceId, tableCount, columnCount, fkCount);

        } catch (Exception e) {
            log.error("快照实体同步失败 datasourceId={} snapshotId={}: {}", datasourceId, snapshotId, e.getMessage(), e);
        }
    }

    /**
     * 清理该数据源旧的实体和关系数据
     */
    private void cleanupOldEntities(Long datasourceId) {
        // 先删除关系
        relationshipService.deleteBySourceDatasource(datasourceId);
        // 再删除实体
        List<MetadataEntity> oldEntities = entityService.getByDatasourceId(datasourceId);
        if (!oldEntities.isEmpty()) {
            entityService.removeByIds(oldEntities.stream().map(MetadataEntity::getId).toList());
        }
        log.debug("已清理数据源 {} 的旧实体和关系", datasourceId);
    }

    /**
     * 创建或更新数据源级实体
     */
    private MetadataEntity createDatasourceEntity(Long datasourceId, String dsName, String dbName) {
        MetadataEntity dsEntity = new MetadataEntity();
        dsEntity.setEntityType(MetadataEntity.TYPE_DATASOURCE);
        dsEntity.setEntityUuid(UUID.randomUUID().toString());
        dsEntity.setFqn(MetadataEntity.fqnDatasource(dsName));
        dsEntity.setName(dsName);
        dsEntity.setDisplayName(dsName);
        dsEntity.setEntityMetadata("{\"datasource_id\":" + datasourceId
                + ",\"database_name\":\"" + dbName + "\"}");
        return entityService.upsert(dsEntity);
    }

    /**
     * 标签推断：基于列名和注释匹配 PII/业务域模式
     * <p>
     * 与 Python auto_tagger.py 的模式对齐，返回 JSON 数组字符串。
     * 标签不自动生效，存储为 tag_candidates 供管理员确认。
     * </p>
     */
    private String inferTagCandidates(String columnName, String columnComment) {
        String text = (columnName + " " + (columnComment != null ? columnComment : "")).toLowerCase();
        java.util.List<String> candidates = new java.util.ArrayList<>();

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

        if (candidates.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(candidates.get(i)).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private boolean matches(String text, String... patterns) {
        for (String p : patterns) {
            if (text.matches(".*\\b" + p + "\\b.*")) return true;
        }
        return false;
    }
}
