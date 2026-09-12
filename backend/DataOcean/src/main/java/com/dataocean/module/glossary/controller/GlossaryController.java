package com.dataocean.module.glossary.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.system.aspect.AdminAuditLog;
import com.dataocean.module.glossary.dto.TermLinkColumnDTO;
import com.dataocean.module.glossary.dto.TermReviewDTO;
import com.dataocean.module.glossary.entity.Glossary;
import com.dataocean.module.glossary.entity.GlossaryTerm;
import com.dataocean.module.glossary.service.GlossaryService;
import com.dataocean.module.glossary.service.GlossaryTermService;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 业务术语管理控制器
 *
 * @author dataocean
 */
@RestController
@RequestMapping("/api/admin/glossary")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('metadata:manage', '*')")
@AdminAuditLog
public class GlossaryController {

    private final GlossaryService glossaryService;
    private final GlossaryTermService termService;
    private final MetadataEntityService entityService;
    private final MetadataRelationshipService relationshipService;

    // ========== 术语表 CRUD ==========

    /** 查询术语表列表 */
    @GetMapping
    public Result<List<Glossary>> listGlossaries() {
        List<Glossary> list = glossaryService.list(
                new LambdaQueryWrapper<Glossary>().orderByDesc(Glossary::getCreatedAt));
        return Result.success(list);
    }

    /** 创建术语表 */
    @PostMapping
    public Result<Map<String, Long>> createGlossary(@RequestBody Glossary glossary) {
        glossary.setOwnerId(UserContext.currentUserId());
        if (glossary.getStatus() == null) {
            glossary.setStatus(Glossary.STATUS_DRAFT);
        }
        glossaryService.save(glossary);
        return Result.success("术语表创建成功", Map.of("id", glossary.getId()));
    }

    /** 更新术语表 */
    @PutMapping("/{id}")
    public Result<Void> updateGlossary(@PathVariable Long id, @RequestBody Glossary glossary) {
        Glossary existing = glossaryService.getById(id);
        if (existing == null) {
            return Result.error(404, "术语表不存在");
        }
        glossary.setId(id);
        glossaryService.updateById(glossary);
        return Result.success("术语表更新成功", null);
    }

    /** 删除术语表（级联清理其下术语与关联关系） */
    @DeleteMapping("/{id}")
    public Result<Void> deleteGlossary(@PathVariable Long id) {
        Glossary existing = glossaryService.getById(id);
        if (existing == null) {
            return Result.error(404, "术语表不存在");
        }
        int removedTerms = glossaryService.deleteGlossary(id);
        return Result.success("术语表已删除，同时清理 " + removedTerms + " 个术语", null);
    }

    // ========== 术语条目 CRUD ==========

    /** 查询术语列表 */
    @GetMapping("/{glossaryId}/terms")
    public Result<List<GlossaryTerm>> listTerms(
            @PathVariable Long glossaryId,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<GlossaryTerm> qw = new LambdaQueryWrapper<GlossaryTerm>()
                .eq(GlossaryTerm::getGlossaryId, glossaryId)
                .eq(status != null, GlossaryTerm::getStatus, status)
                .orderByAsc(GlossaryTerm::getName);
        return Result.success(termService.list(qw));
    }

    /** 创建术语 */
    @PostMapping("/{glossaryId}/terms")
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
    public Result<Void> updateTerm(@PathVariable Long termId, @RequestBody GlossaryTerm term) {
        termService.updateTerm(termId, term);
        return Result.success("术语更新成功", null);
    }

    /** 删除术语（清理关联关系与悬空的父术语引用） */
    @DeleteMapping("/terms/{termId}")
    public Result<Void> deleteTerm(@PathVariable Long termId) {
        termService.deleteTerm(termId);
        return Result.success("术语已删除", null);
    }

    // ========== 审核流程 ==========

    /** 提交术语审核 */
    @PostMapping("/terms/{termId}/submit")
    public Result<Void> submitForReview(@PathVariable Long termId) {
        termService.submitForReview(termId);
        return Result.success("术语已提交审核", null);
    }

    /** 审核术语 */
    @PostMapping("/terms/{termId}/review")
    public Result<Void> reviewTerm(
            @PathVariable Long termId,
            @RequestBody TermReviewDTO request) {
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
    public Result<Void> revertTermToDraft(@PathVariable Long termId) {
        termService.revertToDraft(termId);
        return Result.success("术语已退回草稿，修改后需重新提交审核", null);
    }

    // ========== 术语与列关联 ==========

    /** 关联术语与物理列（创建 GLOSSARY_OF 关系） */
    @PostMapping("/terms/{termId}/link-column")
    public Result<Void> linkTermToColumn(
            @PathVariable Long termId,
            @RequestBody TermLinkColumnDTO request) {
        Long entityId = request.getEntityId();

        GlossaryTerm term = termService.getById(termId);
        if (term == null) {
            return Result.error(404, "术语不存在");
        }
        MetadataEntity entity = entityService.getById(entityId);
        if (entity == null) {
            return Result.error(404, "实体不存在");
        }

        MetadataRelationship rel = new MetadataRelationship();
        rel.setSourceId(termId);
        rel.setSourceType("GLOSSARY_TERM");
        rel.setTargetId(entityId);
        rel.setTargetType(entity.getEntityType());
        rel.setRelationType(MetadataRelationship.TYPE_GLOSSARY_OF);
        relationshipService.upsert(rel);

        return Result.success("关联成功", null);
    }

    /** 取消术语与列的关联 */
    @DeleteMapping("/terms/{termId}/unlink-column/{entityId}")
    public Result<Void> unlinkTermFromColumn(
            @PathVariable Long termId,
            @PathVariable Long entityId) {
        // 查找并删除 GLOSSARY_OF 关系
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

    /** 查询术语关联的物理列 */
    @GetMapping("/terms/{termId}/linked-columns")
    public Result<List<MetadataEntity>> getLinkedColumns(@PathVariable Long termId) {
        var rels = relationshipService.getBySource(termId, "GLOSSARY_TERM");
        List<MetadataEntity> entities = new java.util.ArrayList<>();
        for (MetadataRelationship rel : rels) {
            if (MetadataRelationship.TYPE_GLOSSARY_OF.equals(rel.getRelationType())) {
                MetadataEntity entity = entityService.getById(rel.getTargetId());
                if (entity != null) {
                    entities.add(entity);
                }
            }
        }
        return Result.success(entities);
    }
}
