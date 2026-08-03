package com.dataocean.module.audit.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.audit.entity.dto.ColumnMappingItem;
import com.dataocean.module.audit.entity.dto.LineageCreateRequest;
import com.dataocean.module.audit.entity.vo.LineageEdgeVO;
import com.dataocean.module.audit.entity.vo.LineageGraphVO;
import com.dataocean.module.audit.service.LineageEdgeService;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 血缘边管理服务实现
 * <p>
 * 实现 ETL/MANUAL 血缘的手动创建、删除、批量导入和增强查询。
 * 核心规则：
 * 1. LINEAGE 仅允许 TABLE → TABLE，API 层校验实体类型
 * 2. DERIVED_FROM 仅允许 COLUMN → COLUMN，由 LINEAGE 创建时自动生成
 * 3. 列级关系仅有 DERIVED_FROM 一种表达，避免重复
 * 4. LINEAGE 边方向统一为 source（上游）→ target（下游）
 * </p>
 *
 * @author dataocean
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LineageEdgeServiceImpl implements LineageEdgeService {

    private final MetadataEntityService entityService;
    private final MetadataRelationshipService relationshipService;
    private final ObjectMapper objectMapper;

    // ========== 转换类型映射 ==========

    /** API transformationType → expression_type 映射 */
    private static final Map<String, String> TRANSFORMATION_TO_EXPRESSION = Map.of(
            "IDENTITY", "DIRECT",
            "TRANSFORMATION", "ARITHMETIC",
            "AGGREGATION", "AGGREGATION"
    );

    /** 允许的血缘类型 */
    private static final Set<String> ALLOWED_LINEAGE_TYPES = Set.of("ETL", "MANUAL");

    /** 允许的转换类型 */
    private static final Set<String> ALLOWED_TRANSFORMATION_TYPES = Set.of("IDENTITY", "TRANSFORMATION", "AGGREGATION");

    // ========== 创建 LINEAGE 关系 ==========

    @Override
    @Transactional
    public LineageEdgeVO createLineage(LineageCreateRequest request) {
        // 1. 校验血缘类型
        validateLineageType(request.getLineageType());

        // 2. 加载源/目标实体并校验类型均为 TABLE
        MetadataEntity sourceEntity = loadAndValidateEntity(request.getSourceEntityId(), MetadataEntity.TYPE_TABLE, "源");
        MetadataEntity targetEntity = loadAndValidateEntity(request.getTargetEntityId(), MetadataEntity.TYPE_TABLE, "目标");

        // 3. 校验列映射（如果提供）
        List<ColumnMappingItem> mappings = request.getColumnMappings();
        if (mappings != null) {
            for (ColumnMappingItem mapping : mappings) {
                validateColumnMapping(mapping);
            }
        }

        // 4. 创建 LINEAGE 边（TABLE → TABLE）
        MetadataRelationship lineageRel = buildLineageRelationship(
                sourceEntity, targetEntity, request, mappings);
        lineageRel = relationshipService.upsert(lineageRel);

        // 5. 为每条 columnMapping 创建 DERIVED_FROM 边（COLUMN → COLUMN）
        int derivedCount = 0;
        if (mappings != null && !mappings.isEmpty()) {
            derivedCount = createDerivedFromEdges(sourceEntity, targetEntity, mappings);
        }

        // 6. 组装响应
        return buildLineageEdgeVO(lineageRel, derivedCount);
    }

    // ========== 删除 LINEAGE 关系 ==========

    @Override
    @Transactional
    public int deleteLineage(Long relationshipId, boolean cascadeDerived) {
        // 1. 加载关系并校验类型
        MetadataRelationship rel = relationshipService.getById(relationshipId);
        if (rel == null) {
            throw new BusinessException(404, "血缘关系不存在");
        }
        if (!MetadataRelationship.TYPE_LINEAGE.equals(rel.getRelationType())) {
            throw new BusinessException(400, "仅支持删除 LINEAGE 类型关系，当前类型: " + rel.getRelationType());
        }

        // 2. 查询关联的 DERIVED_FROM 边数量
        int derivedCount = countDerivedFromEdges(rel);

        // 3. 如果 cascadeDerived=true，级联删除 DERIVED_FROM 边
        if (cascadeDerived) {
            cascadeDeleteDerivedFromEdges(rel);
        }

        // 4. 删除 LINEAGE 边
        relationshipService.removeById(relationshipId);
        log.info("已删除 LINEAGE 关系 relationshipId={} cascadeDerived={} derivedCount={}",
                relationshipId, cascadeDerived, derivedCount);

        return derivedCount;
    }

    // ========== 批量创建血缘 ==========

    @Override
    @Transactional
    public List<LineageEdgeVO> batchCreateLineage(List<LineageCreateRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException(400, "批量创建请求列表不能为空");
        }
        if (requests.size() > 200) {
            throw new BusinessException(400, "单次批量创建最多 200 条，当前: " + requests.size());
        }

        List<LineageEdgeVO> results = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            try {
                LineageEdgeVO vo = createLineage(requests.get(i));
                results.add(vo);
            } catch (Exception e) {
                log.warn("批量创建血缘第 {} 条失败: {}", i + 1, e.getMessage());
                // 记录失败的条目（继续执行后续条目，不中断整个批量操作）
                LineageEdgeVO errorVo = new LineageEdgeVO();
                errorVo.setSourceEntityId(requests.get(i).getSourceEntityId());
                errorVo.setTargetEntityId(requests.get(i).getTargetEntityId());
                errorVo.setDescription("创建失败: " + e.getMessage());
                results.add(errorVo);
            }
        }
        log.info("批量创建血缘完成 total={} success={}", requests.size(),
                results.stream().filter(r -> r.getRelationshipId() != null).count());
        return results;
    }

    // ========== 增强血缘查询 ==========

    @Override
    public LineageGraphVO getEnrichedLineage(Long entityId, int depth, Set<String> lineageTypes) {
        // 1. BFS 遍历获取带列映射的血缘关系
        Set<Long> visited = new HashSet<>();
        Map<Long, MetadataRelationship> edgeMap = new LinkedHashMap<>();
        Set<Long> allEntityIds = new HashSet<>();
        allEntityIds.add(entityId);

        List<Long> currentLevel = List.of(entityId);
        for (int d = 0; d < Math.min(depth, 10) && !currentLevel.isEmpty(); d++) {
            List<Long> nextLevel = new ArrayList<>();
            for (Long id : currentLevel) {
                if (!visited.add(id)) continue;
                List<MetadataRelationship> lineage = relationshipService.getLineage(id);
                for (MetadataRelationship rel : lineage) {
                    // 按血缘类型过滤
                    if (lineageTypes != null && !lineageTypes.isEmpty()) {
                        String lt = extractLineageType(rel);
                        if (!lineageTypes.contains(lt)) continue;
                    }
                    if (edgeMap.containsKey(rel.getId())) continue;
                    edgeMap.put(rel.getId(), rel);
                    allEntityIds.add(rel.getSourceId());
                    allEntityIds.add(rel.getTargetId());
                    // 继续向上下游扩展
                    if (!visited.contains(rel.getSourceId())) nextLevel.add(rel.getSourceId());
                    if (!visited.contains(rel.getTargetId())) nextLevel.add(rel.getTargetId());
                }
            }
            currentLevel = nextLevel;
        }

        // 2. 加载所有涉及的实体节点
        List<MetadataEntity> nodes = new ArrayList<>();
        for (Long nid : allEntityIds) {
            MetadataEntity entity = entityService.getById(nid);
            if (entity != null) nodes.add(entity);
        }

        // 3. 提取每条 LINEAGE 边的列映射摘要
        Map<Long, List<Map<String, Object>>> columnMappingsByEdge = new HashMap<>();
        for (MetadataRelationship rel : edgeMap.values()) {
            List<Map<String, Object>> mappings = extractColumnMappings(rel);
            if (!mappings.isEmpty()) {
                columnMappingsByEdge.put(rel.getId(), mappings);
            }
        }

        // 4. 组装响应
        LineageGraphVO vo = new LineageGraphVO();
        vo.setNodes(nodes);
        vo.setEdges(new ArrayList<>(edgeMap.values()));
        vo.setColumnMappingsByEdge(columnMappingsByEdge);
        vo.setDepth(depth);
        return vo;
    }

    // ========== 私有辅助方法 ==========

    /**
     * 校验血缘类型仅允许 ETL 或 MANUAL
     */
    private void validateLineageType(String lineageType) {
        if (lineageType == null || !ALLOWED_LINEAGE_TYPES.contains(lineageType.toUpperCase())) {
            throw new BusinessException(400, "血缘类型仅允许 ETL 或 MANUAL，当前值: " + lineageType);
        }
    }

    /**
     * 加载实体并校验类型
     *
     * @param entityId   实体 ID
     * @param expectedType 预期实体类型
     * @param label      标签（"源"/"目标"）
     * @return 实体对象
     */
    private MetadataEntity loadAndValidateEntity(Long entityId, String expectedType, String label) {
        MetadataEntity entity = entityService.getById(entityId);
        if (entity == null) {
            throw new BusinessException(404, label + "实体不存在，ID: " + entityId);
        }
        if (!expectedType.equals(entity.getEntityType())) {
            throw new BusinessException(400,
                    label + "实体类型必须为 " + expectedType + "，当前类型: " + entity.getEntityType()
                            + "（拒绝直接创建 COLUMN → COLUMN 的 LINEAGE）");
        }
        return entity;
    }

    /**
     * 校验列映射条目
     */
    private void validateColumnMapping(ColumnMappingItem mapping) {
        // 校验 fromColumns 不为空
        if (mapping.getFromColumns() == null || mapping.getFromColumns().isEmpty()) {
            throw new BusinessException(400, "列映射中的源列 ID 列表不能为空");
        }
        // 校验所有源列为 COLUMN 类型
        for (Long fromColId : mapping.getFromColumns()) {
            MetadataEntity col = entityService.getById(fromColId);
            if (col == null) {
                throw new BusinessException(404, "源列实体不存在，ID: " + fromColId);
            }
            if (!MetadataEntity.TYPE_COLUMN.equals(col.getEntityType())) {
                throw new BusinessException(400, "源列实体类型必须为 COLUMN，ID: " + fromColId + " 类型: " + col.getEntityType());
            }
        }
        // 校验目标列为 COLUMN 类型
        MetadataEntity toCol = entityService.getById(mapping.getToColumn());
        if (toCol == null) {
            throw new BusinessException(404, "目标列实体不存在，ID: " + mapping.getToColumn());
        }
        if (!MetadataEntity.TYPE_COLUMN.equals(toCol.getEntityType())) {
            throw new BusinessException(400, "目标列实体类型必须为 COLUMN，ID: " + mapping.getToColumn() + " 类型: " + toCol.getEntityType());
        }
        // 校验转换类型（如果提供）
        if (mapping.getTransformationType() != null && !mapping.getTransformationType().isBlank()) {
            if (!ALLOWED_TRANSFORMATION_TYPES.contains(mapping.getTransformationType())) {
                throw new BusinessException(400,
                        "转换类型仅允许 IDENTITY / TRANSFORMATION / AGGREGATION，当前值: " + mapping.getTransformationType());
            }
        }
    }

    /**
     * 构建 LINEAGE 关系对象
     *
     * @param sourceEntity 上游表实体
     * @param targetEntity 下游表实体
     * @param request      创建请求
     * @param mappings     列映射列表
     * @return LINEAGE 关系对象（未入库）
     */
    private MetadataRelationship buildLineageRelationship(
            MetadataEntity sourceEntity,
            MetadataEntity targetEntity,
            LineageCreateRequest request,
            List<ColumnMappingItem> mappings) {
        MetadataRelationship rel = new MetadataRelationship();
        rel.setSourceId(sourceEntity.getId());
        rel.setSourceType(MetadataEntity.TYPE_TABLE);
        rel.setTargetId(targetEntity.getId());
        rel.setTargetType(MetadataEntity.TYPE_TABLE);
        rel.setRelationType(MetadataRelationship.TYPE_LINEAGE);

        // 构建 relation_metadata JSON
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("lineage_type", request.getLineageType().toUpperCase());
        if (request.getDescription() != null && !request.getDescription().isBlank()) {
            metadata.put("description", request.getDescription());
        }

        // 构建 column_mappings 摘要
        if (mappings != null && !mappings.isEmpty()) {
            List<Map<String, Object>> mappingSummaries = new ArrayList<>();
            for (ColumnMappingItem mapping : mappings) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("from_column_ids", mapping.getFromColumns());

                // 填充冗余 FQN 加速前端渲染
                List<String> fromFqns = new ArrayList<>();
                for (Long fromColId : mapping.getFromColumns()) {
                    MetadataEntity col = entityService.getById(fromColId);
                    fromFqns.add(col != null ? col.getFqn() : "UNKNOWN");
                }
                summary.put("from_fqns", fromFqns);

                summary.put("to_column_id", mapping.getToColumn());
                MetadataEntity toCol = entityService.getById(mapping.getToColumn());
                summary.put("to_fqn", toCol != null ? toCol.getFqn() : "UNKNOWN");

                if (mapping.getExpression() != null && !mapping.getExpression().isBlank()) {
                    summary.put("expression", mapping.getExpression());
                }
                if (mapping.getTransformationType() != null && !mapping.getTransformationType().isBlank()) {
                    summary.put("transformation_type", mapping.getTransformationType());
                }
                mappingSummaries.add(summary);
            }
            metadata.put("column_mappings", mappingSummaries);
        }

        // 审计信息：优先填充表列 created_by（过渡期也写入 JSON）
        String currentUser = getCurrentUsername();
        metadata.put("created_by", currentUser);
        metadata.put("updated_at", LocalDateTime.now().toString());

        // 同时设置表列 created_by（如果 metadata_relationship 已有该字段，通过 V46 迁移新增）
        rel.setCreatedBy(currentUser);

        try {
            rel.setRelationMetadata(objectMapper.writeValueAsString(metadata));
        } catch (Exception e) {
            throw new BusinessException(500, "序列化 relation_metadata 失败: " + e.getMessage());
        }

        // 设置 created_by 表列（如果 metadata_relationship 已有该字段）
        // 当前通过 MyBatis-Plus 的 MetaObjectHandler 自动填充，如无则依赖 JSON 中的审计字段
        return rel;
    }

    /**
     * 为每条 columnMapping 创建 DERIVED_FROM 边
     * <p>
     * 核心规则：fromColumns 中的每个源列 → toColumn 创建独立的一对一 DERIVED_FROM 边。
     * 例如 fromColumns=[301,302], toColumn=401 会创建 2 条 DERIVED_FROM 边（301→401 和 302→401）。
     * 复用 metadata_relationship 表的 UNIQUE(source_id, target_id, relation_type) 约束做去重。
     * </p>
     *
     * @param sourceTable 上游表实体
     * @param targetTable 下游表实体
     * @param mappings    列映射列表
     * @return 创建的 DERIVED_FROM 边总数
     */
    private int createDerivedFromEdges(
            MetadataEntity sourceTable,
            MetadataEntity targetTable,
            List<ColumnMappingItem> mappings) {
        int count = 0;
        for (ColumnMappingItem mapping : mappings) {
            String expressionType = mapExpressionType(mapping.getTransformationType());

            for (Long fromColId : mapping.getFromColumns()) {
                MetadataRelationship derivedRel = new MetadataRelationship();
                derivedRel.setSourceId(fromColId);
                derivedRel.setSourceType(MetadataEntity.TYPE_COLUMN);
                derivedRel.setTargetId(mapping.getToColumn());
                derivedRel.setTargetType(MetadataEntity.TYPE_COLUMN);
                derivedRel.setRelationType(MetadataRelationship.TYPE_DERIVED_FROM);

                // 构建 DERIVED_FROM 的 relation_metadata（先查询已有边，合并 source 来源历史）
                MetadataRelationship existingDerived = findDerivedFromEdge(fromColId, mapping.getToColumn());
                Map<String, Object> existingMeta = null;
                if (existingDerived != null) {
                    derivedRel = existingDerived; // 复用已有边对象，upsert 时更新
                    existingMeta = parseRelationMeta(existingDerived.getRelationMetadata());
                }

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("expression_type", expressionType);
                if (mapping.getExpression() != null && !mapping.getExpression().isBlank()) {
                    meta.put("expression", mapping.getExpression());
                }
                // §4.2.4：source 字段合并去重，保留来源历史（如 ["SQL_PARSER", "MANUAL"]）
                meta.put("source", mergeSourceField(existingMeta, "MANUAL"));
                meta.put("parent_lineage_table_source_id", sourceTable.getId());
                meta.put("parent_lineage_table_target_id", targetTable.getId());

                // 对齐 OpenLineage ColumnLineageDatasetFacet
                Map<String, Object> transformation = new LinkedHashMap<>();
                transformation.put("type", mapping.getTransformationType() != null
                        ? mapping.getTransformationType() : "IDENTITY");
                transformation.put("subtype", expressionType);
                if (mapping.getExpression() != null) {
                    transformation.put("description", mapping.getExpression());
                }
                meta.put("transformation", transformation);

                // 审计信息
                String currentUser = getCurrentUsername();
                meta.put("created_by", currentUser);
                meta.put("updated_at", LocalDateTime.now().toString());
                derivedRel.setCreatedBy(currentUser);

                try {
                    derivedRel.setRelationMetadata(objectMapper.writeValueAsString(meta));
                } catch (Exception e) {
                    log.warn("序列化 DERIVED_FROM relation_metadata 失败 fromCol={} toCol={}: {}",
                            fromColId, mapping.getToColumn(), e.getMessage());
                    continue;
                }

                try {
                    relationshipService.upsert(derivedRel);
                    count++;
                } catch (Exception e) {
                    log.warn("创建 DERIVED_FROM 边失败 fromCol={} toCol={}: {}",
                            fromColId, mapping.getToColumn(), e.getMessage());
                }
            }
        }
        log.info("已创建 {} 条 DERIVED_FROM 边，源表={} 目标表={}", count,
                sourceTable.getFqn(), targetTable.getFqn());
        return count;
    }

    /**
     * 映射 API transformationType → expression_type
     */
    private String mapExpressionType(String transformationType) {
        if (transformationType == null || transformationType.isBlank()) {
            return "DIRECT";
        }
        return TRANSFORMATION_TO_EXPRESSION.getOrDefault(transformationType, "DIRECT");
    }

    /**
     * 统计关联的 DERIVED_FROM 边数量
     */
    private int countDerivedFromEdges(MetadataRelationship lineageRel) {
        // 通过 column_mappings 找出所有源列 ID 和目标列 ID，然后查询 DERIVED_FROM 边数
        List<Map<String, Object>> mappings = extractColumnMappings(lineageRel);
        if (mappings.isEmpty()) return 0;

        int count = 0;
        for (Map<String, Object> mapping : mappings) {
            @SuppressWarnings("unchecked")
            List<Number> fromIds = (List<Number>) mapping.get("from_column_ids");
            Number toId = (Number) mapping.get("to_column_id");
            if (fromIds != null && toId != null) {
                for (Number fromId : fromIds) {
                    MetadataRelationship existing = findDerivedFromEdge(fromId.longValue(), toId.longValue());
                    if (existing != null) count++;
                }
            }
        }
        return count;
    }

    /**
     * 级联删除关联的 DERIVED_FROM 边
     */
    private void cascadeDeleteDerivedFromEdges(MetadataRelationship lineageRel) {
        List<Map<String, Object>> mappings = extractColumnMappings(lineageRel);
        for (Map<String, Object> mapping : mappings) {
            @SuppressWarnings("unchecked")
            List<Number> fromIds = (List<Number>) mapping.get("from_column_ids");
            Number toId = (Number) mapping.get("to_column_id");
            if (fromIds != null && toId != null) {
                for (Number fromId : fromIds) {
                    MetadataRelationship existing = findDerivedFromEdge(fromId.longValue(), toId.longValue());
                    if (existing != null) {
                        relationshipService.removeById(existing.getId());
                    }
                }
            }
        }
        log.info("已级联删除 DERIVED_FROM 边 relationshipId={}", lineageRel.getId());
    }

    /**
     * §4.2.4：合并 DERIVED_FROM 关系的 source 来源历史
     * <p>
     * 多次创建同一对列的 DERIVED_FROM 边时（先 SQL_PARSER 后 MANUAL 或反之），
     * source 字段自动转为去重数组以保留来源历史，不覆盖旧来源。
     * 单个来源存字符串，多个来源存字符串数组。
     * </p>
     *
     * @param existingMeta 已有边的 relation_metadata JSON Map（可能为 null）
     * @param newSource    本次新来源（如 "MANUAL"、"SQL_PARSER"）
     * @return 合并后的 source 值（String 或 List&lt;String&gt;）
     */
    private Object mergeSourceField(Map<String, Object> existingMeta, String newSource) {
        if (existingMeta == null) {
            return newSource;
        }
        Object oldSource = existingMeta.get("source");
        if (oldSource == null) {
            return newSource;
        }

        // 去重集合
        Set<String> sources = new LinkedHashSet<>();

        // 解析旧值
        if (oldSource instanceof String s && !s.isBlank()) {
            sources.add(s);
        } else if (oldSource instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) {
                    sources.add(s);
                }
            }
        }

        // 加入新来源
        sources.add(newSource);

        // 单来源存字符串，多来源存数组
        if (sources.size() == 1) {
            return sources.iterator().next();
        }
        return new ArrayList<>(sources);
    }

    /**
     * 解析 relation_metadata JSON 字符串为 Map（用于 source 合并时读取已有元数据）
     */
    private Map<String, Object> parseRelationMeta(String relationMetadata) {
        if (relationMetadata == null || relationMetadata.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(relationMetadata, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("解析 relation_metadata 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 查找两个列之间的 DERIVED_FROM 边
     */
    private MetadataRelationship findDerivedFromEdge(Long sourceColId, Long targetColId) {
        // 利用 MetadataRelationshipMapper.selectBetween 按 source/target/relationType 查找
        // selectBetween 是带 LIMIT 1 的查询，适合此场景
        try {
            return relationshipService.getBaseMapper().selectBetween(
                    sourceColId, targetColId, MetadataRelationship.TYPE_DERIVED_FROM);
        } catch (Exception e) {
            log.debug("查找 DERIVED_FROM 边失败 source={} target={}: {}", sourceColId, targetColId, e.getMessage());
            return null;
        }
    }

    /**
     * 从 relation_metadata JSON 中提取列映射摘要列表
     */
    private List<Map<String, Object>> extractColumnMappings(MetadataRelationship rel) {
        if (rel.getRelationMetadata() == null || rel.getRelationMetadata().isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(
                    rel.getRelationMetadata(), new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mappings = (List<Map<String, Object>>) metadata.get("column_mappings");
            return mappings != null ? mappings : List.of();
        } catch (Exception e) {
            log.debug("解析 relation_metadata column_mappings 失败 relId={}: {}", rel.getId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 从 relation_metadata JSON 中提取 lineage_type
     */
    private String extractLineageType(MetadataRelationship rel) {
        if (rel.getRelationMetadata() == null || rel.getRelationMetadata().isBlank()) {
            return "MANUAL";
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(
                    rel.getRelationMetadata(), new TypeReference<>() {});
            Object lt = metadata.get("lineage_type");
            return lt != null ? lt.toString() : "MANUAL";
        } catch (Exception e) {
            return "MANUAL";
        }
    }

    /**
     * 获取当前登录用户名
     *
     * @return 当前用户名，如果获取失败则返回 "system"
     */
    private String getCurrentUsername() {
        try {
            return UserContext.currentUser().getUsername();
        } catch (Exception e) {
            return "system";
        }
    }

    /**
     * 组装 LineageEdgeVO 响应
     */
    private LineageEdgeVO buildLineageEdgeVO(MetadataRelationship rel, int derivedCount) {
        LineageEdgeVO vo = new LineageEdgeVO();
        vo.setRelationshipId(rel.getId());
        vo.setSourceEntityId(rel.getSourceId());
        vo.setTargetEntityId(rel.getTargetId());
        vo.setRelationType(rel.getRelationType());
        vo.setDerivedCount(derivedCount);
        vo.setCreatedAt(rel.getCreatedAt());

        // 解析 relation_metadata 填充 lineage_type、description、created_by
        if (rel.getRelationMetadata() != null && !rel.getRelationMetadata().isBlank()) {
            try {
                Map<String, Object> meta = objectMapper.readValue(
                        rel.getRelationMetadata(), new TypeReference<>() {});
                vo.setLineageType(meta.getOrDefault("lineage_type", "").toString());
                vo.setDescription((String) meta.getOrDefault("description", null));
                vo.setCreatedBy((String) meta.getOrDefault("created_by", null));
                vo.setRelationMetadata(meta);
            } catch (Exception e) {
                log.debug("解析 relation_metadata 失败 relId={}: {}", rel.getId(), e.getMessage());
            }
        }
        return vo;
    }
}
