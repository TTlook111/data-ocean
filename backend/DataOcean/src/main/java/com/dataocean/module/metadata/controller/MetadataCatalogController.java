package com.dataocean.module.metadata.controller;

import com.dataocean.common.result.Result;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
public class MetadataCatalogController {

    private final MetadataEntityService entityService;
    private final MetadataRelationshipService relationshipService;
    private final DatasourceAccessPolicyMapper policyMapper;
    private final ApplicationEventPublisher eventPublisher;

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
     * 获取实体的血缘关系
     *
     * @param entityId 实体 ID
     * @return 血缘关系列表（上游 + 下游）
     */
    @GetMapping("/entities/{entityId}/lineage")
    public Result<List<MetadataRelationship>> getLineage(@PathVariable Long entityId) {
        List<MetadataRelationship> lineage = relationshipService.getLineage(entityId);
        return Result.success(lineage);
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
