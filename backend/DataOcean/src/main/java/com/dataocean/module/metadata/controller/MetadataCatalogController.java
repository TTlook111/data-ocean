package com.dataocean.module.metadata.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import lombok.RequiredArgsConstructor;
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
@RestController
@RequestMapping("/api/admin/catalog")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('metadata:manage', '*')")
public class MetadataCatalogController {

    private final MetadataEntityService entityService;
    private final MetadataRelationshipService relationshipService;

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
}
