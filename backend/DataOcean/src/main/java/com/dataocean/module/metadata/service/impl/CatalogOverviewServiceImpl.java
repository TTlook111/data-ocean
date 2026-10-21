package com.dataocean.module.metadata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.entity.po.Datasource;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.fieldtag.entity.vo.FieldTagVO;
import com.dataocean.module.fieldtag.service.FieldTagService;
import com.dataocean.module.glossary.entity.po.GlossaryTerm;
import com.dataocean.module.glossary.mapper.GlossaryTermMapper;
import com.dataocean.module.glossary.service.GlossaryScopeService;
import com.dataocean.module.glossary.service.GlossaryTermWorkflowService;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.entity.po.MetadataEntity;
import com.dataocean.module.metadata.entity.po.MetadataRelationship;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.entity.vo.CatalogEntityOverviewVO;
import com.dataocean.module.metadata.entity.vo.CatalogEntityOverviewVO.ColumnInfo;
import com.dataocean.module.metadata.entity.vo.CatalogEntityOverviewVO.Counts;
import com.dataocean.module.metadata.entity.vo.CatalogEntityOverviewVO.DatasourceInfo;
import com.dataocean.module.metadata.entity.vo.CatalogEntityOverviewVO.FieldTagItem;
import com.dataocean.module.metadata.entity.vo.CatalogEntityOverviewVO.LineageItem;
import com.dataocean.module.metadata.entity.vo.CatalogEntityOverviewVO.RelationItem;
import com.dataocean.module.metadata.entity.vo.CatalogEntityOverviewVO.Section;
import com.dataocean.module.metadata.entity.vo.CatalogEntityOverviewVO.SnapshotInfo;
import com.dataocean.module.metadata.entity.vo.CatalogEntityOverviewVO.TableInfo;
import com.dataocean.module.metadata.entity.vo.CatalogEntityOverviewVO.TagItem;
import com.dataocean.module.metadata.entity.vo.CatalogEntityOverviewVO.TermItem;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.metadata.service.CatalogOverviewService;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import com.dataocean.module.metadata.service.SchemaSnapshotService;
import com.dataocean.module.metadata.support.RelationshipBindingStatus;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 数据对象详情聚合实现。
 *
 * <p>只读取现有术语、标签、治理和关系事实，不复制主记录、不新建审核流程。
 * 术语可见性沿用 GlossaryScope/S1 规则；字段治理标签按 db_column_meta 的当前快照归属读取。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogOverviewServiceImpl implements CatalogOverviewService {

    private static final String VIEW_FUNCTION = "metadata:view";
    private static final String LINEAGE_VIEW_FUNCTION = "lineage:view";
    private static final String GLOSSARY_VIEW_FUNCTION = "glossary:view";
    private static final String FIELD_VIEW_FUNCTION = "governance:field:view";

    private final MetadataEntityService entityService;
    private final MetadataRelationshipService relationshipService;
    private final MetadataRelationshipMapper relationshipMapper;
    private final SchemaSnapshotService snapshotService;
    private final DatasourceMapper datasourceMapper;
    private final DbTableMetaMapper tableMetaMapper;
    private final DbColumnMetaMapper columnMetaMapper;
    private final GlossaryTermMapper glossaryTermMapper;
    private final GlossaryScopeService glossaryScopeService;
    private final GlossaryTermWorkflowService glossaryTermWorkflowService;
    private final FieldTagService fieldTagService;
    private final IamS1AdminGuard adminGuard;
    private final IamS1CapabilityService capabilityService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public CatalogEntityOverviewVO overview(Long userId, Long entityId) {
        MetadataEntity entity = entityService.getById(entityId);
        if (entity == null) {
            throw new BusinessException(404, "对象不存在");
        }
        String entityType = entity.getEntityType();
        if (!MetadataEntity.TYPE_TABLE.equals(entityType) && !MetadataEntity.TYPE_COLUMN.equals(entityType)) {
            throw new BusinessException(400, "对象详情只支持表和字段");
        }
        Long datasourceId = entityService.getDatasourceIdByEntityId(entityId);
        if (datasourceId == null) {
            throw new BusinessException(409, "对象缺少数据源归属，无法读取详情");
        }
        Datasource datasource = datasourceMapper.selectById(datasourceId);
        MetadataSnapshot publishedSnapshot = snapshotService.getPublishedSnapshot(datasourceId);
        Map<String, Object> entityMetadata = parseMetadata(entity.getEntityMetadata());
        Long entitySnapshotId = asLong(entityMetadata.get("snapshot_id"));

        DatasourceInfo datasourceInfo = datasource == null ? null
                : new DatasourceInfo(datasource.getId(), datasource.getName(), datasource.getDatabaseName());

        // 详情只能有一个数据来源快照：优先使用实体记录的快照。若实体是历史数据、
        // 没有记录快照，则只能在发布目标中按完整归属核验后采用发布目标。
        MetadataSnapshot sourceSnapshot = null;
        boolean adoptedPublished = false;
        String sourceMessage = null;
        if (entitySnapshotId != null) {
            MetadataSnapshot candidate = snapshotService.getById(entitySnapshotId);
            if (candidate != null && Objects.equals(candidate.getDatasourceId(), datasourceId)) {
                sourceSnapshot = candidate;
            } else {
                sourceMessage = "对象记录的快照来源不存在或不属于当前数据源，结构与治理信息无法核验。";
            }
        } else if (publishedSnapshot != null) {
            sourceSnapshot = publishedSnapshot;
            adoptedPublished = true;
        } else {
            sourceMessage = "对象未记录来源快照，且当前数据源没有已发布快照，结构与治理信息无法核验。";
        }

        boolean sourceVerified = sourceSnapshot != null;
        boolean entityGraphTrusted = sourceSnapshot != null && entitySnapshotId != null
                && Objects.equals(sourceSnapshot.getId(), entitySnapshotId);
        boolean allowEntityMetadata = entityGraphTrusted;
        Long sourceSnapshotId = sourceSnapshot == null ? null : sourceSnapshot.getId();
        String tableName = tableNameOf(entity, entityMetadata);

        // 字段详情不能把字段自己的显示名/注释当成所属表信息。
        TableInfo tableInfo = MetadataEntity.TYPE_TABLE.equals(entityType)
                ? basicTableInfo(entity, tableName, entityMetadata, allowEntityMetadata)
                : new TableInfo(null, tableName, null, null, null, null);
        ColumnInfo columnInfo = null;
        Section<ColumnInfo> columnsSection;

        try {
            if (sourceSnapshotId == null) {
                columnsSection = Section.unavailable(sourceMessage == null
                        ? "无法核验对象详情的数据来源快照" : sourceMessage);
            } else if (MetadataEntity.TYPE_TABLE.equals(entityType)) {
                DbTableMeta tableMeta = findTableMeta(datasourceId, sourceSnapshotId, tableName,
                        entityMetadata, allowEntityMetadata);
                tableInfo = toTableInfo(entity, tableName, tableMeta, entityMetadata, allowEntityMetadata);
                columnsSection = loadTableColumns(datasourceId, sourceSnapshotId, tableName,
                        datasource, entityGraphTrusted);
            } else {
                DbTableMeta tableMeta = findTableMeta(datasourceId, sourceSnapshotId, tableName, null, false);
                if (tableMeta != null) {
                    tableInfo = toTableInfo(null, tableName, tableMeta, null, allowEntityMetadata);
                }
                columnInfo = loadColumnInfo(entity, datasourceId, sourceSnapshotId, tableName,
                        columnNameOf(entity, entityMetadata), entityMetadata, allowEntityMetadata);
                columnsSection = columnInfo == null
                        ? Section.unavailable("来源快照中找不到该字段的元数据，无法读取字段属性与治理标签。")
                        : Section.ok(List.of(columnInfo));
            }
        } catch (Exception exception) {
            // 单个区块读取失败不应拖垮整个对象详情。
            log.warn("读取对象来源快照属性失败 entityId={}: {}", entity.getId(), exception.getMessage());
            columnsSection = Section.error("来源快照属性读取失败：" + safeMessage(exception));
            columnInfo = null;
            if (!MetadataEntity.TYPE_TABLE.equals(entityType)) {
                tableInfo = new TableInfo(null, tableName, null, null, null, null);
            }
        }

        if (adoptedPublished) {
            // 历史目录没有记录来源快照：只有发布目标中确实存在对应表/字段时才采用发布目标。
            boolean verified = MetadataEntity.TYPE_TABLE.equals(entityType)
                    ? columnsSection.status().equals(Section.OK) && !columnsSection.items().isEmpty()
                    : columnInfo != null;
            if (verified) {
                sourceVerified = true;
                sourceMessage = "对象目录未记录来源快照；表/字段属性和字段治理标签按发布目标 v"
                        + sourceSnapshot.getSnapshotVersion()
                        + " 核验，目录标签、术语和关系区块因缺少来源快照不可用。";
            } else {
                String failureMessage = columnsSection.message();
                sourceSnapshot = null;
                sourceVerified = false;
                sourceSnapshotId = null;
                columnInfo = null;
                tableInfo = MetadataEntity.TYPE_TABLE.equals(entityType)
                        ? basicTableInfo(entity, tableName, null, false)
                        : new TableInfo(null, tableName, null, null, null, null);
                columnsSection = Section.ERROR.equals(columnsSection.status())
                        ? Section.error(failureMessage == null ? "发布目标属性读取失败" : failureMessage)
                        : Section.unavailable(failureMessage == null
                        ? "对象目录未记录来源快照，且发布目标中无法核验对应表/字段。" : failureMessage);
                sourceMessage = failureMessage == null
                        ? "对象目录未记录来源快照，且发布目标中无法核验对应表/字段。" : failureMessage;
            }
        }

        boolean sourceMatchesPublished = sourceSnapshot != null && publishedSnapshot != null
                && Objects.equals(sourceSnapshot.getId(), publishedSnapshot.getId());
        if (sourceSnapshot != null && !adoptedPublished) {
            if (publishedSnapshot == null) {
                sourceMessage = "当前数据源没有已发布快照；对象详情按来源快照 v"
                        + sourceSnapshot.getSnapshotVersion() + " 读取。";
            } else if (!sourceMatchesPublished) {
                sourceMessage = "对象详情按实际来源快照 v" + sourceSnapshot.getSnapshotVersion()
                        + " 读取；当前发布目标为 v" + publishedSnapshot.getSnapshotVersion()
                        + "，目录实体尚未与发布目标对齐。";
            }
        }

        Set<String> visibleViewKeys = entityService.getEntityKeysByDatasourceIds(
                visibleDatasourceIds(userId, VIEW_FUNCTION));
        List<Long> lineageDatasourceIds = visibleDatasourceIds(userId, LINEAGE_VIEW_FUNCTION);
        Set<String> visibleLineageKeys = entityService.getEntityKeysByDatasourceIds(lineageDatasourceIds);
        List<ColumnInfo> columns = columnsSection.status().equals(Section.OK)
                ? columnsSection.items() : List.of();
        Section<RelationItem> structureRelations;
        Section<TermItem> terms;
        Section<TagItem> tags;
        Section<LineageItem> lineage;
        Section<LineageItem> downstream;
        if (entityGraphTrusted) {
            structureRelations = buildStructureRelations(entity, columns, visibleViewKeys);
            terms = buildTerms(userId, entity, columns, datasourceId, sourceSnapshotId);
            tags = buildTags(entity);
            lineage = buildLineage(userId, datasourceId, entity, visibleLineageKeys);
            downstream = buildDownstream(userId, datasourceId, entity,
                    visibleLineageKeys, lineageDatasourceIds);
        } else {
            String message = "实体目录关系缺少可核验的来源快照，无法确认与当前详情一致。";
            structureRelations = Section.unavailable(message);
            terms = Section.unavailable(message);
            tags = Section.unavailable(message);
            lineage = Section.unavailable(message);
            downstream = Section.unavailable(message);
        }
        Section<FieldTagItem> fieldTags = sourceVerified
                ? buildFieldTags(userId, entity, datasourceId, columnInfo)
                : Section.unavailable("数据来源快照无法核验，无法读取字段治理标签。");

        Counts counts = new Counts(
                columnsSection.status().equals(Section.OK) ? columns.size() : 0,
                structureRelations.items().size(),
                terms.items().size(),
                tags.items().size(),
                fieldTags.items().size(),
                lineage.items().size(),
                downstream.items().size());

        return new CatalogEntityOverviewVO(entity, entityType, datasourceInfo,
                snapshotInfo(sourceSnapshot), snapshotInfo(publishedSnapshot),
                sourceVerified, sourceMatchesPublished, sourceMessage,
                tableInfo, columnInfo, columnsSection, counts,
                structureRelations, terms, tags, fieldTags, lineage, downstream);
    }

    private SnapshotInfo snapshotInfo(MetadataSnapshot snapshot) {
        return snapshot == null ? null : new SnapshotInfo(
                snapshot.getId(), snapshot.getSnapshotVersion(), snapshot.getStatus(),
                snapshot.getEntitySyncStatus(), snapshot.getEntitySyncError(),
                snapshot.getEntitySyncedAt());
    }

    private TableInfo basicTableInfo(MetadataEntity entity, String tableName,
                                     Map<String, Object> entityMetadata, boolean allowEntityBasics) {
        String displayName = allowEntityBasics && entity != null ? entity.getDisplayName() : null;
        String comment = allowEntityBasics && entity != null ? entity.getDescription() : null;
        String tableType = allowEntityBasics && entityMetadata != null
                ? stringValue(entityMetadata.get("table_type")) : null;
        String governance = allowEntityBasics && entityMetadata != null
                ? stringValue(entityMetadata.get("governance_status")) : null;
        return new TableInfo(entity == null ? null : entity.getId(), tableName,
                displayName, comment, tableType, governance);
    }

    // ==================== 基础信息 ====================

    private TableInfo toTableInfo(MetadataEntity entity, String tableName, DbTableMeta tableMeta,
                                  Map<String, Object> entityMetadata, boolean allowEntityMetadata) {
        String tableType = tableMeta != null ? tableMeta.getTableType()
                : allowEntityMetadata ? stringValue(entityMetadata == null ? null : entityMetadata.get("table_type"))
                : null;
        String governance = tableMeta != null ? tableMeta.getGovernanceStatus()
                : allowEntityMetadata ? stringValue(entityMetadata == null ? null : entityMetadata.get("governance_status"))
                : null;
        String comment = tableMeta != null ? tableMeta.getTableComment()
                : allowEntityMetadata && entity != null ? entity.getDescription() : null;
        String displayName = tableMeta != null ? tableMeta.getTableComment()
                : allowEntityMetadata && entity != null ? entity.getDisplayName() : null;
        return new TableInfo(entity == null ? null : entity.getId(), tableName, displayName,
                comment, tableType, governance);
    }

    private Section<ColumnInfo> loadTableColumns(Long datasourceId, Long snapshotId,
                                                 String tableName, Datasource datasource,
                                                 boolean allowEntityMetadata) {
        if (snapshotId == null || tableName == null) {
            return Section.unavailable("无法确定表字段的来源快照或表名，字段列表不可用。");
        }
        List<DbColumnMeta> metas = columnMetaMapper.selectList(new LambdaQueryWrapper<DbColumnMeta>()
                .eq(DbColumnMeta::getDatasourceId, datasourceId)
                .eq(DbColumnMeta::getSnapshotId, snapshotId)
                .eq(DbColumnMeta::getTableName, tableName)
                .orderByAsc(DbColumnMeta::getOrdinalPosition));
        if (metas == null || metas.isEmpty()) {
            return Section.unavailable("来源快照中未找到该表的字段元数据，无法显示字段列表。");
        }
        Map<String, MetadataEntity> entitiesByFqn = entitiesByFqn(datasourceId);
        String datasourceName = datasource == null || datasource.getName() == null
                ? "" : datasource.getName();
        String databaseName = datasource == null || datasource.getDatabaseName() == null
                ? "" : datasource.getDatabaseName();
        List<ColumnInfo> result = new ArrayList<>();
        for (DbColumnMeta meta : metas) {
            if (meta == null
                    || !columnMetaMatches(meta, datasourceId, snapshotId, tableName, meta.getColumnName())) {
                continue;
            }
            String fqn = MetadataEntity.fqnColumn(datasourceName, databaseName,
                    meta.getTableName(), meta.getColumnName());
            MetadataEntity columnEntity = entitiesByFqn.get(fqn.toLowerCase());
            if (!entityMatchesSnapshot(columnEntity, datasourceId, snapshotId)) {
                // 字段属性来自来源快照；目录实体如果属于其他快照，不能用来做同版本跳转。
                columnEntity = null;
            }
            result.add(toColumnInfo(columnEntity, meta, null, allowEntityMetadata));
        }
        return result.isEmpty()
                ? Section.unavailable("来源快照中的字段元数据归属校验未通过，字段列表不可用。")
                : Section.ok(result);
    }

    private ColumnInfo loadColumnInfo(MetadataEntity entity, Long datasourceId, Long snapshotId,
                                      String tableName, String columnName,
                                      Map<String, Object> entityMetadata,
                                      boolean allowEntityMetadata) {
        if (snapshotId == null || tableName == null || columnName == null) {
            return null;
        }
        DbColumnMeta meta = null;
        if (allowEntityMetadata && entityMetadata != null) {
            Long columnMetaId = asLong(entityMetadata.get("column_meta_id"));
            if (columnMetaId != null) {
                DbColumnMeta candidate = columnMetaMapper.selectById(columnMetaId);
                if (columnMetaMatches(candidate, datasourceId, snapshotId, tableName, columnName)) {
                    meta = candidate;
                }
            }
        }
        if (meta == null) {
            DbColumnMeta candidate = columnMetaMapper.selectOne(new LambdaQueryWrapper<DbColumnMeta>()
                    .eq(DbColumnMeta::getDatasourceId, datasourceId)
                    .eq(DbColumnMeta::getSnapshotId, snapshotId)
                    .eq(DbColumnMeta::getTableName, tableName)
                    .eq(DbColumnMeta::getColumnName, columnName)
                    .last("LIMIT 1"));
            if (columnMetaMatches(candidate, datasourceId, snapshotId, tableName, columnName)) {
                meta = candidate;
            }
        }
        return meta == null ? null : toColumnInfo(entity, meta, entityMetadata, allowEntityMetadata);
    }

    private ColumnInfo toColumnInfo(MetadataEntity entity, DbColumnMeta meta,
                                    Map<String, Object> entityMetadata, boolean allowEntityMetadata) {
        Map<String, Object> metadata;
        if (!allowEntityMetadata) {
            metadata = new LinkedHashMap<>();
        } else if (entityMetadata != null) {
            metadata = entityMetadata;
        } else if (entity != null) {
            metadata = parseMetadata(entity.getEntityMetadata());
        } else {
            metadata = new LinkedHashMap<>();
        }
        Long entityId = entity == null ? null : entity.getId();
        Long columnMetaId = meta == null ? asLong(metadata.get("column_meta_id")) : meta.getId();
        String name = meta == null ? stringValue(metadata.get("column_name")) : meta.getColumnName();
        String comment = meta == null ? null : meta.getColumnComment();
        String displayName = comment;
        String dataType = meta == null ? stringValue(metadata.get("data_type")) : meta.getDataType();
        Boolean nullable = meta == null ? asBoolean(metadata.get("is_nullable"))
                : (meta.getIsNullable() != null && meta.getIsNullable() == 1);
        Boolean primaryKey = meta == null ? asBoolean(metadata.get("is_primary_key"))
                : (meta.getIsPrimaryKey() != null && meta.getIsPrimaryKey() == 1);
        String governance = meta == null ? stringValue(metadata.get("governance_status"))
                : meta.getGovernanceStatus();
        String tableName = meta == null ? stringValue(metadata.get("table_name")) : meta.getTableName();
        Integer ordinal = meta == null ? null : meta.getOrdinalPosition();
        List<String> usageLimits = usageLimits(governance, metadata);
        return new ColumnInfo(entityId, columnMetaId, name, displayName, dataType,
                nullable, primaryKey, comment, governance, tableName, ordinal, usageLimits);
    }

    /** 元数据行必须同时匹配数据源、来源快照、表和字段，不能只凭主键接受旧快照结果。 */
    private boolean columnMetaMatches(DbColumnMeta meta, Long datasourceId, Long snapshotId,
                                      String tableName, String columnName) {
        return meta != null
                && Objects.equals(meta.getDatasourceId(), datasourceId)
                && Objects.equals(meta.getSnapshotId(), snapshotId)
                && equalsIgnoreCase(meta.getTableName(), tableName)
                && equalsIgnoreCase(meta.getColumnName(), columnName);
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    /** 目录实体必须属于同一数据源和同一来源快照，才能与来源快照字段一起展示/跳转。 */
    private boolean entityMatchesSnapshot(MetadataEntity entity, Long datasourceId, Long snapshotId) {
        if (entity == null) return false;
        Map<String, Object> metadata = parseMetadata(entity.getEntityMetadata());
        return Objects.equals(asLong(metadata.get("datasource_id")), datasourceId)
                && Objects.equals(asLong(metadata.get("snapshot_id")), snapshotId);
    }

    /** 只陈述当前已记录的治理限制；标签本身不等同于权限或已生效脱敏策略。 */
    private List<String> usageLimits(String governanceStatus, Map<String, Object> metadata) {
        List<String> limits = new ArrayList<>();
        if ("BLOCKED".equals(governanceStatus)) {
            limits.add("治理状态为“禁止使用”，不应进入问数、SQL 生成或临时授权。");
        } else if ("DEPRECATED".equals(governanceStatus)) {
            limits.add("治理状态为“已废弃”，不应继续作为正式查询字段使用。");
        } else if ("SENSITIVE".equals(governanceStatus)) {
            limits.add("已标记为敏感字段，查询结果需按现有保护策略处理。");
        }
        if (metadata.containsKey("pending_mask")) {
            limits.add("存在待确认的脱敏候选，尚未自动生效，需在字段治理工作区确认。");
        }
        return limits;
    }

    // ==================== 所属/结构关系 ====================

    private Section<RelationItem> buildStructureRelations(MetadataEntity entity, List<ColumnInfo> columns,
                                                          Set<String> visibleKeys) {
        try {
            List<MetadataRelationship> relations = new ArrayList<>(relationshipService.getVisibleRelationsByType(
                    entity.getEntityType(), entity.getId(), null));
            // 表详情还要展示字段级外键形成的表间引用；它们不是表实体自身的边，
            // 但属于“必要的表关系”，不能因为端点类型是 COLUMN 就隐藏。
            if (MetadataEntity.TYPE_TABLE.equals(entity.getEntityType())) {
                Set<Long> columnIds = columns.stream()
                        .map(ColumnInfo::entityId).filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toCollection(HashSet::new));
                if (!columnIds.isEmpty()) {
                    Set<Long> seen = relations.stream().map(MetadataRelationship::getId)
                            .filter(Objects::nonNull)
                            .collect(java.util.stream.Collectors.toCollection(HashSet::new));
                    // 一次查出当前表所有字段的外键边，避免按字段逐个查询。
                    List<MetadataRelationship> foreignKeys = relationshipMapper.selectList(
                            new LambdaQueryWrapper<MetadataRelationship>()
                                    .eq(MetadataRelationship::getRelationType,
                                            MetadataRelationship.TYPE_FOREIGN_KEY)
                                    .and(wrapper -> wrapper.in(MetadataRelationship::getSourceId, columnIds)
                                            .or().in(MetadataRelationship::getTargetId, columnIds)));
                    for (MetadataRelationship rel : foreignKeys) {
                        boolean sourceIsColumn = MetadataEntity.TYPE_COLUMN.equals(rel.getSourceType())
                                && columnIds.contains(rel.getSourceId());
                        boolean targetIsColumn = MetadataEntity.TYPE_COLUMN.equals(rel.getTargetType())
                                && columnIds.contains(rel.getTargetId());
                        if ((sourceIsColumn || targetIsColumn) && seen.add(rel.getId())) {
                            relations.add(rel);
                        }
                    }
                }
            }
            // getVisibleRelationsByType 的 null 可见范围表示“不限制”；这里已由端点权限保证当前对象可访问。
            Set<String> visible = visibleKeys;
            List<MetadataRelationship> filtered = relations.stream()
                    .filter(rel -> Set.of(MetadataRelationship.TYPE_CONTAINS,
                            MetadataRelationship.TYPE_HAS_PART,
                            MetadataRelationship.TYPE_FOREIGN_KEY).contains(rel.getRelationType()))
                    .filter(rel -> visible == null
                            || (visible.contains(MetadataEntity.endpointKey(rel.getSourceType(), rel.getSourceId()))
                            && visible.contains(MetadataEntity.endpointKey(rel.getTargetType(), rel.getTargetId()))))
                    .toList();
            Map<Long, MetadataEntity> names = loadEntities(filtered.stream()
                    .flatMap(rel -> java.util.stream.Stream.of(rel.getSourceId(), rel.getTargetId()))
                    .filter(Objects::nonNull).toList());
            List<RelationItem> items = new ArrayList<>();
            for (MetadataRelationship rel : filtered) {
                items.add(toRelationItem(rel, names));
            }
            return Section.ok(items);
        } catch (Exception exception) {
            log.warn("读取对象所属关系失败 entityId={}: {}", entity.getId(), exception.getMessage());
            return Section.error("所属关系读取失败：" + safeMessage(exception));
        }
    }

    private RelationItem toRelationItem(MetadataRelationship rel, Map<Long, MetadataEntity> names) {
        MetadataEntity source = names.get(rel.getSourceId());
        MetadataEntity target = names.get(rel.getTargetId());
        String direction = Objects.equals(rel.getTargetId(), rel.getSourceId()) ? "BOTH" : "RELATED";
        return new RelationItem(rel.getId(), rel.getRelationType(), direction,
                rel.getSourceType(), rel.getSourceId(), entityLabel(source, rel.getSourceId()),
                rel.getTargetType(), rel.getTargetId(), entityLabel(target, rel.getTargetId()),
                bindingStatus(rel));
    }

    // ==================== 业务术语 ====================

    private Section<TermItem> buildTerms(Long userId, MetadataEntity entity, List<ColumnInfo> columns,
                                         Long datasourceId, Long sourceSnapshotId) {
        try {
            adminGuard.requireGlobalFunction(userId, GLOSSARY_VIEW_FUNCTION);
        } catch (BusinessException exception) {
            return Section.forbidden("没有查看业务术语的能力，无法显示该对象关联的术语。");
        }
        try {
            Set<Long> visibleGlossaryDatasources = glossaryScopeService.visibleDatasourceIds(
                    userId, GLOSSARY_VIEW_FUNCTION);
            Map<Long, LinkedHashSet<String>> termColumns = new LinkedHashMap<>();
            boolean tableAggregate = MetadataEntity.TYPE_TABLE.equals(entity.getEntityType());
            List<MetadataEntity> targetColumns = new ArrayList<>();
            if (tableAggregate) {
                for (ColumnInfo column : columns) {
                    if (column.entityId() != null) {
                        MetadataEntity columnEntity = entityService.getById(column.entityId());
                        if (columnEntity != null) targetColumns.add(columnEntity);
                    }
                }
                // 列清单不完整时仍按 HAS_PART 关系补齐真实字段，避免把术语显示成“没有关联”。
                Set<Long> loadedColumnIds = new HashSet<>();
                targetColumns.forEach(column -> {
                    if (column.getId() != null) loadedColumnIds.add(column.getId());
                });
                for (MetadataRelationship relation : relationshipService.getBySource(
                        entity.getId(), MetadataEntity.TYPE_TABLE)) {
                    if (!MetadataRelationship.TYPE_HAS_PART.equals(relation.getRelationType())
                            || loadedColumnIds.contains(relation.getTargetId())) {
                        continue;
                    }
                    MetadataEntity columnEntity = entityService.getById(relation.getTargetId());
                    if (columnEntity != null
                            && MetadataEntity.TYPE_COLUMN.equals(columnEntity.getEntityType())
                            && entityMatchesSnapshot(columnEntity, datasourceId, sourceSnapshotId)) {
                        targetColumns.add(columnEntity);
                        loadedColumnIds.add(columnEntity.getId());
                    }
                }
            } else {
                targetColumns.add(entity);
            }
            Set<Long> directTableTermIds = new HashSet<>();
            for (MetadataRelationship rel : relationshipMapper.selectByTargetAndRelationType(
                    entity.getId(), entity.getEntityType(), MetadataRelationship.TYPE_GLOSSARY_OF)) {
                if (MetadataEntity.TYPE_GLOSSARY_TERM.equals(rel.getSourceType())
                        && !RelationshipBindingStatus.isInactive(rel)) {
                    termColumns.computeIfAbsent(rel.getSourceId(), key -> new LinkedHashSet<>());
                    directTableTermIds.add(rel.getSourceId());
                }
            }
            Map<Long, MetadataEntity> columnById = new HashMap<>();
            Set<Long> columnIds = new HashSet<>();
            for (MetadataEntity column : targetColumns) {
                if (column == null || !MetadataEntity.TYPE_COLUMN.equals(column.getEntityType())) continue;
                columnById.put(column.getId(), column);
                columnIds.add(column.getId());
            }
            if (!columnIds.isEmpty()) {
                // 一次读出当前表全部字段的术语映射，避免按字段逐个查询。
                List<MetadataRelationship> relations = relationshipMapper.selectList(
                        new LambdaQueryWrapper<MetadataRelationship>()
                                .eq(MetadataRelationship::getRelationType,
                                        MetadataRelationship.TYPE_GLOSSARY_OF)
                                .eq(MetadataRelationship::getSourceType,
                                        MetadataEntity.TYPE_GLOSSARY_TERM)
                                .eq(MetadataRelationship::getTargetType,
                                        MetadataEntity.TYPE_COLUMN)
                                .in(MetadataRelationship::getTargetId, columnIds));
                for (MetadataRelationship rel : relations) {
                    if (RelationshipBindingStatus.isInactive(rel)) {
                        continue;
                    }
                    MetadataEntity column = columnById.get(rel.getTargetId());
                    if (column == null) continue;
                    termColumns.computeIfAbsent(rel.getSourceId(), key -> new LinkedHashSet<>())
                            .add(column.getDisplayName() == null || column.getDisplayName().isBlank()
                                    ? column.getName() : column.getDisplayName());
                }
            }
            if (termColumns.isEmpty()) {
                return Section.ok(List.of());
            }
            List<GlossaryTerm> terms = glossaryTermMapper.selectBatchIds(termColumns.keySet());
            if (terms == null) terms = List.of();
            Map<Long, GlossaryTerm> termById = new HashMap<>();
            terms.forEach(term -> termById.put(term.getId(), term));
            Map<Long, GlossaryScopeService.Scope> scopes =
                    glossaryScopeService.termScopes(termColumns.keySet());

            List<TermItem> items = new ArrayList<>();
            for (Map.Entry<Long, LinkedHashSet<String>> entry : termColumns.entrySet()) {
                GlossaryTerm term = termById.get(entry.getKey());
                if (term == null) {
                    // 关系存在但术语主记录丢失：这是数据完整性问题，不能当成“没有术语”。
                    items.add(new TermItem(entry.getKey(), null, "术语记录缺失", null,
                            null, "MISSING", false,
                            "关联的术语记录已不存在，需要重新确认或清理该关联。",
                            "TERM_MISSING", "BROKEN", new ArrayList<>(entry.getValue()), null));
                    continue;
                }
                if (!GlossaryScopeService.visibleIn(scopes.get(term.getId()), visibleGlossaryDatasources)) {
                    continue;
                }
                // 原因分类由术语工作流统一给出；目录只展示，不另写一套宽松规则。
                var verification = glossaryTermWorkflowService.verify(term.getId());
                String linkMode = MetadataEntity.TYPE_COLUMN.equals(entity.getEntityType())
                        ? "DIRECT_COLUMN"
                        : (entry.getValue().isEmpty() && directTableTermIds.contains(term.getId())
                        ? "DIRECT_TABLE" : "VIA_COLUMNS");
                items.add(new TermItem(term.getId(), term.getGlossaryId(), term.getName(),
                        term.getDisplayName(), term.getDescription(), term.getStatus(),
                        verification.valid(), verification.message(), verification.code(), linkMode,
                        new ArrayList<>(entry.getValue()), term.getRevision()));
            }
            return Section.ok(items);
        } catch (Exception exception) {
            log.warn("读取对象术语失败 entityId={}: {}", entity.getId(), exception.getMessage());
            return Section.error("业务术语读取失败：" + safeMessage(exception));
        }
    }

    // ==================== 标签 ====================

    private Section<TagItem> buildTags(MetadataEntity entity) {
        try {
            List<TagItem> items = new ArrayList<>();
            List<MetadataRelationship> relations = relationshipService.getBySource(
                    entity.getId(), entity.getEntityType());
            List<Long> tagIds = relations.stream()
                    .filter(rel -> MetadataRelationship.TYPE_TAGGED_WITH.equals(rel.getRelationType()))
                    .filter(rel -> MetadataEntity.TYPE_TAG.equals(rel.getTargetType())
                            || rel.getTargetType() == null)
                    .map(MetadataRelationship::getTargetId)
                    .filter(Objects::nonNull)
                    .distinct().toList();
            Map<Long, MetadataEntity> tags = loadEntities(tagIds);
            for (MetadataRelationship rel : relations) {
                if (!MetadataRelationship.TYPE_TAGGED_WITH.equals(rel.getRelationType())) continue;
                if (rel.getTargetType() != null
                        && !MetadataEntity.TYPE_TAG.equals(rel.getTargetType())) {
                    continue;
                }
                if (RelationshipBindingStatus.isInactive(rel)) {
                    items.add(new TagItem(null, "标签关联已失效", null,
                            null, "UNBOUND", "快照发布后目标对象消失，关联已保留但需要重新确认",
                            false, RelationshipBindingStatus.UNBOUND));
                    continue;
                }
                MetadataEntity tag = tags.get(rel.getTargetId());
                if (tag == null || !MetadataEntity.TYPE_TAG.equals(tag.getEntityType())) {
                    items.add(new TagItem(null, "标签记录缺失", null,
                            "关联的标签实体已不存在或类型不一致", "BROKEN", "数据完整性提示",
                            false, null));
                    continue;
                }
                String tagName = tag.getName() == null ? "" : tag.getName();
                String source = tagName.toUpperCase().startsWith("PII.")
                        ? "敏感信息标签（人工确认）" : "分类标签（人工确认）";
                items.add(new TagItem(tag.getId(), tag.getName(), tag.getDisplayName(),
                        tag.getDescription(), "CONFIRMED", source, true,
                        bindingStatus(rel)));
            }
            // 采集推断只作为候选展示，不能伪装成已确认标签。
            Map<String, Object> metadata = parseMetadata(entity.getEntityMetadata());
            Object rawCandidates = metadata.get("tag_candidates");
            JsonNode candidates = rawCandidates == null ? null : objectMapper.valueToTree(rawCandidates);
            if (candidates != null && candidates.isTextual()) {
                try {
                    candidates = objectMapper.readTree(candidates.asText());
                } catch (Exception ignored) {
                    candidates = null;
                }
            }
            if (candidates != null && candidates.isArray()) {
                for (JsonNode candidate : candidates) {
                    String tagName = candidate.asText(null);
                    if (tagName == null || tagName.isBlank()) continue;
                    items.add(new TagItem(null, tagName, tagName, null, "CANDIDATE",
                            "采集推断候选（未确认，不自动生效）", false, null));
                }
            }
            return Section.ok(items);
        } catch (Exception exception) {
            log.warn("读取对象标签失败 entityId={}: {}", entity.getId(), exception.getMessage());
            return Section.error("标签读取失败：" + safeMessage(exception));
        }
    }

    private Section<FieldTagItem> buildFieldTags(Long userId, MetadataEntity entity, Long datasourceId,
                                                 ColumnInfo columnInfo) {
        if (!MetadataEntity.TYPE_COLUMN.equals(entity.getEntityType())) {
            return Section.ok(List.of());
        }
        try {
            adminGuard.requireDatasourceFunction(userId, FIELD_VIEW_FUNCTION, datasourceId);
        } catch (BusinessException exception) {
            return Section.forbidden("没有查看字段治理标签的能力，无法显示该字段的治理标签。");
        }
        if (columnInfo == null || columnInfo.columnMetaId() == null) {
            return Section.unavailable("当前发布快照中找不到该字段的治理元数据，无法读取字段治理标签。");
        }
        try {
            List<FieldTagVO> tags = fieldTagService.getTagsByColumnMetaId(columnInfo.columnMetaId());
            List<FieldTagItem> items = tags.stream()
                    .map(tag -> new FieldTagItem(tag.getId(), tag.getColumnMetaId(),
                            tag.getTagCode(), tag.getTagName(), tag.getSource()))
                    .toList();
            return Section.ok(items);
        } catch (Exception exception) {
            log.warn("读取字段治理标签失败 entityId={}: {}", entity.getId(), exception.getMessage());
            return Section.error("字段治理标签读取失败：" + safeMessage(exception));
        }
    }

    // ==================== 血缘与下游 ====================

    private Section<LineageItem> buildLineage(Long userId, Long datasourceId,
                                              MetadataEntity entity, Set<String> visibleKeys) {
        try {
            adminGuard.requireDatasourceFunction(userId, LINEAGE_VIEW_FUNCTION, datasourceId);
        } catch (BusinessException exception) {
            return Section.forbidden("没有查看血缘的能力，无法显示该对象的血缘。");
        }
        try {
            String relationType = MetadataEntity.TYPE_COLUMN.equals(entity.getEntityType())
                    ? MetadataRelationship.TYPE_DERIVED_FROM : MetadataRelationship.TYPE_LINEAGE;
            List<MetadataRelationship> relations = relationshipService.getVisibleRelationsByType(
                    entity.getEntityType(), entity.getId(), null).stream()
                    .filter(rel -> relationType.equals(rel.getRelationType()))
                    .filter(rel -> visibleKeys == null
                            || (visibleKeys.contains(MetadataEntity.endpointKey(rel.getSourceType(), rel.getSourceId()))
                            && visibleKeys.contains(MetadataEntity.endpointKey(rel.getTargetType(), rel.getTargetId()))))
                    .toList();
            Map<Long, MetadataEntity> names = loadEntities(relations.stream()
                    .flatMap(rel -> java.util.stream.Stream.of(rel.getSourceId(), rel.getTargetId()))
                    .filter(Objects::nonNull).toList());
            List<LineageItem> items = new ArrayList<>();
            for (MetadataRelationship rel : relations) {
                String direction = entity.getId().equals(rel.getSourceId()) ? "DOWNSTREAM" : "UPSTREAM";
                items.add(toLineageItem(rel, direction, names));
            }
            return Section.ok(items);
        } catch (Exception exception) {
            log.warn("读取对象血缘失败 entityId={}: {}", entity.getId(), exception.getMessage());
            return Section.error("血缘读取失败：" + safeMessage(exception));
        }
    }

    private Section<LineageItem> buildDownstream(Long userId, Long datasourceId,
                                                 MetadataEntity entity, Set<String> visibleKeys,
                                                 Collection<Long> visibleDatasourceIds) {
        try {
            adminGuard.requireDatasourceFunction(userId, LINEAGE_VIEW_FUNCTION, datasourceId);
        } catch (BusinessException exception) {
            return Section.forbidden("没有查看下游影响的能力，无法显示该对象的下游影响。");
        }
        try {
            // 可见范围必须在遍历阶段生效：遇到无权节点时停止，不用它当跳板。
            List<MetadataRelationship> relations = relationshipService.getDownstream(
                    entity.getEntityType(), entity.getId(), 10, visibleDatasourceIds);
            List<MetadataRelationship> visible = relations.stream()
                    .filter(rel -> visibleKeys == null
                            || (visibleKeys.contains(MetadataEntity.endpointKey(rel.getSourceType(), rel.getSourceId()))
                            && visibleKeys.contains(MetadataEntity.endpointKey(rel.getTargetType(), rel.getTargetId()))))
                    .toList();
            Map<Long, MetadataEntity> names = loadEntities(visible.stream()
                    .flatMap(rel -> java.util.stream.Stream.of(rel.getSourceId(), rel.getTargetId()))
                    .filter(Objects::nonNull).toList());
            List<LineageItem> items = new ArrayList<>();
            for (MetadataRelationship rel : visible) {
                items.add(toLineageItem(rel, "DOWNSTREAM", names));
            }
            return Section.ok(items);
        } catch (Exception exception) {
            log.warn("读取对象下游影响失败 entityId={}: {}", entity.getId(), exception.getMessage());
            return Section.error("下游影响读取失败：" + safeMessage(exception));
        }
    }

    private LineageItem toLineageItem(MetadataRelationship rel, String direction,
                                      Map<Long, MetadataEntity> names) {
        Map<String, Object> metadata = parseMetadata(rel.getRelationMetadata());
        return new LineageItem(rel.getId(), rel.getRelationType(), direction,
                rel.getSourceType(), rel.getSourceId(),
                entityLabel(names.get(rel.getSourceId()), rel.getSourceId()),
                rel.getTargetType(), rel.getTargetId(),
                entityLabel(names.get(rel.getTargetId()), rel.getTargetId()),
                stringValue(metadata.get("lineage_type")),
                stringValue(metadata.get(RelationshipBindingStatus.KEY_STATUS)),
                stringValue(metadata.get("description")));
    }

    // ==================== 工具 ====================

    private DbTableMeta findTableMeta(Long datasourceId, Long snapshotId, String tableName,
                                      Map<String, Object> entityMetadata, boolean allowEntityMetadata) {
        if (snapshotId == null || tableName == null) return null;
        if (allowEntityMetadata && entityMetadata != null) {
            Long tableMetaId = asLong(entityMetadata.get("table_meta_id"));
            if (tableMetaId != null) {
                DbTableMeta candidate = tableMetaMapper.selectById(tableMetaId);
                if (tableMetaMatches(candidate, datasourceId, snapshotId, tableName)) {
                    return candidate;
                }
            }
        }
        DbTableMeta candidate = tableMetaMapper.selectOne(new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getDatasourceId, datasourceId)
                .eq(DbTableMeta::getSnapshotId, snapshotId)
                .eq(DbTableMeta::getTableName, tableName)
                .last("LIMIT 1"));
        return tableMetaMatches(candidate, datasourceId, snapshotId, tableName) ? candidate : null;
    }

    /** 表元数据行必须同时匹配数据源、来源快照和表名。 */
    private boolean tableMetaMatches(DbTableMeta meta, Long datasourceId, Long snapshotId,
                                     String tableName) {
        return meta != null
                && Objects.equals(meta.getDatasourceId(), datasourceId)
                && Objects.equals(meta.getSnapshotId(), snapshotId)
                && equalsIgnoreCase(meta.getTableName(), tableName);
    }

    private Map<String, MetadataEntity> entitiesByFqn(Long datasourceId) {
        Map<String, MetadataEntity> result = new HashMap<>();
        for (MetadataEntity entity : entityService.getByDatasourceId(datasourceId)) {
            if (entity.getFqn() != null) {
                result.put(entity.getFqn().toLowerCase(), entity);
            }
        }
        return result;
    }

    private Map<Long, MetadataEntity> loadEntities(Collection<Long> ids) {
        Set<Long> distinct = new LinkedHashSet<>(ids);
        distinct.remove(null);
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, MetadataEntity> result = new HashMap<>();
        List<MetadataEntity> entities = entityService.listByIds(distinct);
        if (entities != null) {
            entities.forEach(entity -> result.put(entity.getId(), entity));
        }
        return result;
    }

    /**
     * 表名优先使用采集时保存的原始大小写；FQN 已小写，仅在元数据缺失时兜底。
     * 元数据行仍会按数据源、来源快照和大小写无关的名称再次核验。
     */
    private String tableNameOf(MetadataEntity entity, Map<String, Object> metadata) {
        String tableName = stringValue(metadata.get("table_name"));
        if (tableName != null) return tableName;
        String fqn = entity.getFqn();
        if (fqn != null) {
            String[] parts = fqn.split("\\.");
            if (MetadataEntity.TYPE_TABLE.equals(entity.getEntityType()) && parts.length >= 3) {
                return parts[parts.length - 1];
            }
            if (MetadataEntity.TYPE_COLUMN.equals(entity.getEntityType()) && parts.length >= 4) {
                return parts[parts.length - 2];
            }
        }
        return null;
    }

    private String columnNameOf(MetadataEntity entity, Map<String, Object> metadata) {
        String columnName = stringValue(metadata.get("column_name"));
        if (columnName != null) return columnName;
        String fqn = entity.getFqn();
        if (fqn != null) {
            String[] parts = fqn.split("\\.");
            if (parts.length >= 4) {
                return parts[parts.length - 1];
            }
        }
        return entity.getName();
    }

    private String entityLabel(MetadataEntity entity, Long id) {
        if (entity == null) return id == null ? null : "未找到的对象 #" + id;
        if (entity.getDisplayName() != null && !entity.getDisplayName().isBlank()) {
            return entity.getDisplayName() + "（" + entity.getName() + "）";
        }
        return entity.getName();
    }

    private String bindingStatus(MetadataRelationship rel) {
        String status = stringValue(parseMetadata(rel.getRelationMetadata())
                .get(RelationshipBindingStatus.KEY_STATUS));
        return status == null ? RelationshipBindingStatus.BOUND : status;
    }

    private List<Long> visibleDatasourceIds(Long userId, String functionCode) {
        List<Long> result = new ArrayList<>();
        for (IamS1DatasourceRefVO datasource
                : capabilityService.responsibleDatasourcesWithFunction(userId, functionCode)) {
            if (datasource.id() != null) {
                result.add(datasource.id());
            }
        }
        return result;
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception exception) {
            return new LinkedHashMap<>();
        }
    }

    private String stringValue(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value == null) return null;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "请稍后重试";
        }
        return message.length() > 160 ? message.substring(0, 160) : message;
    }
}
