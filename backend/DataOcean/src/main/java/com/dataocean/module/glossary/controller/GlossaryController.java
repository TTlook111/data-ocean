package com.dataocean.module.glossary.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.glossary.entity.Glossary;
import com.dataocean.module.glossary.entity.GlossaryTerm;
import com.dataocean.module.glossary.service.GlossaryService;
import com.dataocean.module.glossary.service.GlossaryTermService;
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
public class GlossaryController {

    private final GlossaryService glossaryService;
    private final GlossaryTermService termService;

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

    /** 删除术语表 */
    @DeleteMapping("/{id}")
    public Result<Void> deleteGlossary(@PathVariable Long id) {
        Glossary existing = glossaryService.getById(id);
        if (existing == null) {
            return Result.error(404, "术语表不存在");
        }
        glossaryService.removeById(id);
        return Result.success("术语表已删除", null);
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

    /** 更新术语 */
    @PutMapping("/terms/{termId}")
    public Result<Void> updateTerm(@PathVariable Long termId, @RequestBody GlossaryTerm term) {
        GlossaryTerm existing = termService.getById(termId);
        if (existing == null) {
            return Result.error(404, "术语不存在");
        }
        term.setId(termId);
        termService.updateById(term);
        return Result.success("术语更新成功", null);
    }

    /** 删除术语 */
    @DeleteMapping("/terms/{termId}")
    public Result<Void> deleteTerm(@PathVariable Long termId) {
        termService.removeById(termId);
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
            @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        String reason = (String) body.getOrDefault("reason", "");
        termService.reviewTerm(termId, UserContext.currentUserId(), approved, reason);
        return Result.success(approved ? "术语审核通过" : "术语审核拒绝", null);
    }
}
