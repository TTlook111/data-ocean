package com.dataocean.module.metadata.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.system.aspect.AdminAuditLog;
import com.dataocean.module.audit.service.LineageEdgeService;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import com.dataocean.module.permission.entity.DatasourceAccessPolicy;
import com.dataocean.module.permission.event.PermissionChangedEvent;
import com.dataocean.module.permission.mapper.DatasourceAccessPolicyMapper;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 元数据目录控制器
 * <p>
 * 提供实体搜索、血缘查询、影响分析等 API。
 * </p>
 *
 * @author dataocean
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/catalog")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('metadata:manage', '*')")
@AdminAuditLog(logReads = false)
public class MetadataCatalogController {

    private final MetadataEntityService entityService;
    private final MetadataRelationshipService relationshipService;
    private final DatasourceAccessPolicyMapper policyMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final LineageEdgeService lineageEdgeService;

    /**
     * 全文搜索实体
     *
     * @param q            搜索关键词
     * @param type         实体类型过滤（可选：TABLE / COLUMN / GLOSSARY_TERM / TAG）
     * @param datasourceId 数据源 ID 过滤（可选）
     * @param page         页码（默认 1）
     * @param size         每页大小（默认 20）
     * @return 搜索结果
     */
    @GetMapping("/search")
    public Result<List<MetadataEntity>> search(
            @RequestParam String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<MetadataEntity> results = entityService.search(q, type, page, size);
        return Result.success(results);
    }

    /**
     * 获取实体详情（含关系）
     *
     * @param entityId 实体 ID
     * @return 实体详情和关系列表
     */
    @GetMapping("/entities/{entityId}")
    public Result<Map<String, Object>> getEntityDetail(@PathVariable Long entityId) {
        MetadataEntity entity = entityService.getById(entityId);
        if (entity == null) {
            return Result.error(404, "实体不存在");
        }

        List<MetadataRelationship> outgoing = relationshipService.getBySource(entityId, entity.getEntityType());
        List<MetadataRelationship> incoming = relationshipService.getByTarget(entityId, entity.getEntityType());

        Map<String, Object> result = new HashMap<>();
        result.put("entity", entity);
        result.put("outgoingRelations", outgoing);
        result.put("incomingRelations", incoming);
        return Result.success(result);
    }

    /**
     * 获取实体的血缘关系（增强版）
     * <p>
     * 支持按血缘类型过滤（逗号分隔）和深度控制。
     * 启用过滤/深度时返回增强图谱（含节点、边、列映射摘要），
     * 类型为 com.dataocean.module.audit.entity.vo.LineageGraphVO；
     * 未提供过滤参数时保持原有行为（仅返回该实体的一层 LINEAGE 边列表）。
     * </p>
     *
     * @param entityId    实体 ID
     * @param depth       图谱 BFS 深度（默认 1）
     * @param lineageType 血缘类型过滤，逗号分隔（QUERY,ETL,MANUAL），可选
     * @return 血缘关系列表或增强图谱
     */
    @GetMapping("/entities/{entityId}/lineage")
    public Result<?> getLineage(
            @PathVariable Long entityId,
            @RequestParam(defaultValue = "1") int depth,
            @RequestParam(required = false) String lineageType) {
        // 无过滤时保持原有行为（向后兼容）
        if (lineageType == null || lineageType.isBlank()) {
            List<MetadataRelationship> lineage = relationshipService.getLineage(entityId);
            return Result.success(lineage);
        }

        // 解析血缘类型过滤集合
        Set<String> lineageTypes = new HashSet<>();
        for (String type : lineageType.split(",")) {
            String trimmed = type.trim().toUpperCase();
            if (!trimmed.isEmpty()) {
                lineageTypes.add(trimmed);
            }
        }

        // 委托给 LineageEdgeService 提供增强响应（含节点和列映射摘要）
        com.dataocean.module.audit.entity.vo.LineageGraphVO vo =
                lineageEdgeService.getEnrichedLineage(entityId, depth, lineageTypes);
        return Result.success(vo);
    }

    /**
     * 获取列级血缘（DERIVED_FROM 上下游链）
     * <p>
     * 返回该列的上游（来源）和下游（影响）的 DERIVED_FROM 链。
     * 参考 Marquez GET /api/v1/column-lineage/{nodeId}?depth=N 设计。
     * </p>
     *
     * @param columnId  列实体 ID
     * @param depth     遍历深度（默认 3）
     * @param direction 方向：upstream（仅上游）、downstream（仅下游）、both（双向，默认）
     * @return 列级血缘链
     */
    @GetMapping("/entities/{columnId}/column-lineage")
    public Result<Map<String, Object>> getColumnLineage(
            @PathVariable Long columnId,
            @RequestParam(defaultValue = "3") int depth,
            @RequestParam(defaultValue = "both") String direction) {
        MetadataEntity column = entityService.getById(columnId);
        if (column == null || !MetadataEntity.TYPE_COLUMN.equals(column.getEntityType())) {
            return Result.error(404, "列实体不存在");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columnId", column.getId());
        result.put("columnName", column.getName());
        result.put("fqn", column.getFqn());

        // 获取上游 DERIVED_FROM 链（该列是 target，即哪些源列派生出了它）
        if ("upstream".equals(direction) || "both".equals(direction)) {
            result.put("upstream", traceDerivedFromChain(columnId, depth, true));
        }

        // 获取下游 DERIVED_FROM 链（该列是 source，即它派生出了哪些列）
        if ("downstream".equals(direction) || "both".equals(direction)) {
            result.put("downstream", traceDerivedFromChain(columnId, depth, false));
        }

        return Result.success(result);
    }

    /**
     * BFS 遍历 DERIVED_FROM 链
     *
     * @param columnId   起始列实体 ID
     * @param depth      遍历深度
     * @param upstream   true=上游（查询入边），false=下游（查询出边）
     * @return 递归嵌套的列血缘链 [{"entity":..., "relationship":..., "children":[...]}, ...]
     */
    private List<Map<String, Object>> traceDerivedFromChain(Long columnId, int depth, boolean upstream) {
        List<Map<String, Object>> chain = new ArrayList<>();
        if (depth <= 0) return chain;

        Set<Long> nextIds = new HashSet<>();
        List<MetadataRelationship> rels = upstream
                ? relationshipService.getByTarget(columnId, MetadataEntity.TYPE_COLUMN)
                : relationshipService.getBySource(columnId, MetadataEntity.TYPE_COLUMN);

        for (MetadataRelationship rel : rels) {
            if (!MetadataRelationship.TYPE_DERIVED_FROM.equals(rel.getRelationType())) continue;

            Long relatedColId = upstream ? rel.getSourceId() : rel.getTargetId();
            MetadataEntity relatedCol = entityService.getById(relatedColId);
            if (relatedCol == null) continue;

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("entity", relatedCol);
            node.put("relationship", rel);

            // 递归获取更深层
            List<Map<String, Object>> children = traceDerivedFromChain(relatedColId, depth - 1, upstream);
            if (!children.isEmpty()) {
                node.put("children", children);
            }
            chain.add(node);
        }
        return chain;
    }

    /**
     * 获取实体的下游影响分析
     * <p>
     * 递归查找所有受该实体影响的下游依赖（最大深度 10 层）。
     * </p>
     *
     * @param entityId  实体 ID
     * @param maxDepth  最大递归深度（默认 10）
     * @return 下游关系列表
     */
    @GetMapping("/entities/{entityId}/downstream")
    public Result<List<MetadataRelationship>> getDownstream(
            @PathVariable Long entityId,
            @RequestParam(defaultValue = "10") int maxDepth) {
        List<MetadataRelationship> downstream = relationshipService.getDownstream(entityId, maxDepth);
        return Result.success(downstream);
    }

    /**
     * 按数据源获取所有实体
     *
     * @param datasourceId 数据源 ID
     * @return 实体列表
     */
    @GetMapping("/entities")
    public Result<List<MetadataEntity>> getEntitiesByDatasource(
            @RequestParam Long datasourceId) {
        List<MetadataEntity> entities = entityService.getByDatasourceId(datasourceId);
        return Result.success(entities);
    }

    // ========== 标签确认 API ==========

    /**
     * 确认标签候选，创建 TAGGED_WITH 关系
     *
     * @param entityId 实体 ID（列实体）
     * @param body     { "tagFqn": "PII.手机号" }
     */
    @PostMapping("/entities/{entityId}/confirm-tag")
    public Result<Void> confirmTag(
            @PathVariable Long entityId,
            @RequestBody Map<String, String> body) {
        String tagFqn = body.get("tagFqn");
        if (tagFqn == null || tagFqn.isBlank()) {
            return Result.error(400, "tagFqn 不能为空");
        }

        MetadataEntity entity = entityService.getById(entityId);
        if (entity == null) {
            return Result.error(404, "实体不存在");
        }

        // 查找或创建标签实体
        MetadataEntity tagEntity = entityService.getByFqn("tag." + tagFqn.toLowerCase());
        if (tagEntity == null) {
            // 标签实体不存在，创建一个
            tagEntity = new MetadataEntity();
            tagEntity.setEntityType(MetadataEntity.TYPE_TAG);
            tagEntity.setEntityUuid(java.util.UUID.randomUUID().toString());
            tagEntity.setFqn("tag." + tagFqn.toLowerCase());
            tagEntity.setName(tagFqn);
            tagEntity.setDisplayName(tagFqn);
            tagEntity = entityService.upsert(tagEntity);
        }

        // 创建 TAGGED_WITH 关系
        MetadataRelationship rel = new MetadataRelationship();
        rel.setSourceId(entityId);
        rel.setSourceType(entity.getEntityType());
        rel.setTargetId(tagEntity.getId());
        rel.setTargetType(MetadataEntity.TYPE_TAG);
        rel.setRelationType(MetadataRelationship.TYPE_TAGGED_WITH);
        relationshipService.upsert(rel);

        // PII 标签联动：自动生成 MASK 策略候选
        if (tagFqn.startsWith("PII.") && MetadataEntity.TYPE_COLUMN.equals(entity.getEntityType())) {
            generateMaskPolicyCandidate(entity, tagFqn);
        }

        return Result.success("标签确认成功", null);
    }

    /**
     * 取消标签关联
     */
    @DeleteMapping("/entities/{entityId}/unconfirm-tag/{tagFqn}")
    public Result<Void> unconfirmTag(
            @PathVariable Long entityId,
            @PathVariable String tagFqn) {
        MetadataEntity tagEntity = entityService.getByFqn("tag." + tagFqn.toLowerCase());
        if (tagEntity == null) {
            return Result.error(404, "标签不存在");
        }

        var rels = relationshipService.getBySource(entityId, null);
        for (MetadataRelationship rel : rels) {
            if (MetadataRelationship.TYPE_TAGGED_WITH.equals(rel.getRelationType())
                    && rel.getTargetId().equals(tagEntity.getId())) {
                relationshipService.removeById(rel.getId());
                break;
            }
        }
        return Result.success("已取消标签", null);
    }

    /**
     * 查询实体的已确认标签
     */
    @GetMapping("/entities/{entityId}/tags")
    public Result<List<MetadataEntity>> getEntityTags(@PathVariable Long entityId) {
        var rels = relationshipService.getBySource(entityId, null);
        List<MetadataEntity> tags = new java.util.ArrayList<>();
        for (MetadataRelationship rel : rels) {
            if (MetadataRelationship.TYPE_TAGGED_WITH.equals(rel.getRelationType())) {
                MetadataEntity tag = entityService.getById(rel.getTargetId());
                if (tag != null) tags.add(tag);
            }
        }
        return Result.success(tags);
    }

    // ========== PII 标签 → MASK 策略联动 ==========

    /**
     * 查询待确认的 MASK 策略候选（由 PII 标签确认自动生成）
     *
     * @param datasourceId 数据源 ID（可选）
     */
    @GetMapping("/mask-candidates")
    public Result<List<Map<String, Object>>> getMaskCandidates(
            @RequestParam(required = false) Long datasourceId) {
        // 查找所有 COLUMN 实体中 pending_mask 非空的
        List<MetadataEntity> columns = datasourceId != null
                ? entityService.getByDatasourceId(datasourceId)
                : entityService.search("*", MetadataEntity.TYPE_COLUMN, 1, 1000);

        List<Map<String, Object>> candidates = new java.util.ArrayList<>();
        for (MetadataEntity col : columns) {
            if (!MetadataEntity.TYPE_COLUMN.equals(col.getEntityType())) continue;
            String meta = col.getEntityMetadata();
            if (meta != null && meta.contains("\"pending_mask\"")) {
                Map<String, Object> item = new HashMap<>();
                item.put("entityId", col.getId());
                item.put("fqn", col.getFqn());
                item.put("name", col.getName());
                item.put("displayName", col.getDisplayName());
                // 提取 pending_mask 信息
                try {
                    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                    var node = om.readTree(meta);
                    if (node.has("pending_mask")) {
                        item.put("pendingMask", om.treeToValue(node.get("pending_mask"), Map.class));
                    }
                } catch (Exception ignored) {}
                candidates.add(item);
            }
        }
        return Result.success(candidates);
    }

    /**
     * 确认 MASK 策略候选，生成实际的 MASK 策略
     *
     * @param entityId 列实体 ID
     * @param body     { "maskStrategy": "PHONE" }
     */
    @PostMapping("/mask-candidates/{entityId}/confirm")
    public Result<Void> confirmMaskCandidate(
            @PathVariable Long entityId,
            @RequestBody Map<String, String> body) {
        String maskStrategy = body.getOrDefault("maskStrategy", "PHONE");

        MetadataEntity entity = entityService.getById(entityId);
        if (entity == null) {
            return Result.error(404, "实体不存在");
        }

        // 从 entity_metadata 解析表名和列名
        String tableName = null;
        String columnName = entity.getName();
        Long datasourceId = null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = om.readTree(entity.getEntityMetadata());
            if (node.has("datasource_id")) datasourceId = node.get("datasource_id").asLong();
        } catch (Exception ignored) {}

        // 从 FQN 解析表名：datasource.db.table.column → table
        String fqn = entity.getFqn();
        String[] parts = fqn.split("\\.");
        if (parts.length >= 3) {
            tableName = parts[parts.length - 2];
        }

        if (datasourceId == null || tableName == null) {
            return Result.error(400, "无法从实体元数据解析数据源或表名");
        }

        // 检查是否已有 MASK 策略
        var existingPolicies = policyMapper.selectBySubject(datasourceId, "USER", 0L); // 检查全局策略
        for (DatasourceAccessPolicy p : existingPolicies) {
            if ("MASK".equals(p.getAccessType())
                    && tableName.equals(p.getTableName())
                    && columnName.equals(p.getColumnName())) {
                return Result.error(400, "该列已有 MASK 策略");
            }
        }

        // 创建 MASK 策略（全局，subjectType=USER, subjectId=0 表示所有用户）
        DatasourceAccessPolicy policy = new DatasourceAccessPolicy();
        policy.setDatasourceId(datasourceId);
        policy.setSubjectType("USER");
        policy.setSubjectId(0L);
        policy.setTableName(tableName);
        policy.setColumnName(columnName);
        policy.setAccessType("MASK");
        policy.setMaskStrategy(maskStrategy);
        policy.setPriority(50); // PII 标签生成的策略优先级高
        policyMapper.insert(policy);

        // 清除 entity_metadata 中的 pending_mask
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = om.readTree(entity.getEntityMetadata());
            if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode objNode) {
                objNode.remove("pending_mask");
                entity.setEntityMetadata(om.writeValueAsString(objNode));
                entityService.updateById(entity);
            }
        } catch (Exception ignored) {}

        // 触发权限缓存失效
        eventPublisher.publishEvent(new PermissionChangedEvent(this, 0L, datasourceId));

        return Result.success("MASK 策略已确认生效", null);
    }

    /**
     * 拒绝 MASK 策略候选
     */
    @PostMapping("/mask-candidates/{entityId}/reject")
    public Result<Void> rejectMaskCandidate(@PathVariable Long entityId) {
        MetadataEntity entity = entityService.getById(entityId);
        if (entity == null) {
            return Result.error(404, "实体不存在");
        }
        // 清除 pending_mask
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = om.readTree(entity.getEntityMetadata());
            if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode objNode) {
                objNode.remove("pending_mask");
                entity.setEntityMetadata(om.writeValueAsString(objNode));
                entityService.updateById(entity);
            }
        } catch (Exception ignored) {}
        return Result.success("已拒绝 MASK 策略候选", null);
    }

    /**
     * 为 PII 标签生成 MASK 策略候选
     */
    private void generateMaskPolicyCandidate(MetadataEntity entity, String tagFqn) {
        // 根据 PII 标签类型推断脱敏策略
        String maskStrategy = switch (tagFqn) {
            case "PII.手机号" -> "PHONE";
            case "PII.身份证号" -> "ID_CARD";
            case "PII.邮箱" -> "EMAIL";
            case "PII.银行卡号" -> "BANK_CARD";
            case "PII.姓名" -> "NAME";
            default -> "PHONE"; // 默认手机号脱敏
        };

        // 在 entity_metadata 中添加 pending_mask
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = om.readTree(entity.getEntityMetadata());
            if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode objNode) {
                var maskNode = om.createObjectNode();
                maskNode.put("tag_fqn", tagFqn);
                maskNode.put("mask_strategy", maskStrategy);
                maskNode.put("status", "PENDING");
                maskNode.put("created_at", java.time.LocalDateTime.now().toString());
                objNode.set("pending_mask", maskNode);
                entity.setEntityMetadata(om.writeValueAsString(objNode));
                entityService.updateById(entity);
                log.info("PII 标签确认，生成 MASK 策略候选 entityId={} tag={} strategy={}",
                        entity.getId(), tagFqn, maskStrategy);
            }
        } catch (Exception e) {
            log.warn("生成 MASK 策略候选失败 entityId={} error={}", entity.getId(), e.getMessage());
        }
    }
}
