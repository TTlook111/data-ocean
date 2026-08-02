package com.dataocean.module.metadata.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 元数据内部 API 控制器（供 Python Agent 消费）
 * <p>
 * Phase 3：为 Schema Linking 提供实体关系数据（FOREIGN_KEY / LINEAGE / DERIVED_FROM），
 * 使 RAG 能推荐 JOIN 条件、表关联和字段派生解释。
 * 通过 X-Internal-Token 请求头校验内部调用身份。
 * </p>
 *
 * @author dataocean
 */
@RestController
@RequestMapping("/internal/metadata")
@RequiredArgsConstructor
@Slf4j
public class InternalMetadataController {

    private final MetadataEntityService entityService;
    private final MetadataRelationshipService relationshipService;

    @Value("${dataocean.internal.token:dataocean-internal-default}")
    private String internalToken;

    /**
     * 校验内部调用 token，防止外部未授权访问。
     */
    private void requireInternal(HttpServletRequest request) {
        String token = request.getHeader("X-Internal-Token");
        if (token == null || !token.equals(internalToken)) {
            throw new RuntimeException("未授权的内部 API 调用");
        }
    }

    /**
     * 获取实体相关的所有关系（供 Python Schema Linking 消费）
     * <p>
     * 返回该实体关联的指定类型关系，每条关系附带关联实体的基本信息。
     * Python 侧根据 relationType 判断用途：
     * - FOREIGN_KEY → 推荐 JOIN 条件（格式：Table A JOIN Table B ON A.col = B.col）
     * - LINEAGE     → 推荐上游/下游表（如"订单数据从哪里来"）
     * - DERIVED_FROM → 解释字段计算逻辑（如"total_amount 是怎么算的"）
     * </p>
     *
     * @param entityId     实体 ID
     * @param relationType 关系类型过滤（逗号分隔），如 FOREIGN_KEY,LINEAGE,DERIVED_FROM
     * @return 关系列表（含关联实体）
     */
    @GetMapping("/entities/{entityId}/relationships")
    public Result<List<Map<String, Object>>> getEntityRelationships(
            @PathVariable Long entityId,
            @RequestParam(required = false) String relationType,
            HttpServletRequest request) {
        requireInternal(request);

        MetadataEntity entity = entityService.getById(entityId);
        if (entity == null) {
            return Result.error(404, "实体不存在");
        }

        // 解析关系类型过滤
        Set<String> typeFilter = new HashSet<>();
        if (relationType != null && !relationType.isBlank()) {
            for (String type : relationType.split(",")) {
                String trimmed = type.trim().toUpperCase();
                if (!trimmed.isEmpty()) typeFilter.add(trimmed);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();

        // 查询出向关系（该实体是源）
        List<MetadataRelationship> outgoing = relationshipService.getBySource(entityId, entity.getEntityType());
        for (MetadataRelationship rel : outgoing) {
            if (!typeFilter.isEmpty() && !typeFilter.contains(rel.getRelationType())) continue;
            result.add(buildRelationMap(rel, "outgoing"));
        }

        // 查询入向关系（该实体是目标）
        List<MetadataRelationship> incoming = relationshipService.getByTarget(entityId, entity.getEntityType());
        for (MetadataRelationship rel : incoming) {
            if (!typeFilter.isEmpty() && !typeFilter.contains(rel.getRelationType())) continue;
            result.add(buildRelationMap(rel, "incoming"));
        }

        return Result.success(result);
    }

    /**
     * 构建关系响应 map，包含关联实体的基本信息。
     */
    private Map<String, Object> buildRelationMap(MetadataRelationship rel, String direction) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("relationshipId", rel.getId());
        map.put("relationType", rel.getRelationType());
        map.put("direction", direction);
        map.put("relationMetadata", rel.getRelationMetadata());
        map.put("createdAt", rel.getCreatedAt());

        // 源实体信息
        MetadataEntity source = entityService.getById(rel.getSourceId());
        if (source != null) {
            map.put("sourceEntity", buildEntitySummary(source));
        }

        // 目标实体信息
        MetadataEntity target = entityService.getById(rel.getTargetId());
        if (target != null) {
            map.put("targetEntity", buildEntitySummary(target));
        }

        return map;
    }

    /**
     * 构建实体摘要（仅含基本信息，不返回全部字段以减少序列化开销）。
     */
    private Map<String, Object> buildEntitySummary(MetadataEntity entity) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", entity.getId());
        summary.put("entityType", entity.getEntityType());
        summary.put("name", entity.getName());
        summary.put("displayName", entity.getDisplayName());
        summary.put("fqn", entity.getFqn());
        summary.put("description", entity.getDescription());
        return summary;
    }
}
