package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.common.persistence.OptimisticLockSupport;
import com.dataocean.module.knowledge.client.PythonKnowledgeClient;
import com.dataocean.module.knowledge.entity.dto.*;
import com.dataocean.module.knowledge.entity.po.*;
import com.dataocean.module.knowledge.mapper.*;
import com.dataocean.module.glossary.service.GlossaryTermWorkflowService;
import com.dataocean.module.metadata.entity.po.*;
import com.dataocean.module.metadata.mapper.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.time.LocalDateTime;

/** AI 仅返回候选；用户确认后才创建新的知识草稿版本。 */
@Service
@RequiredArgsConstructor
public class KnowledgeSemanticServiceImpl {
    private final KnowledgeDocMapper docMapper;
    private final KnowledgeDocVersionMapper versionMapper;
    private final KnowledgeSemanticCandidateMapper candidateMapper;
    private final DbTableMetaMapper tableMapper;
    private final DbColumnMetaMapper columnMapper;
    private final GlossaryKnowledgeSourceServiceImpl glossarySources;
    private final GlossaryTermWorkflowService termWorkflow;
    private final PythonKnowledgeClient pythonClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    private KnowledgeDoc requireDraft(Long id, Integer version, boolean lock) {
        var doc = lock ? docMapper.selectForUpdate(id) : docMapper.selectById(id);
        if (doc == null) throw new BusinessException(404, "知识文档不存在");
        if (!"DRAFT".equals(doc.getStatus()) || version == null || !version.equals(doc.getVersion()))
            throw new BusinessException(409, "文档状态或版本已变化，请先保存并重新核对");
        return doc;
    }

    private KnowledgeDocVersion sourceVersion(KnowledgeDoc doc) {
        var version = versionMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocVersion>()
                .eq(KnowledgeDocVersion::getDocId, doc.getId()).eq(KnowledgeDocVersion::getVersionNo, doc.getCurrentVersion()));
        if (version == null || version.getMetadataSnapshotId() == null)
            throw new BusinessException(409, "请先按已发布快照生成目录，再补写业务释义");
        return version;
    }

    private List<String> dependencies(Long datasourceId, Long snapshotId) {
        var tables = tableMapper.selectList(new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getDatasourceId, datasourceId).eq(DbTableMeta::getSnapshotId, snapshotId)
                .notIn(DbTableMeta::getGovernanceStatus, List.of("BLOCKED", "DEPRECATED")));
        if (tables.isEmpty()) return List.of();
        var columns = columnMapper.selectList(new LambdaQueryWrapper<DbColumnMeta>()
                .eq(DbColumnMeta::getDatasourceId, datasourceId).eq(DbColumnMeta::getSnapshotId, snapshotId)
                .in(DbColumnMeta::getTableMetaId, tables.stream().map(DbTableMeta::getId).toList())
                .notIn(DbColumnMeta::getGovernanceStatus, List.of("BLOCKED", "DEPRECATED")));
        var result = new TreeSet<String>();
        tables.forEach(t -> result.add("table:" + t.getTableName()));
        columns.forEach(c -> result.add("column:" + c.getTableName() + "." + c.getColumnName()));
        return new ArrayList<>(result);
    }

    public List<Map<String, Object>> generate(Long docId, SemanticDraftDTO request) {
        var doc = requireDraft(docId, request.getVersion(), false);
        var version = sourceVersion(doc);
        var input = new LinkedHashMap<String, Object>(glossarySources.inputs(doc.getDatasourceId(), version.getMetadataSnapshotId()));
        var allowed = dependencies(doc.getDatasourceId(), version.getMetadataSnapshotId());
        if (request.getTableNames() == null || request.getTableNames().size() > 100)
            throw new BusinessException(400, "业务表范围无效");
        if (!request.getTableNames().isEmpty()) {
            var tableCandidates = new HashSet<>(allowed);
            if (request.getTableNames().stream().anyMatch(t -> !tableCandidates.contains("table:" + t)))
                throw new BusinessException(409, "选择的业务表不属于当前合规快照");
            allowed = allowed.stream().filter(d -> request.getTableNames().stream()
                    .anyMatch(t -> d.equals("table:" + t) || d.startsWith("column:" + t + "."))).toList();
        }
        input.put("authorizedDependencies", allowed);
        var metadata = columnMapper.selectList(new LambdaQueryWrapper<DbColumnMeta>()
                .eq(DbColumnMeta::getDatasourceId, doc.getDatasourceId()).eq(DbColumnMeta::getSnapshotId, version.getMetadataSnapshotId()));
        var allowedSet = new HashSet<>(allowed);
        @SuppressWarnings("unchecked") var terms = (List<Map<String, Object>>) input.get("glossaryFacts");
        input.put("glossaryFacts", terms.stream().filter(term -> term.get("dependencies") instanceof List<?> deps
                && allowedSet.containsAll(deps)).toList());
        input.put("schema", metadata.stream().filter(c -> allowedSet.contains("column:" + c.getTableName() + "." + c.getColumnName()))
                .map(c -> Map.of("column", c.getTableName() + "." + c.getColumnName(), "type", c.getDataType(),
                        "rawCommentPendingConfirmation", c.getColumnComment() == null ? "" : c.getColumnComment())).toList());
        if (((List<?>) input.get("authorizedDependencies")).isEmpty()) throw new BusinessException(409, "没有可补写的合规字段");
        var result = pythonClient.supplementSemantics(input);
        if (result == null || !(result.get("candidates") instanceof List<?> candidates) || candidates.isEmpty() || candidates.size() > 100)
            throw new BusinessException("AI 未返回有效释义候选，原有目录与人工内容已保留");
        return transactionTemplate.execute(status -> {
            requireDraft(docId, request.getVersion(), true);
            List<Map<String, Object>> records = new ArrayList<>();
            for (var raw : candidates) {
                if (!(raw instanceof Map<?, ?> candidate) || !(candidate.get("text") instanceof String text)
                        || text.isBlank() || text.length() > 4000 || !(candidate.get("dependencies") instanceof List<?> deps))
                    throw new BusinessException("AI 候选格式不正确，未写入本次候选");
                var normalized = deps.stream().map(String::valueOf).distinct().sorted().toList();
                if (!((List<?>) input.get("authorizedDependencies")).containsAll(normalized))
                    throw new BusinessException("AI 候选超出本次选择范围");
                validateDependencies(doc.getDatasourceId(), version.getMetadataSnapshotId(), normalized);
                var row = new KnowledgeSemanticCandidate();
                row.setDocId(docId); row.setDatasourceId(doc.getDatasourceId());
                row.setSnapshotId(version.getMetadataSnapshotId()); row.setBaseVersion(request.getVersion());
                row.setCandidateText(text); row.setDependenciesJson(json(normalized)); row.setInputJson(json(input));
                row.setCreatedAt(LocalDateTime.now()); validateTermInputs(row); candidateMapper.insert(row);
                records.add(Map.of("id", row.getId(), "text", text, "dependencies", normalized));
            }
            return records;
        });
    }

    @Transactional
    public void confirm(Long docId, SemanticConfirmDTO request) {
        var doc = requireDraft(docId, request.getVersion(), true);
        var previous = sourceVersion(doc);
        if (!request.isConfirmed() || request.getSelections() == null || request.getSelections().isEmpty()
                || request.getSelections().size() > 100) throw new BusinessException(400, "请核对业务含义并选择需要采纳的释义");
        var ids = new HashSet<Long>();
        var content = new StringBuilder(doc.getContent()).append("\n\n## 人工确认的业务释义\n");
        for (var selection : request.getSelections()) {
            if (selection.getId() == null || !ids.add(selection.getId()) || selection.getText() == null
                    || selection.getText().isBlank() || selection.getText().length() > 4000)
                throw new BusinessException(400, "释义选择重复或内容为空、过长");
            var row = candidateMapper.selectById(selection.getId());
            if (row == null || !docId.equals(row.getDocId()) || !request.getVersion().equals(row.getBaseVersion())
                    || !previous.getMetadataSnapshotId().equals(row.getSnapshotId()) || row.getConfirmedBy() != null)
                throw new BusinessException(409, "候选不属于当前文档版本或已经被采纳，请重新生成");
            validateDependencies(row.getDatasourceId(), row.getSnapshotId(), strings(row.getDependenciesJson()));
            validateTermInputs(row);
            row.setConfirmedText(GlossaryKnowledgeSourceServiceImpl.plain(selection.getText()));
            row.setConfirmedBy(UserContext.currentUserId()); row.setConfirmedAt(LocalDateTime.now());
            candidateMapper.updateById(row);
            var marker = new LinkedHashMap<String, Object>();
            marker.put("snapshotId", row.getSnapshotId()); marker.put("factType", "SEMANTIC_NOTE");
            marker.put("sourceIds", List.of(row.getId())); marker.put("dependencies", strings(row.getDependenciesJson()));
            marker.put("reviewStatus", "APPROVED"); marker.put("governanceStatus", "NORMAL");
            content.append("\n### 业务释义 ").append(row.getId()).append("\n<!-- dataocean-fact: ")
                    .append(json(marker)).append(" -->\n- 业务释义: ").append(row.getConfirmedText()).append("\n");
        }
        doc.setContent(content.toString()); doc.setCurrentVersion(doc.getCurrentVersion() + 1);
        doc.setUpdatedBy(UserContext.currentUserId());
        OptimisticLockSupport.requireUpdated(docMapper.updateById(doc), "文档已被修改，未采纳本次释义");
        var version = KnowledgeDocVersion.builder().docId(docId).datasourceId(doc.getDatasourceId())
                .metadataSnapshotId(previous.getMetadataSnapshotId()).dependencySnapshot(previous.getDependencySnapshot())
                .versionNo(doc.getCurrentVersion()).content(doc.getContent()).generationSource("MANUAL")
                .changeSummary("人工核对并采纳 AI 业务释义，仍需文档审核")
                .createdBy(UserContext.currentUserId()).build();
        versionMapper.insert(version);
    }

    public void validate(Long datasourceId, Long snapshotId, Map<String, Object> marker, String text) {
        var ids = (List<?>) marker.get("sourceIds");
        if (ids == null || ids.size() != 1) throw new BusinessException("业务释义来源无效");
        var row = candidateMapper.selectById(Long.valueOf(ids.get(0).toString()));
        if (row == null || docMapper.selectById(row.getDocId()) == null || !datasourceId.equals(row.getDatasourceId()) || !snapshotId.equals(row.getSnapshotId())
                || row.getConfirmedBy() == null || !Objects.equals("- 业务释义: " + row.getConfirmedText(), text.strip())
                || !new HashSet<>(strings(row.getDependenciesJson())).equals(new HashSet<>((List<?>) marker.get("dependencies")))
                || !"APPROVED".equals(marker.get("reviewStatus")))
            throw new BusinessException("业务释义正文或来源未经人工确认");
        validateDependencies(datasourceId, snapshotId, strings(row.getDependenciesJson())); validateTermInputs(row);
    }

    public boolean membershipValid(List<String> ids) {
        try {
            if (ids.size() != 1) return false;
            var row = candidateMapper.selectById(Long.valueOf(ids.get(0)));
            if (row == null || row.getConfirmedBy() == null || docMapper.selectById(row.getDocId()) == null) return false;
            validateDependencies(row.getDatasourceId(), row.getSnapshotId(), strings(row.getDependenciesJson()));
            validateTermInputs(row); return true;
        } catch (RuntimeException ex) { return false; }
    }

    private void validateDependencies(Long datasourceId, Long snapshotId, List<String> deps) {
        if (deps.isEmpty() || deps.stream().noneMatch(d -> d.startsWith("column:"))
                || !new HashSet<>(dependencies(datasourceId, snapshotId)).containsAll(deps))
            throw new BusinessException(409, "业务释义字段依赖缺失或不属于本次合规快照");
        for (var dependency : deps) if (dependency.startsWith("column:")) {
            String column = dependency.substring(7); int dot = column.indexOf('.');
            if (dot < 1 || !deps.contains("table:" + column.substring(0, dot)))
                throw new BusinessException("业务释义缺少完整表依赖");
        }
    }

    private void validateTermInputs(KnowledgeSemanticCandidate row) {
        try {
            var input = objectMapper.readTree(row.getInputJson());
            if (!input.path("glossaryFacts").isArray()) throw new BusinessException("业务释义缺少术语输入清单");
            for (var fact : input.path("glossaryFacts")) {
                var current = termWorkflow.approvedFact(fact.path("term_id").asLong());
                if (!Objects.equals(current.get("contentHash"), fact.path("content_hash").asText())
                        || !String.valueOf(current.get("revision")).equals(fact.path("revision").asText())
                        || !String.valueOf(current.get("reviewId")).equals(fact.path("review_id").asText()))
                    throw new BusinessException(409, "生成所用术语已经变化，请重新补写业务释义");
            }
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw new BusinessException("业务释义来源记录损坏"); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { throw new BusinessException("业务释义来源序列化失败"); }
    }
    private List<String> strings(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception ex) { throw new BusinessException("业务释义字段依赖损坏"); }
    }
}
