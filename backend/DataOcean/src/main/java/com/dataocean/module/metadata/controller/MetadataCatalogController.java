package com.dataocean.module.metadata.controller;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.metadata.entity.dto.ConfirmMaskCandidateRequest;
import jakarta.validation.Valid;
import com.dataocean.module.system.aspect.AdminAuditLog;
import com.dataocean.module.audit.service.LineageEdgeService;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@AdminAuditLog(logReads = false)
public class MetadataCatalogController {

    /** 读取资产结构：负责源范围内的目录、快照与关系。 */
    private static final String VIEW_FUNCTION = "metadata:view";
    /** 血缘与影响分析：独立功能码，负责源范围内。 */
    private static final String LINEAGE_FUNCTION = "lineage:view";
    /** 字段治理写入：标签确认等。 */
    private static final String FIELD_MANAGE_FUNCTION = "governance:field:manage";
    /** 掩码候选：查看与确认分属两个功能码，都由 security 域负责源约束。 */
    private static final String MASK_VIEW_FUNCTION = "security:mask:view";
    private static final String MASK_MANAGE_FUNCTION = "security:mask:manage";

    private final MetadataEntityService entityService;
    private final MetadataRelationshipService relationshipService;
    private final LineageEdgeService lineageEdgeService;
    private final IamS1AdminGuard adminGuard;
    private final IamS1CapabilityService capabilityService;
    private final com.dataocean.module.metadata.service.MetadataMaskCandidateService maskCandidateService;

    /**
     * 校验调用者在指定功能上有权访问该实体所属的数据源。
     *
     * <p>元数据实体本身不带 datasourceId 字段，归属存在 `entity_metadata.datasource_id`，
     * 所以必须先解析出所属数据源再做负责源判定，不能只检查全局功能码。</p>
     */
    private void requireEntityScope(Long userId, Long entityId, String functionCode) {
        Long datasourceId = entityService.getDatasourceIdByEntityId(entityId);
        if (datasourceId == null) {
            throw new BusinessException(404, "实体不存在或没有数据源归属");
        }
        adminGuard.requireDatasourceFunction(userId, functionCode, datasourceId);
    }

    /** 调用者在指定功能上负责的数据源 ID。 */
    private List<Long> visibleDatasourceIds(Long userId, String functionCode) {
        return capabilityService.responsibleDatasourcesWithFunction(userId, functionCode)
                .stream()
                .map(IamS1DatasourceRefVO::id)
                .toList();
    }

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
        Long userId = UserContext.currentUserId();
        // 未指定数据源时按调用者的负责源范围收窄；指定时还要确认该源确实在范围内，
        // 否则会变成“按无权源的用途查询”，等于绕过范围过滤。
        List<Long> visible = visibleDatasourceIds(userId, VIEW_FUNCTION);
        if (datasourceId != null) {
            adminGuard.requireDatasourceFunction(userId, VIEW_FUNCTION, datasourceId);
            if (!visible.contains(datasourceId)) {
                return Result.success(List.of());
            }
            return Result.success(entityService.search(q, type, datasourceId, page, size));
        }
        return Result.success(entityService.searchScoped(q, type, visible, page, size));
    }

    /**
     * 获取实体详情（含关系）
     *
     * @param entityId 实体 ID
     * @return 实体详情和关系列表
     */
    @GetMapping("/entities/{entityId}")
    public Result<Map<String, Object>> getEntityDetail(@PathVariable Long entityId) {
        requireEntityScope(UserContext.currentUserId(), entityId, VIEW_FUNCTION);
        MetadataEntity entity = entityService.getById(entityId);
        if (entity == null) {
            return Result.error(404, "实体不存在");
        }

        // 关系另一侧可能属于调用者无负责权限的数据源：与血缘同样只返回两端都可见的边。
        Set<Long> detailEntityScope = entityService.getEntityIdsByDatasourceIds(
                visibleDatasourceIds(UserContext.currentUserId(), VIEW_FUNCTION));
        List<MetadataRelationship> outgoing = relationshipService.getBySource(entityId, entity.getEntityType())
                .stream()
                .filter(rel -> detailEntityScope.contains(rel.getSourceId())
                        && detailEntityScope.contains(rel.getTargetId()))
                .toList();
        List<MetadataRelationship> incoming = relationshipService.getByTarget(entityId, entity.getEntityType())
                .stream()
                .filter(rel -> detailEntityScope.contains(rel.getSourceId())
                        && detailEntityScope.contains(rel.getTargetId()))
                .toList();

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
        Long lineageUserId = UserContext.currentUserId();
        requireEntityScope(lineageUserId, entityId, LINEAGE_FUNCTION);
        List<Long> lineageScope = visibleDatasourceIds(lineageUserId, LINEAGE_FUNCTION);
        // 无过滤时保持原有行为（向后兼容）
        if (lineageType == null || lineageType.isBlank()) {
            // 血缘跨数据源：只返回两端都在负责范围内的边。
            List<MetadataRelationship> lineage = relationshipService.getLineage(entityId, lineageScope);
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

        // 委托给 LineageEdgeService 提供增强响应（含节点和列映射摘要）。
        // 可见范围在 BFS 的**遍历阶段**生效：只做返回前过滤会留下
        // “A 可见 → B 无权 → C 可见”这类孤立节点，等于泄露存在隐藏路径的拓扑信号。
        com.dataocean.module.audit.entity.vo.LineageGraphVO vo = lineageEdgeService.getEnrichedLineage(
                entityId, depth, lineageTypes, entityService.getEntityIdsByDatasourceIds(lineageScope));
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
        Long columnUserId = UserContext.currentUserId();
        requireEntityScope(columnUserId, columnId, LINEAGE_FUNCTION);
        MetadataEntity column = entityService.getById(columnId);
        if (column == null || !MetadataEntity.TYPE_COLUMN.equals(column.getEntityType())) {
            return Result.error(404, "列实体不存在");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columnId", column.getId());
        result.put("columnName", column.getName());
        result.put("fqn", column.getFqn());

        Set<Long> lineageEntityScope = entityService.getEntityIdsByDatasourceIds(
                visibleDatasourceIds(columnUserId, LINEAGE_FUNCTION));

        // 获取上游 DERIVED_FROM 链（该列是 target，即哪些源列派生出了它）
        if ("upstream".equals(direction) || "both".equals(direction)) {
            result.put("upstream",
                    traceDerivedFromChain(columnId, depth, true, new HashSet<>(), lineageEntityScope));
        }

        // 获取下游 DERIVED_FROM 链（该列是 source，即它派生出了哪些列）
        if ("downstream".equals(direction) || "both".equals(direction)) {
            result.put("downstream",
                    traceDerivedFromChain(columnId, depth, false, new HashSet<>(), lineageEntityScope));
        }

        return Result.success(result);
    }

    /**
     * BFS 遍历 DERIVED_FROM 链
     *
     * @param columnId   起始列实体 ID
     * @param depth      遍历深度
     * @param upstream   true=上游（查询入边），false=下游（查询出边）
     * @param visited    已访问节点集合（防止循环血缘导致无限递归）
     * @return 递归嵌套的列血缘链 [{"entity":..., "relationship":..., "children":[...]}, ...]
     */
    private List<Map<String, Object>> traceDerivedFromChain(Long columnId, int depth, boolean upstream,
                                                            Set<Long> visited, Set<Long> visibleEntityIds) {
        List<Map<String, Object>> chain = new ArrayList<>();
        if (depth <= 0 || visited.contains(columnId)) return chain;
        visited.add(columnId);

        List<MetadataRelationship> rels = upstream
                ? relationshipService.getByTarget(columnId, MetadataEntity.TYPE_COLUMN)
                : relationshipService.getBySource(columnId, MetadataEntity.TYPE_COLUMN);

        for (MetadataRelationship rel : rels) {
            if (!MetadataRelationship.TYPE_DERIVED_FROM.equals(rel.getRelationType())) continue;

            Long relatedColId = upstream ? rel.getSourceId() : rel.getTargetId();
            // 列级血缘同样是跨数据源的图：无权节点既不返回，也不继续向下递归，
            // 否则会用它当跳板把更深层的可见列也带出来。
            if (visibleEntityIds != null && !visibleEntityIds.contains(relatedColId)) {
                continue;
            }
            MetadataEntity relatedCol = entityService.getById(relatedColId);
            if (relatedCol == null) continue;

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("entity", relatedCol);
            node.put("relationship", rel);

            // 递归获取更深层
            List<Map<String, Object>> children =
                    traceDerivedFromChain(relatedColId, depth - 1, upstream, visited, visibleEntityIds);
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
        Long downstreamUserId = UserContext.currentUserId();
        requireEntityScope(downstreamUserId, entityId, LINEAGE_FUNCTION);
        List<MetadataRelationship> downstream = relationshipService.getDownstream(entityId, maxDepth,
                visibleDatasourceIds(downstreamUserId, LINEAGE_FUNCTION));
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
        adminGuard.requireDatasourceFunction(UserContext.currentUserId(), VIEW_FUNCTION, datasourceId);
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
        requireEntityScope(UserContext.currentUserId(), entityId, FIELD_MANAGE_FUNCTION);
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
        requireEntityScope(UserContext.currentUserId(), entityId, FIELD_MANAGE_FUNCTION);
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
        requireEntityScope(UserContext.currentUserId(), entityId, VIEW_FUNCTION);
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
        Long userId = UserContext.currentUserId();
        // 查找 COLUMN 实体中 pending_mask 非空的。
        // 未指定数据源时**不能**退化成全局扫描——原实现走 search("*", …, null, …)，
        // 任何持码者都能读到所有数据源的掩码候选。
        List<MetadataEntity> columns;
        if (datasourceId != null) {
            adminGuard.requireDatasourceFunction(userId, MASK_VIEW_FUNCTION, datasourceId);
            columns = entityService.getByDatasourceId(datasourceId);
        } else {
            columns = entityService.getByDatasourceIdsAndType(
                    visibleDatasourceIds(userId, MASK_VIEW_FUNCTION), MetadataEntity.TYPE_COLUMN);
        }

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
                } catch (Exception e) {
                    log.warn("掩码策略解析失败 entityId={}: {}", col.getId(), e.getMessage());
                }
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
            @Valid @RequestBody ConfirmMaskCandidateRequest body) {
        // S1 字段保护写入与候选标记清理在同一事务内完成，并做幂等处理：
        // 编排与幂等逻辑见 MetadataMaskCandidateService。
        Long userId = UserContext.currentUserId();
        requireEntityScope(userId, entityId, MASK_MANAGE_FUNCTION);
        maskCandidateService.confirm(userId, entityId, body.getMaskStrategy());
        return Result.success("字段保护已生效", null);
    }

    /**
     * 拒绝 MASK 策略候选
     */
    @PostMapping("/mask-candidates/{entityId}/reject")
    public Result<Void> rejectMaskCandidate(@PathVariable Long entityId) {
        requireEntityScope(UserContext.currentUserId(), entityId, MASK_MANAGE_FUNCTION);
        Long userId = UserContext.currentUserId();
        requireEntityScope(userId, entityId, MASK_MANAGE_FUNCTION);
        maskCandidateService.reject(userId, entityId);
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
