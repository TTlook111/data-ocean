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
import com.dataocean.module.metadata.support.RelationshipBindingStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/** 定义、映射及审核共用同一行锁和修订，所有公共写入口均经过此服务。 */
@Slf4j
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
                // 快照发布后端点消失的映射保留历史，但不再参与当前定义、审核摘要和写入门禁。
                .filter(r -> !RelationshipBindingStatus.isInactive(r))
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

    /** 对外输出（审核快照、知识生成）保留完整字段来源。 */
    private Map<String, Object> content(GlossaryTerm term) {
        return content(term, resolve(linkedIds(term.getId()), false));
    }

    private Map<String, Object> content(GlossaryTerm term, List<TermColumnVO> columns) {
        var result = new LinkedHashMap<String, Object>();
        result.put("termId", term.getId()); result.put("name", term.getName());
        result.put("displayName", term.getDisplayName()); result.put("description", term.getDescription());
        result.put("synonyms", arrayValue(term.getSynonyms())); result.put("relatedTerms", arrayValue(term.getRelatedTerms()));
        result.put("columns", columns);
        return result;
    }

    /**
     * 审核摘要只使用字段 FQN 等稳定身份，不包含快照/实体/表/字段的数字 ID。
     *
     * <p>否则每次发布新快照都会改变摘要，把所有仍然有效的审核结论误判为失效；
     * 同时不能把快照期号写进审阅证据，让同一逻辑字段的结论无法跨发布延续。</p>
     */
    private Map<String, Object> stableContent(GlossaryTerm term) {
        return stableContent(term, resolve(linkedIds(term.getId()), false));
    }

    private Map<String, Object> stableContent(GlossaryTerm term, List<TermColumnVO> columns) {
        var result = new LinkedHashMap<String, Object>();
        result.put("termId", term.getId()); result.put("name", term.getName());
        result.put("displayName", term.getDisplayName()); result.put("description", term.getDescription());
        result.put("synonyms", arrayValue(term.getSynonyms())); result.put("relatedTerms", arrayValue(term.getRelatedTerms()));
        result.put("columns", columns.stream().map(this::stableColumnIdentity).toList());
        return result;
    }

    private Map<String, Object> stableColumnIdentity(TermColumnVO column) {
        var identity = new LinkedHashMap<String, Object>();
        identity.put("fqn", column.fqn() == null ? null : column.fqn().toLowerCase());
        identity.put("datasourceId", column.datasourceId());
        identity.put("tableName", column.tableName());
        identity.put("columnName", column.columnName());
        identity.put("columnType", column.columnType());
        identity.put("description", column.description());
        return identity;
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
        String contentHash;
        if ("REVERTED".equals(action)) {
            snapshot = Map.of("termId", term.getId(), "name", term.getName(), "mappingEntityIds", linkedIds(term.getId()));
            contentHash = hash(snapshot);
        } else if ("REJECTED".equals(action)) {
            var submitted = reviewMapper.selectOne(new LambdaQueryWrapper<GlossaryTermReview>()
                    .eq(GlossaryTermReview::getTermId, term.getId()).eq(GlossaryTermReview::getAction, "SUBMITTED")
                    .orderByDesc(GlossaryTermReview::getId).last("LIMIT 1"));
            try {
                snapshot = submitted == null ? Map.of("termId", term.getId(), "name", term.getName())
                        : objectMapper.readTree(submitted.getSnapshotJson());
                // 拒绝记录沿用被拒绝的提交摘要，不能按包含快照期号的完整正文重新计算。
                contentHash = submitted == null ? hash(stableContent(term)) : submitted.getContentHash();
            } catch (Exception ex) { throw new BusinessException("原审核快照读取失败"); }
        } else {
            snapshot = content(term);
            contentHash = term.getReviewHash() != null ? term.getReviewHash() : hash(stableContent(term));
        }
        var review = new GlossaryTermReview();
        review.setTermId(term.getId()); review.setRevision(term.getRevision()); review.setAction(action);
        review.setContentHash(contentHash); review.setSnapshotJson(json(snapshot));
        review.setReason(reason); review.setActorId(UserContext.currentUserId());
        review.setCreatedAt(LocalDateTime.now()); reviewMapper.insert(review);
    }

    private void submit(GlossaryTerm term) {
        editable(term);
        if (term.getDescription() == null || term.getDescription().isBlank() || linkedIds(term.getId()).isEmpty())
            throw new BusinessException(400, "提交审核前请填写业务定义并关联字段；也可先保存草稿");
        term.setReviewHash(hash(stableContent(term))); term.setStatus("PENDING_REVIEW");
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
        if (request.isApproved() && (term.getReviewHash() == null
                || !term.getReviewHash().equals(hash(stableContent(term)))))
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
                && (term.getReviewHash() == null || !factValid(termId, term.getReviewHash())), reviews,
                invalidMappings(termId));
    }

    /** 失效映射保留历史但不再参与当前定义与审核；页面明确提示需要重新确认。 */
    private List<TermInvalidMappingVO> invalidMappings(Long termId) {
        List<TermInvalidMappingVO> result = new ArrayList<>();
        for (MetadataRelationship relation : relationshipService.getBySource(termId, "GLOSSARY_TERM")) {
            if (!"GLOSSARY_OF".equals(relation.getRelationType())
                    || !RelationshipBindingStatus.isInactive(relation)) {
                continue;
            }
            Map<String, Object> metadata = Map.of();
            try {
                if (relation.getRelationMetadata() != null && !relation.getRelationMetadata().isBlank()) {
                    metadata = objectMapper.readValue(relation.getRelationMetadata(), new TypeReference<>() {});
                }
            } catch (Exception ignored) {
                // 元数据损坏时仍返回失效记录本身，原因显示为通用提示。
            }
            result.add(new TermInvalidMappingVO(relation.getTargetId(),
                    stringValue(metadata.get(RelationshipBindingStatus.KEY_PREVIOUS_TARGET_FQN)),
                    stringValue(metadata.get(RelationshipBindingStatus.KEY_REASON)),
                    asLong(metadata.get(RelationshipBindingStatus.KEY_INVALIDATED_SNAPSHOT))));
        }
        return result;
    }

    private String stringValue(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @Override
    public Map<String, Object> approvedFact(Long termId) {
        var term = termMapper.selectById(termId);
        if (term == null) {
            throw new BusinessException(409, "术语尚未完成定义与字段映射的修订审核");
        }
        var verification = assess(term);
        if (!verification.valid()) {
            // 与 verify() 共用同一套规则；这里只把失败原因转成原有 409 保护。
            throw new BusinessException(409, verification.message());
        }
        var snapshot = content(term, verification.columns());
        snapshot.put("revision", term.getRevision());
        snapshot.put("reviewId", verification.review().getId());
        snapshot.put("contentHash", term.getReviewHash());
        return snapshot;
    }

    @Override
    public GlossaryTermVerificationVO verify(Long termId) {
        GlossaryTerm term;
        try {
            term = termId == null ? null : termMapper.selectById(termId);
        } catch (Exception exception) {
            log.warn("术语核验读取失败 termId={}: {}", termId, exception.getMessage());
            return new GlossaryTermVerificationVO(false,
                    GlossaryTermVerificationVO.SYSTEM_ERROR,
                    "术语记录读取失败，当前有效性无法确认。",
                    null, null, null, null);
        }
        if (term == null) {
            return new GlossaryTermVerificationVO(false,
                    GlossaryTermVerificationVO.TERM_MISSING,
                    "关联的术语记录已不存在，无法核验当前有效性。",
                    null, null, null, null);
        }
        Verification verification = assess(term);
        return new GlossaryTermVerificationVO(
                verification.valid(), verification.code(), verification.message(),
                term.getStatus(), term.getRevision(),
                verification.review() == null ? null : verification.review().getId(),
                verification.valid() ? term.getReviewHash() : null);
    }

    /**
     * 现有审核与来源规则的唯一核验实现。
     *
     * <p>分类只来自这里的控制流，不解析异常文案：审核证据缺失、内容变化、
     * 来源失效和读取/解析异常分别返回不同 code。</p>
     */
    private Verification assess(GlossaryTerm term) {
        if (!"APPROVED".equals(term.getStatus())) {
            return Verification.failure(GlossaryTermVerificationVO.NOT_APPROVED,
                    notApprovedMessage(term.getStatus()));
        }
        if (term.getReviewHash() == null) {
            return Verification.failure(GlossaryTermVerificationVO.EVIDENCE_MISSING,
                    "缺少审核摘要或与当前修订匹配的审核记录，历史审核证据不足，需要重新确认。");
        }

        List<TermColumnVO> columns;
        try {
            columns = resolve(linkedIds(term.getId()), false);
        } catch (BusinessException exception) {
            return Verification.failure(GlossaryTermVerificationVO.SOURCE_INVALID,
                    sourceFailureMessage(exception));
        } catch (Exception exception) {
            log.warn("术语来源核验失败 termId={}: {}", term.getId(), exception.getMessage());
            return Verification.failure(GlossaryTermVerificationVO.SYSTEM_ERROR,
                    "术语来源读取失败，当前有效性无法确认。");
        }

        GlossaryTermReview latest;
        try {
            latest = reviewMapper.selectOne(new LambdaQueryWrapper<GlossaryTermReview>()
                    .eq(GlossaryTermReview::getTermId, term.getId())
                    .eq(GlossaryTermReview::getAction, "APPROVED")
                    .eq(GlossaryTermReview::getRevision, term.getRevision())
                    .last("LIMIT 1"));
        } catch (Exception exception) {
            log.warn("术语审核记录读取失败 termId={}: {}", term.getId(), exception.getMessage());
            return Verification.failure(GlossaryTermVerificationVO.SYSTEM_ERROR,
                    "术语审核记录读取失败，当前有效性无法确认。");
        }
        if (latest == null || !term.getReviewHash().equals(latest.getContentHash())) {
            return Verification.failure(GlossaryTermVerificationVO.EVIDENCE_MISSING,
                    "缺少与当前修订匹配的审核摘要或审核记录，历史审核证据不足，需要重新确认。");
        }

        try {
            String currentStableHash = hash(stableContent(term, columns));
            if (!term.getReviewHash().equals(currentStableHash)) {
                // 兼容改造前包含快照/实体数字 ID 的旧审核记录；只有稳定内容相同才继续有效。
                String reviewedStableHash = stableHashOfReviewSnapshot(latest.getSnapshotJson(), term.getId());
                if (!currentStableHash.equals(reviewedStableHash)) {
                    return Verification.failure(GlossaryTermVerificationVO.CONTENT_CHANGED,
                            "业务定义、字段映射或来源内容已发生变化，需要重新确认。");
                }
            }
        } catch (BusinessException exception) {
            log.warn("术语内容核验失败 termId={}: {}", term.getId(), exception.getMessage());
            return Verification.failure(GlossaryTermVerificationVO.SYSTEM_ERROR,
                    "术语定义或审核快照解析失败，当前有效性无法确认。");
        } catch (Exception exception) {
            log.warn("术语内容核验异常 termId={}: {}", term.getId(), exception.getMessage());
            return Verification.failure(GlossaryTermVerificationVO.SYSTEM_ERROR,
                    "术语核验读取失败，当前有效性无法确认。");
        }
        return new Verification(true, GlossaryTermVerificationVO.VALID,
                "已通过审核，当前定义与字段来源仍可核验。", columns, latest);
    }

    private String notApprovedMessage(String status) {
        return switch (status == null ? "" : status) {
            case "PENDING_REVIEW" -> "待审核，当前不能作为已审核业务事实。";
            case "REJECTED" -> "审核未通过，当前不能作为业务事实。";
            case "DRAFT" -> "仍为草稿，尚未提交审核。";
            default -> "状态未知，不能宣称当前可用。";
        };
    }

    private String sourceFailureMessage(BusinessException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "关联字段来源已失效或无法核验，需要重新关联并确认。";
        }
        return "关联字段来源已失效或无法核验：" + message;
    }

    private record Verification(boolean valid, String code, String message,
                                 List<TermColumnVO> columns, GlossaryTermReview review) {
        static Verification failure(String code, String message) {
            return new Verification(false, code, message, List.of(), null);
        }
    }

    /** 从不可变审核快照 JSON 重建稳定摘要；无法解析时按不通过处理。 */
    private String stableHashOfReviewSnapshot(String snapshotJson, Long termId) {
        try {
            var node = objectMapper.readTree(snapshotJson);
            var stable = new LinkedHashMap<String, Object>();
            stable.put("termId", node.hasNonNull("termId") ? node.get("termId").asLong() : termId);
            stable.put("name", text(node, "name"));
            stable.put("displayName", text(node, "displayName"));
            stable.put("description", text(node, "description"));
            stable.put("synonyms", node.has("synonyms") ? node.get("synonyms") : objectMapper.createArrayNode());
            stable.put("relatedTerms", node.has("relatedTerms") ? node.get("relatedTerms") : objectMapper.createArrayNode());
            var columns = new ArrayList<Map<String, Object>>();
            if (node.has("columns") && node.get("columns").isArray()) {
                for (var column : node.get("columns")) {
                    var identity = new LinkedHashMap<String, Object>();
                    identity.put("fqn", text(column, "fqn") == null ? null : text(column, "fqn").toLowerCase());
                    identity.put("datasourceId", longOrNull(column, "datasourceId"));
                    identity.put("tableName", text(column, "tableName"));
                    identity.put("columnName", text(column, "columnName"));
                    identity.put("columnType", text(column, "columnType"));
                    identity.put("description", text(column, "description"));
                    columns.add(identity);
                }
            }
            stable.put("columns", columns);
            return hash(stable);
        } catch (Exception exception) {
            throw new BusinessException(409, "术语审核快照无法核验");
        }
    }

    private String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private Long longOrNull(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asLong() : null;
    }

    @Override
    public boolean factValid(Long termId, String expectedHash) {
        try { return Objects.equals(approvedFact(termId).get("contentHash"), expectedHash); }
        catch (BusinessException ex) { return false; }
    }
}
