package com.dataocean.module.glossary.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.glossary.dto.TermLinkColumnDTO;
import com.dataocean.module.glossary.dto.TermReviewDTO;
import com.dataocean.module.glossary.entity.Glossary;
import com.dataocean.module.glossary.entity.GlossaryTerm;
import com.dataocean.module.glossary.service.GlossaryScopeService;
import com.dataocean.module.glossary.service.GlossaryService;
import com.dataocean.module.glossary.service.GlossaryTermService;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import com.dataocean.module.permission.s1.annotation.IamS1ScopedList;
import com.dataocean.module.system.aspect.AdminAuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务术语管理控制器。
 *
 * <p>准入使用 IAM-SIMPLE-1 方法级注解。{@code glossary:view} / {@code glossary:manage} /
 * {@code glossary:approve} 是 B0 的「源/全」混合码，因此这里统一用
 * {@link IamS1ScopedList} 做**功能级准入**，动态范围（术语关联了哪些源、调用者是否负责）
 * 由 {@link GlossaryScopeService} 按批次 5 定稿规则落实：</p>
 *
 * <ul>
 *   <li>术语/术语表未关联任何数据源 → 只校验功能，不推导任何数据源权限；</li>
 *   <li>已关联 → 查看只返回负责源内的关联字段，写操作对全部关联源逐源校验、任一无权整体拒绝；</li>
 *   <li>关联/解除字段还要校验**目标实体**的真实数据源，不采信前端传入的任何数据源。</li>
 * </ul>
 *
 * <p>术语表（{@code glossary}）自身不携带数据源归属，所以它的创建/更新/删除按“其下术语关联的源”
 * 汇总校验；列表只返回术语表基本信息（名称、描述、状态），不泄露任何字段名、FQN、实体 ID 或关联关系。</p>
 */
@RestController
@RequestMapping("/api/admin/glossary")
@RequiredArgsConstructor
@AdminAuditLog
public class GlossaryController {

    /** 查看术语、术语表与关联字段。 */
    private static final String VIEW_FUNCTION = "glossary:view";
    /** 创建、修改、删除、提交审核、退回草稿、关联/解除字段。 */
    private static final String MANAGE_FUNCTION = "glossary:manage";
    /** 审核通过/驳回：独立功能码，不自动带来维护权，也不带来业务查询权。 */
    private static final String APPROVE_FUNCTION = "glossary:approve";

    private final GlossaryService glossaryService;
    private final GlossaryTermService termService;
    private final GlossaryScopeService scopeService;
    private final MetadataEntityService entityService;
    private final MetadataRelationshipService relationshipService;

    // ========== 术语表 CRUD ==========

    /**
     * 查询术语表列表。
     *
     * <p>术语表本身没有数据源归属，列表项只有名称、描述、状态等信息，
     * 不含字段名、FQN、实体 ID 或关联关系，因此不按源裁剪；作用域规则作用在
     * 术语与关联字段上（见 {@code /{glossaryId}/terms} 与 {@code /terms/{termId}/linked-columns}）。</p>
     */
    @GetMapping
    @IamS1ScopedList(VIEW_FUNCTION)
    public Result<List<Glossary>> listGlossaries() {
        List<Glossary> list = glossaryService.list(
                new LambdaQueryWrapper<Glossary>().orderByDesc(Glossary::getCreatedAt));
        return Result.success(list);
    }

    /** 创建术语表（新建时未关联任何数据源，只校验功能）。 */
    @PostMapping
    @IamS1ScopedList(MANAGE_FUNCTION)
    public Result<Map<String, Long>> createGlossary(@RequestBody Glossary glossary) {
        glossary.setOwnerId(UserContext.currentUserId());
        if (glossary.getStatus() == null) {
            glossary.setStatus(Glossary.STATUS_DRAFT);
        }
        glossaryService.save(glossary);
        return Result.success("术语表创建成功", Map.of("id", glossary.getId()));
    }

    /** 更新术语表：按其下全部术语关联的数据源逐源校验。 */
    @PutMapping("/{id}")
    @IamS1ScopedList(MANAGE_FUNCTION)
    public Result<Void> updateGlossary(@PathVariable Long id, @RequestBody Glossary glossary) {
        Glossary existing = glossaryService.getById(id);
        if (existing == null) {
            return Result.error(404, "术语表不存在");
        }
        requireGlossaryManageable(id);
        glossary.setId(id);
        glossaryService.updateById(glossary);
        return Result.success("术语表更新成功", null);
    }

    /** 删除术语表：先逐源校验，再执行现有非空/状态/关联保护。 */
    @DeleteMapping("/{id}")
    @IamS1ScopedList(MANAGE_FUNCTION)
    public Result<Void> deleteGlossary(@PathVariable Long id) {
        Glossary existing = glossaryService.getById(id);
        if (existing == null) {
            return Result.error(404, "术语表不存在");
        }
        requireGlossaryManageable(id);
        int removedTerms = glossaryService.deleteGlossary(id);
        return Result.success("术语表已删除，同时清理 " + removedTerms + " 个术语", null);
    }

    // ========== 术语条目 CRUD ==========

    /**
     * 查询术语列表。
     *
     * <p>已绑定术语至少要有一个可见关联源才返回；未绑定术语继续按全局语义显示。
     * 判定所需的可见源集合与术语关联源集合各查一次，不按术语逐条打库。</p>
     */
    @GetMapping("/{glossaryId}/terms")
    @IamS1ScopedList(VIEW_FUNCTION)
    public Result<List<GlossaryTerm>> listTerms(
            @PathVariable Long glossaryId,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<GlossaryTerm> qw = new LambdaQueryWrapper<GlossaryTerm>()
                .eq(GlossaryTerm::getGlossaryId, glossaryId)
                .eq(status != null, GlossaryTerm::getStatus, status)
                .orderByAsc(GlossaryTerm::getName);
        List<GlossaryTerm> terms = termService.list(qw);
        if (terms == null || terms.isEmpty()) {
            return Result.success(List.of());
        }
        Set<Long> visibleDatasourceIds = scopeService.visibleDatasourceIds(
                UserContext.currentUserId(), VIEW_FUNCTION);
        Map<Long, GlossaryScopeService.Scope> scopes = scopeService.termScopes(
                terms.stream().map(GlossaryTerm::getId).toList());
        List<GlossaryTerm> visible = new ArrayList<>();
        for (GlossaryTerm term : terms) {
            // BROKEN（存在关联但归属损坏）不返回：它与“未绑定”是两种状态，
            // 不能因为解析不出源就当成合法未绑定返回。
            if (GlossaryScopeService.visibleIn(scopes.get(term.getId()), visibleDatasourceIds)) {
                visible.add(term);
            }
        }
        return Result.success(visible);
    }

    /** 创建术语（新术语未关联任何数据源，只校验功能，不推导数据源权限）。 */
    @PostMapping("/{glossaryId}/terms")
    @IamS1ScopedList(MANAGE_FUNCTION)
    public Result<Map<String, Long>> createTerm(
            @PathVariable Long glossaryId,
            @RequestBody GlossaryTerm term) {
        term.setGlossaryId(glossaryId);
        GlossaryTerm created = termService.createTerm(term);
        return Result.success("术语创建成功", Map.of("id", created.getId()));
    }

    /**
     * 更新术语。
     * <p>
     * 只允许修改 DRAFT / REJECTED 状态的术语；请求体只被采纳内容字段，
     * 无法借由此接口改写状态与审核记录。上述约束在 Service 内实现。
     * </p>
     */
    @PutMapping("/terms/{termId}")
    @IamS1ScopedList(MANAGE_FUNCTION)
    public Result<Void> updateTerm(@PathVariable Long termId, @RequestBody GlossaryTerm term) {
        requireTermFunction(termId, MANAGE_FUNCTION);
        termService.updateTerm(termId, term);
        return Result.success("术语更新成功", null);
    }

    /** 删除术语（清理关联关系与悬空的父术语引用）。 */
    @DeleteMapping("/terms/{termId}")
    @IamS1ScopedList(MANAGE_FUNCTION)
    public Result<Void> deleteTerm(@PathVariable Long termId) {
        requireTermFunction(termId, MANAGE_FUNCTION);
        termService.deleteTerm(termId);
        return Result.success("术语已删除", null);
    }

    // ========== 审核流程 ==========

    /** 提交术语审核。 */
    @PostMapping("/terms/{termId}/submit")
    @IamS1ScopedList(MANAGE_FUNCTION)
    public Result<Void> submitForReview(@PathVariable Long termId) {
        requireTermFunction(termId, MANAGE_FUNCTION);
        termService.submitForReview(termId);
        return Result.success("术语已提交审核", null);
    }

    /**
     * 审核术语。
     *
     * <p>使用独立的 {@code glossary:approve}：审核者不会因此获得维护权，
     * 也不会获得该数据源上的业务查询权。</p>
     */
    @PostMapping("/terms/{termId}/review")
    @IamS1ScopedList(APPROVE_FUNCTION)
    public Result<Void> reviewTerm(
            @PathVariable Long termId,
            @RequestBody TermReviewDTO request) {
        requireTermFunction(termId, APPROVE_FUNCTION);
        termService.reviewTerm(termId, UserContext.currentUserId(), request.isApproved(), request.getReason());
        return Result.success(request.isApproved() ? "术语审核通过" : "术语审核拒绝", null);
    }

    /**
     * 把已通过的术语退回草稿。
     * <p>
     * 状态机原先从 APPROVED 没有出边，已通过的术语没有合法修改路径，
     * 而 {@code updateTerm} 又完全不校验状态，形成「合规流程被限制、绕过路径不受限」的倒挂。
     * 本接口补上合规路径：退回草稿 → 修改 → 重新提交审核。
     * </p>
     */
    @PostMapping("/terms/{termId}/revert")
    @IamS1ScopedList(MANAGE_FUNCTION)
    public Result<Void> revertTermToDraft(@PathVariable Long termId) {
        requireTermFunction(termId, MANAGE_FUNCTION);
        termService.revertToDraft(termId);
        return Result.success("术语已退回草稿，修改后需重新提交审核", null);
    }

    // ========== 术语与列关联 ==========

    /**
     * 关联术语与物理列（创建 GLOSSARY_OF 关系）。
     *
     * <p>两侧都要校验：术语**当前**关联的全部数据源，以及目标实体的**真实**数据源
     * （由实体元数据解析，不采信前端传入）。任意一侧无权即整体拒绝，且不得先写后校验。</p>
     */
    @PostMapping("/terms/{termId}/link-column")
    @IamS1ScopedList(MANAGE_FUNCTION)
    public Result<Void> linkTermToColumn(
            @PathVariable Long termId,
            @RequestBody TermLinkColumnDTO request) {
        Long entityId = request.getEntityId();
        MetadataEntity entity = entityService.getById(entityId);
        if (entity == null) {
            return Result.error(404, "实体不存在");
        }
        // 术语只能关联到**物理列**：任何带数据源归属的实体（表、库、数据源本身）都能建
        // GLOSSARY_OF，会让“术语 → 字段”的语义被稀释，也让按列做的字段级判定失去前提。
        if (!MetadataEntity.TYPE_COLUMN.equals(entity.getEntityType())) {
            return Result.error(400, "术语只能关联物理列实体，当前实体类型：" + entity.getEntityType());
        }
        // 先术语现有源、再目标实体真实源；两侧都通过才写入。
        requireTermFunction(termId, MANAGE_FUNCTION);
        requireEntityFunction(entityId, MANAGE_FUNCTION);

        MetadataRelationship rel = new MetadataRelationship();
        rel.setSourceId(termId);
        rel.setSourceType("GLOSSARY_TERM");
        rel.setTargetId(entityId);
        rel.setTargetType(entity.getEntityType());
        rel.setRelationType(MetadataRelationship.TYPE_GLOSSARY_OF);
        relationshipService.upsert(rel);

        return Result.success("关联成功", null);
    }

    /**
     * 取消术语与列的关联。
     *
     * <p>与关联对称：校验术语当前的关联源与被解除实体的真实数据源。</p>
     */
    @DeleteMapping("/terms/{termId}/unlink-column/{entityId}")
    @IamS1ScopedList(MANAGE_FUNCTION)
    public Result<Void> unlinkTermFromColumn(
            @PathVariable Long termId,
            @PathVariable Long entityId) {
        if (termService.getById(termId) == null) {
            return Result.error(404, "术语不存在");
        }
        if (entityService.getById(entityId) == null) {
            return Result.error(404, "实体不存在");
        }
        requireTermFunction(termId, MANAGE_FUNCTION);
        requireEntityFunction(entityId, MANAGE_FUNCTION);

        var rels = relationshipService.getBySource(termId, "GLOSSARY_TERM");
        for (MetadataRelationship rel : rels) {
            if (MetadataRelationship.TYPE_GLOSSARY_OF.equals(rel.getRelationType())
                    && rel.getTargetId().equals(entityId)) {
                relationshipService.removeById(rel.getId());
                break;
            }
        }
        return Result.success("已取消关联", null);
    }

    /**
     * 查询术语关联的物理列。
     *
     * <p>只返回调用者在 {@code glossary:view} 下负责源内的关联字段；归属解析不出来的实体
     * fail-closed（不返回），避免泄露无权源的字段名、FQN 或实体 ID。</p>
     */
    @GetMapping("/terms/{termId}/linked-columns")
    @IamS1ScopedList(VIEW_FUNCTION)
    public Result<List<MetadataEntity>> getLinkedColumns(@PathVariable Long termId) {
        var rels = relationshipService.getBySource(termId, "GLOSSARY_TERM");
        List<MetadataEntity> entities = new ArrayList<>();
        for (MetadataRelationship rel : rels) {
            if (MetadataRelationship.TYPE_GLOSSARY_OF.equals(rel.getRelationType())) {
                MetadataEntity entity = entityService.getById(rel.getTargetId());
                if (entity != null) {
                    entities.add(entity);
                }
            }
        }
        // 归属损坏时不能返回一个“看起来没有关联”的空列表：那会把「关联已损坏」
        // 伪装成「这个术语本来就没有关联字段」。
        if (scopeService.termScope(termId).isBroken()) {
            throw new BusinessException(409, "术语的关联字段归属不完整，无法返回关联字段");
        }
        if (entities.isEmpty()) {
            return Result.success(List.of());
        }
        Set<Long> visibleDatasourceIds = scopeService.visibleDatasourceIds(
                UserContext.currentUserId(), VIEW_FUNCTION);
        Map<Long, Long> entityDatasourceIds = scopeService.entityDatasourceIds(
                entities.stream().map(MetadataEntity::getId).toList());
        List<MetadataEntity> visible = new ArrayList<>();
        for (MetadataEntity entity : entities) {
            Long datasourceId = entityDatasourceIds.get(entity.getId());
            if (datasourceId != null && visibleDatasourceIds.contains(datasourceId)) {
                visible.add(entity);
            }
        }
        return Result.success(visible);
    }

    // ========== MIXED 动态范围 ==========

    /**
     * 术语级写/审校验。
     *
     * <p>先确认术语存在（不存在返回 404，而不是静默按“未关联”放行），再解析它当前关联的
     * 全部数据源；未关联时只校验功能，已关联时逐源校验、任一无权整体拒绝。</p>
     */
    private void requireTermFunction(Long termId, String functionCode) {
        if (termService.getById(termId) == null) {
            // 术语不存在时**不能**因为“查不到关联源”而按未关联放行。
            throw new BusinessException(404, "术语不存在");
        }
        scopeService.requireWritableScope(UserContext.currentUserId(), functionCode,
                scopeService.termScope(termId));
    }

    /** 术语表级写校验：汇总该术语表下全部术语关联的数据源后逐源校验。 */
    private void requireGlossaryManageable(Long glossaryId) {
        scopeService.requireWritableScope(UserContext.currentUserId(), MANAGE_FUNCTION,
                scopeService.glossaryScope(glossaryId));
    }

    /**
     * 目标实体的写校验。
     *
     * <p>实体归属解析不出来时 fail-closed：不能因为“解析不到源”就当成“没有源限制”。</p>
     */
    private void requireEntityFunction(Long entityId, String functionCode) {
        Long datasourceId = scopeService.entityDatasourceIds(List.of(entityId)).get(entityId);
        if (datasourceId == null) {
            throw new BusinessException(409, "目标实体缺少数据源归属，无法判定负责范围");
        }
        scopeService.requireWritableScope(UserContext.currentUserId(), functionCode,
                GlossaryScopeService.Scope.bound(Set.of(datasourceId)));
    }
}
