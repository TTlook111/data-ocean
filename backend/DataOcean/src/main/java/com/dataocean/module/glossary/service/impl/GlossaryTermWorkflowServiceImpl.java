package com.dataocean.module.glossary.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.glossary.entity.dto.*;
import com.dataocean.module.glossary.entity.po.*;
import com.dataocean.module.glossary.entity.vo.*;
import com.dataocean.module.glossary.mapper.*;
import com.dataocean.module.glossary.service.*;
import com.dataocean.module.metadata.entity.po.MetadataRelationship;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/** 定义、映射及审核共用同一行锁和修订，所有公共写入口均经过此服务。 */
@Service
@RequiredArgsConstructor
public class GlossaryTermWorkflowServiceImpl implements GlossaryTermWorkflowService {
    private final GlossaryTermMapper termMapper;
    private final GlossaryTermReviewMapper reviewMapper;
    private final GlossaryTermService termService;
    private final GlossaryFieldService fieldService;
    private final GlossaryScopeService scopeService;
    private final MetadataRelationshipService relationshipService;
    private final ObjectMapper objectMapper;
    private final GlossaryMapper glossaryMapper;

    private GlossaryTerm locked(Long id, Long revision, String function) {
        var observed = termMapper.selectById(id);
        if (observed == null) throw new BusinessException(404, "术语不存在");
        if (glossaryMapper.selectForUpdate(observed.getGlossaryId()) == null)
            throw new BusinessException(409, "术语分组已被移除");
        var term = termMapper.selectForUpdate(id);
        if (term == null) throw new BusinessException(404, "术语不存在");
        requireScope(id, function);
        if (revision == null) throw new BusinessException(400, "请提供已读取的术语修订号");
        if (!Objects.equals(revision, term.getRevision()))
            throw new BusinessException(409, "术语已被修改，请刷新后重新核对");
        return term;
    }

    private void requireScope(Long id, String function) {
        scopeService.requireWritableScope(UserContext.currentUserId(), function, scopeService.termScope(id));
    }

    private static void editable(GlossaryTerm term) {
        if (!Set.of("DRAFT", "REJECTED").contains(term.getStatus()))
            throw new BusinessException(409, "待审核或已通过的术语不能修改，请先退回草稿");
    }

    @Override
    public List<Long> linkedIds(Long termId) {
        return relationshipService.getBySource(termId, "GLOSSARY_TERM").stream()
                .filter(r -> "GLOSSARY_OF".equals(r.getRelationType()))
                .map(MetadataRelationship::getTargetId).distinct().sorted().toList();
    }

    private List<TermColumnVO> resolve(List<Long> ids, boolean authorize) {
        if (ids == null || ids.size() > 500 || ids.stream().anyMatch(Objects::isNull))
            throw new BusinessException(400, "字段映射清单无效或超过 500 项");
        var columns = ids.stream().distinct().sorted().map(fieldService::resolve).toList();
        if (authorize && !columns.isEmpty()) {
            var sources = new HashSet<Long>();
            columns.forEach(c -> sources.add(c.datasourceId()));
            scopeService.requireWritableScope(UserContext.currentUserId(), "glossary:manage",
                    GlossaryScopeService.Scope.bound(sources));
        }
        return columns;
    }

    private void persist(GlossaryTerm term) {
        long previous = term.getRevision() == null ? 0 : term.getRevision();
        term.setRevision(previous + 1);
        term.setUpdatedAt(LocalDateTime.now());
        // 行锁覆盖定义、关系和审核动作；所有旧入口也必须获取此锁。
        if (termMapper.updateById(term) != 1) throw new BusinessException(409, "术语保存冲突，请刷新重试");
    }

    @Transactional
    @Override
    public GlossaryTerm save(Long glossaryId, Long termId, TermDraftDTO draft) {
        var columns = resolve(draft.getEntityIds(), true);
        GlossaryTerm term;
        if (termId == null) {
            if (glossaryMapper.selectForUpdate(glossaryId) == null) throw new BusinessException(404, "术语分组不存在");
            term = new GlossaryTerm();
            term.setGlossaryId(glossaryId);
            term.setRevision(0L);
        } else {
            term = locked(termId, draft.getRevision(), "glossary:manage");
            editable(term);
        }
        if (draft.getName() == null || draft.getName().isBlank() || draft.getName().length() > 128)
            throw new BusinessException(400, "请填写业务名称");
        term.setName(draft.getName().trim());
        term.setDisplayName(draft.getDisplayName());
        term.setDescription(draft.getDescription());
        term.setSynonyms(arrayJson(draft.getSynonyms()));
        term.setRelatedTerms(arrayJson(draft.getRelatedTerms()));
        if (termId == null) termService.createTerm(term);
        else term = termService.updateTerm(termId, term);
        relationshipService.remove(new LambdaQueryWrapper<MetadataRelationship>()
                .eq(MetadataRelationship::getSourceId, term.getId())
                .eq(MetadataRelationship::getSourceType, "GLOSSARY_TERM")
                .eq(MetadataRelationship::getRelationType, "GLOSSARY_OF"));
        for (var column : columns) {
            var relation = new MetadataRelationship();
            relation.setSourceId(term.getId()); relation.setSourceType("GLOSSARY_TERM");
            relation.setTargetId(column.entityId()); relation.setTargetType("COLUMN");
            relation.setRelationType("GLOSSARY_OF"); relationshipService.upsert(relation);
        }
        // 清空旧审核摘要不能依赖 updateById 对 null 的默认跳过行为。
        termMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GlossaryTerm>()
                .eq(GlossaryTerm::getId, term.getId()).set(GlossaryTerm::getReviewHash, null).set(GlossaryTerm::getReviewerId, null).set(GlossaryTerm::getReviewedAt, null));
        term.setReviewHash(null); term.setReviewerId(null); term.setReviewedAt(null);
        persist(term);
        if (draft.isSubmit()) submit(term);
        return term;
    }

    @Transactional
    @Override
    public void link(Long termId, Long entityId, Long revision, boolean remove) {
        var term = locked(termId, revision, "glossary:manage");
        editable(term);
        var ids = new ArrayList<>(linkedIds(termId));
        if (remove) ids.remove(entityId); else if (!ids.contains(entityId)) ids.add(entityId);
        var draft = new TermDraftDTO();
        draft.setRevision(revision); draft.setName(term.getName()); draft.setDisplayName(term.getDisplayName());
        draft.setDescription(term.getDescription()); draft.setSynonyms(term.getSynonyms());
        draft.setRelatedTerms(term.getRelatedTerms()); draft.setEntityIds(ids);
        save(term.getGlossaryId(), termId, draft);
    }

    private Map<String, Object> content(GlossaryTerm term) {
        var result = new LinkedHashMap<String, Object>();
        result.put("termId", term.getId()); result.put("name", term.getName());
        result.put("displayName", term.getDisplayName()); result.put("description", term.getDescription());
        result.put("synonyms", arrayValue(term.getSynonyms())); result.put("relatedTerms", arrayValue(term.getRelatedTerms()));
        result.put("columns", resolve(linkedIds(term.getId()), false));
        return result;
    }

    private Object arrayValue(String value) {
        try { return objectMapper.readValue(arrayJson(value), java.util.List.class); }
        catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw new BusinessException("术语列表内容无法核验"); }
    }

    private String arrayJson(String value) {
        if (value == null || value.isBlank()) return "[]";
        try {
            var parsed = objectMapper.readTree(value);
            if (!parsed.isArray() || parsed.size() > 200) throw new BusinessException(400, "同义词或关联术语必须是有效列表");
            return objectMapper.writeValueAsString(parsed);
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw new BusinessException(400, "同义词或关联术语格式不正确"); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { throw new BusinessException("术语审核快照保存失败"); }
    }

    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new BusinessException("术语内容摘要计算失败"); }
    }

    private void record(GlossaryTerm term, String action, String reason) {
        Object snapshot;
        if ("REVERTED".equals(action)) {
            snapshot = Map.of("termId", term.getId(), "name", term.getName(), "mappingEntityIds", linkedIds(term.getId()));
        } else if ("REJECTED".equals(action)) {
            var submitted = reviewMapper.selectOne(new LambdaQueryWrapper<GlossaryTermReview>()
                    .eq(GlossaryTermReview::getTermId, term.getId()).eq(GlossaryTermReview::getAction, "SUBMITTED")
                    .orderByDesc(GlossaryTermReview::getId).last("LIMIT 1"));
            try { snapshot = submitted == null ? Map.of("termId", term.getId(), "name", term.getName())
                    : objectMapper.readTree(submitted.getSnapshotJson()); }
            catch (Exception ex) { throw new BusinessException("原审核快照读取失败"); }
        } else snapshot = content(term);
        var review = new GlossaryTermReview();
        review.setTermId(term.getId()); review.setRevision(term.getRevision()); review.setAction(action);
        review.setContentHash(hash(snapshot)); review.setSnapshotJson(json(snapshot));
        review.setReason(reason); review.setActorId(UserContext.currentUserId());
        review.setCreatedAt(LocalDateTime.now()); reviewMapper.insert(review);
    }

    private void submit(GlossaryTerm term) {
        editable(term);
        if (term.getDescription() == null || term.getDescription().isBlank() || linkedIds(term.getId()).isEmpty())
            throw new BusinessException(400, "提交审核前请填写业务定义并关联字段；也可先保存草稿");
        term.setReviewHash(hash(content(term))); term.setStatus("PENDING_REVIEW");
        persist(term); record(term, "SUBMITTED", null);
    }

    @Transactional
    @Override
    public void action(Long termId, String action, TermActionDTO request) {
        boolean reviewing = "REVIEW".equals(action);
        var term = locked(termId, request.getRevision(), reviewing ? "glossary:approve" : "glossary:manage");
        if ("SUBMIT".equals(action)) { submit(term); return; }
        if ("REVERT".equals(action)) {
            if (!"APPROVED".equals(term.getStatus())) throw new BusinessException(409, "只有已通过术语可以退回草稿");
            termMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GlossaryTerm>()
                    .eq(GlossaryTerm::getId, termId).set(GlossaryTerm::getReviewerId, null).set(GlossaryTerm::getReviewedAt, null));
            term.setReviewerId(null); term.setReviewedAt(null);
            term.setStatus("DRAFT"); persist(term); record(term, "REVERTED", request.getReason()); return;
        }
        if (!reviewing || !"PENDING_REVIEW".equals(term.getStatus()))
            throw new BusinessException(409, "术语当前不在待审核状态");
        if (request.isApproved() && (term.getReviewHash() == null || !term.getReviewHash().equals(hash(content(term)))))
            throw new BusinessException(409, "定义或字段来源已变化，请重新提交审核");
        if (!request.isApproved() && (request.getReason() == null || request.getReason().isBlank()))
            throw new BusinessException(400, "请填写审核拒绝原因");
        if (request.getReason() != null && request.getReason().length() > 2000)
            throw new BusinessException(400, "审核意见不能超过 2000 字");
        term.setStatus(request.isApproved() ? "APPROVED" : "REJECTED");
        term.setReviewerId(UserContext.currentUserId()); term.setReviewedAt(LocalDateTime.now());
        persist(term); record(term, term.getStatus(), request.getReason());
    }

    @Transactional
    @Override
    public void delete(Long termId, Long revision) {
        var term = locked(termId, revision, "glossary:manage"); editable(term);
        termService.deleteTerm(termId);
    }

    @Override
    public TermWorkspaceVO workspace(Long termId) {
        var term = termMapper.selectById(termId);
        if (term == null) throw new BusinessException(404, "术语不存在");
        requireScope(termId, "glossary:view");
        List<TermColumnVO> columns = List.of(); String error = null;
        try { columns = resolve(linkedIds(termId), false); }
        catch (BusinessException ex) { error = ex.getMessage(); }
        var reviews = reviewMapper.selectList(new LambdaQueryWrapper<GlossaryTermReview>()
                .eq(GlossaryTermReview::getTermId, termId).orderByDesc(GlossaryTermReview::getId));
        return new TermWorkspaceVO(term, columns, error, "APPROVED".equals(term.getStatus())
                && (term.getReviewHash() == null || !factValid(termId, term.getReviewHash())), reviews);
    }

    @Override
    public Map<String, Object> approvedFact(Long termId) {
        var term = termMapper.selectById(termId);
        if (term == null || !"APPROVED".equals(term.getStatus()) || term.getReviewHash() == null)
            throw new BusinessException(409, "术语尚未完成定义与字段映射的修订审核");
        var snapshot = content(term);
        if (!term.getReviewHash().equals(hash(snapshot))) throw new BusinessException(409, "术语审核来源已变化");
        var latest = reviewMapper.selectOne(new LambdaQueryWrapper<GlossaryTermReview>()
                .eq(GlossaryTermReview::getTermId, termId).eq(GlossaryTermReview::getAction, "APPROVED")
                .eq(GlossaryTermReview::getRevision, term.getRevision()).last("LIMIT 1"));
        if (latest == null || !term.getReviewHash().equals(latest.getContentHash()))
            throw new BusinessException(409, "缺少可核验的术语审核快照");
        snapshot.put("revision", term.getRevision()); snapshot.put("reviewId", latest.getId());
        snapshot.put("contentHash", term.getReviewHash());
        return snapshot;
    }

    @Override
    public boolean factValid(Long termId, String expectedHash) {
        try { return Objects.equals(approvedFact(termId).get("contentHash"), expectedHash); }
        catch (BusinessException ex) { return false; }
    }
}
