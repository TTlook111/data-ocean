package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.glossary.entity.po.*;
import com.dataocean.module.glossary.entity.vo.TermColumnVO;
import com.dataocean.module.glossary.mapper.*;
import com.dataocean.module.glossary.service.*;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** 术语来源只由 Java 生成并核验，模型无权声明审核结果。 */
@Service
@RequiredArgsConstructor
public class GlossaryKnowledgeSourceServiceImpl {
    private final GlossaryTermMapper termMapper;
    private final GlossaryTermReviewMapper reviewMapper;
    private final GlossaryTermWorkflowService workflowService;
    private final GlossaryScopeService scopeService;
    private final MetadataSnapshotMapper snapshotMapper;
    private final com.dataocean.module.metadata.mapper.DbTableMetaMapper tableMapper;

    public Map<String, Object> inputs(Long datasourceId, Long snapshotId) {
        var snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null || !datasourceId.equals(snapshot.getDatasourceId()) || !"PUBLISHED".equals(snapshot.getStatus()))
            throw new BusinessException(409, "请选择该数据源的已发布快照");
        var terms = termMapper.selectList(new LambdaQueryWrapper<GlossaryTerm>().eq(GlossaryTerm::getStatus, "APPROVED"));
        var scopes = scopeService.termScopes(terms.stream().map(GlossaryTerm::getId).toList());
        List<Map<String, Object>> facts = new ArrayList<>();
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (var term : terms) {
            var scope = scopes.get(term.getId());
            // 不向本源的知识维护者泄露其他源的术语、字段或排除原因。
            if (scope == null || !scope.datasourceIds().contains(datasourceId)) continue;
            try {
                var source = workflowService.approvedFact(term.getId());
                @SuppressWarnings("unchecked") var columns = (List<TermColumnVO>) source.get("columns");
                if (columns.isEmpty() || columns.stream().anyMatch(c -> !datasourceId.equals(c.datasourceId()) || !snapshotId.equals(c.snapshotId())))
                    throw new BusinessException(409, "映射不完整、跨源或不属于所选快照，请分开确认业务含义");
                var dependencies = new TreeSet<String>();
                for (var column : columns) {
                    dependencies.add("table:" + column.tableName());
                    dependencies.add("column:" + column.tableName() + "." + column.columnName());
                }
                var fact = new LinkedHashMap<String, Object>();
                fact.put("term_id", term.getId()); fact.put("review_id", source.get("reviewId"));
                fact.put("revision", source.get("revision")); fact.put("content_hash", source.get("contentHash"));
                fact.put("name", source.get("displayName") == null ? source.get("name") : source.get("displayName"));
                fact.put("description", source.get("description")); fact.put("synonyms", source.get("synonyms"));
                fact.put("dependencies", new ArrayList<>(dependencies));
                fact.put("body", body(fact)); facts.add(fact);
            } catch (BusinessException ex) {
                excluded.add(Map.of("termId", term.getId(), "name", term.getName(), "reason", ex.getMessage()));
            }
        }
        var tableNames = tableMapper.selectList(new LambdaQueryWrapper<com.dataocean.module.metadata.entity.po.DbTableMeta>()
                .eq(com.dataocean.module.metadata.entity.po.DbTableMeta::getDatasourceId, datasourceId)
                .eq(com.dataocean.module.metadata.entity.po.DbTableMeta::getSnapshotId, snapshotId)
                .notIn(com.dataocean.module.metadata.entity.po.DbTableMeta::getGovernanceStatus, List.of("BLOCKED", "DEPRECATED")))
                .stream().map(com.dataocean.module.metadata.entity.po.DbTableMeta::getTableName).sorted().toList();
        return Map.of("tableNames", tableNames, "datasourceId", datasourceId, "snapshotId", snapshotId,
                "glossaryFacts", facts, "excludedTerms", excluded, "snapshotVersion", snapshot.getSnapshotVersion() == null ? 0 : snapshot.getSnapshotVersion(),
                "tableCount", snapshot.getTableCount() == null ? 0 : snapshot.getTableCount(),
                "columnCount", snapshot.getColumnCount() == null ? 0 : snapshot.getColumnCount());
    }

    public static String plain(Object value) {
        return (value == null ? "" : String.valueOf(value)).replaceAll("[\\r\\n\\t]+", " ")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").strip();
    }

    public static String body(Map<String, Object> fact) {
        return "- 业务名称: " + plain(fact.get("name")) + "\n- 术语定义: " + plain(fact.get("description"))
                + "\n- 同义词: " + plain(fact.get("synonyms"));
    }

    public void validate(Long datasourceId, Long snapshotId, Map<String, Object> marker, String text) {
        var ids = (List<?>) marker.get("sourceIds");
        if (ids == null || ids.size() != 2) throw new BusinessException("术语事实来源无效");
        var review = reviewMapper.selectById(Long.valueOf(ids.get(1).toString()));
        if (review == null || !review.getTermId().toString().equals(ids.get(0).toString())
                || !"APPROVED".equals(review.getAction())
                || !Objects.equals(review.getContentHash(), marker.get("sourceHash"))
                || !workflowService.factValid(review.getTermId(), review.getContentHash()))
            throw new BusinessException(409, "术语来源已撤回、变更或缺少有效审核，请重新生成草稿");
        var current = workflowService.approvedFact(review.getTermId());
        if (!Objects.equals(current.get("reviewId"), review.getId())
                || !String.valueOf(current.get("revision")).equals(String.valueOf(marker.get("sourceRevision"))))
            throw new BusinessException(409, "术语审核修订已变化，请重新生成草稿");
        @SuppressWarnings("unchecked") var columns = (List<TermColumnVO>) current.get("columns");
        if (columns.isEmpty() || columns.stream().anyMatch(c -> !datasourceId.equals(c.datasourceId()) || !snapshotId.equals(c.snapshotId())))
            throw new BusinessException(409, "术语字段映射不适用本次数据源和快照");
        var dependencies = new TreeSet<String>();
        columns.forEach(c -> { dependencies.add("table:" + c.tableName()); dependencies.add("column:" + c.tableName() + "." + c.columnName()); });
        var expected = new LinkedHashMap<String, Object>();
        expected.put("dependencies", new ArrayList<>(dependencies));
        expected.put("name", current.get("displayName") == null ? current.get("name") : current.get("displayName"));
        expected.put("description", current.get("description")); expected.put("synonyms", current.get("synonyms"));
        if (!new HashSet<>((List<?>) expected.get("dependencies")).equals(new HashSet<>((List<?>) marker.get("dependencies")))
                || !body(expected).equals(text.strip()) || !"APPROVED".equals(marker.get("reviewStatus")))
            throw new BusinessException("术语事实的正文、依赖或审核标记与审核来源不一致");
    }

    public boolean membershipValid(List<String> ids) {
        try {
            if (ids.size() != 2) return false;
            var review = reviewMapper.selectById(Long.valueOf(ids.get(1)));
            return review != null && "APPROVED".equals(review.getAction())
                    && review.getTermId().toString().equals(ids.get(0))
                    && workflowService.factValid(review.getTermId(), review.getContentHash())
                    && Objects.equals(workflowService.approvedFact(review.getTermId()).get("reviewId"), review.getId());
        } catch (RuntimeException ex) { return false; }
    }
}
