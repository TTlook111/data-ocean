package com.dataocean.module.fieldsemantic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.fieldsemantic.entity.po.SemanticCheckBatch;
import com.dataocean.module.fieldsemantic.entity.po.SemanticCheckResult;
import com.dataocean.module.fieldsemantic.mapper.SemanticCheckBatchMapper;
import com.dataocean.module.fieldsemantic.mapper.SemanticCheckResultMapper;
import com.dataocean.module.fieldsemantic.service.FieldSemanticEvaluation;
import com.dataocean.module.fieldsemantic.service.FieldSemanticService;
import com.dataocean.module.governance.entity.po.MetadataQualityIssue;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Isolated result/issue writer. It never calls SOURCE scoring or governance status services. */
@Service
@RequiredArgsConstructor
public class SemanticCheckExecutionService {
    private final SemanticCheckBatchMapper batchMapper;
    private final SemanticCheckResultMapper resultMapper;
    private final MetadataQualityIssueMapper issueMapper;
    private final MetadataSnapshotMapper snapshotMapper;
    private final DbTableMetaMapper tableMapper;
    private final DbColumnMetaMapper columnMapper;
    private final FieldSemanticService semanticService;

    @Transactional
    public void execute(Long batchId) {
        SemanticCheckBatch batch = batchMapper.selectById(batchId);
        if (batch == null || !"RUNNING".equals(batch.getStatus())) throw new BusinessException(409, "语义复查批次不存在或状态已变化");
        MetadataSnapshot snapshot = snapshotMapper.selectById(batch.getSnapshotId());
        if (snapshot == null || !batch.getDatasourceId().equals(snapshot.getDatasourceId())) throw new BusinessException(409, "快照来源已变化");
        List<DbTableMeta> tables = tableMapper.selectList(new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getDatasourceId, batch.getDatasourceId())
                .eq(DbTableMeta::getSnapshotId, batch.getSnapshotId())
                .orderByAsc(DbTableMeta::getTableName));
        List<DbColumnMeta> columns = columnMapper.selectList(new LambdaQueryWrapper<DbColumnMeta>()
                .eq(DbColumnMeta::getDatasourceId, batch.getDatasourceId())
                .eq(DbColumnMeta::getSnapshotId, batch.getSnapshotId())
                .orderByAsc(DbColumnMeta::getTableName).orderByAsc(DbColumnMeta::getOrdinalPosition));
        if (tables.isEmpty() || columns.isEmpty()) throw new BusinessException(409, "所选快照没有可复查的字段目录");
        var tableById = tables.stream().collect(java.util.stream.Collectors.toMap(DbTableMeta::getId, item -> item));
        int applicable = 0, valid = 0, missing = 0, stale = 0, notApplicable = 0;
        for (DbColumnMeta column : columns) {
            DbTableMeta table = tableById.get(column.getTableMetaId());
            if (table == null) throw new BusinessException(409, "快照字段缺少所属表来源");
            FieldSemanticEvaluation evaluation = semanticService.evaluate(column.getId(), batch.getSnapshotId());
            SemanticCheckResult result = new SemanticCheckResult();
            result.setBatchId(batch.getId()); result.setDefinitionId(evaluation.definitionId());
            result.setBindingId(evaluation.bindingId()); result.setColumnMetaId(column.getId());
            result.setTableName(table.getTableName()); result.setColumnName(column.getColumnName());
            result.setRuleVersion(batch.getRuleVersion()); result.setRevisionId(evaluation.revisionId());
            result.setReviewId(evaluation.reviewId()); result.setEvidenceHash(evaluation.evidenceHash());
            result.setReason(evaluation.reason()); result.setCreatedAt(LocalDateTime.now());
            if (evaluation.usable()) {
                result.setOutcome("VALID"); result.setRuleCode("FIELD_SEMANTIC_VALID"); valid++; applicable++;
            } else if ("NOT_APPLICABLE".equals(evaluation.outcome())) {
                result.setOutcome("NOT_APPLICABLE"); result.setRuleCode("FIELD_SEMANTIC_NOT_APPLICABLE"); notApplicable++;
            } else {
                result.setOutcome("ISSUE"); result.setRuleCode(evaluation.ruleCode()); applicable++;
                if ("MISSING_FIELD_SEMANTIC".equals(evaluation.ruleCode())) missing++; else stale++;
            }
            resultMapper.insert(result);
            attachOrCreateSemanticIssue(batch, column, table, evaluation, result);
        }
        batch.setApplicableCount(applicable); batch.setValidCount(valid); batch.setMissingCount(missing);
        batch.setStaleCount(stale); batch.setNotApplicableCount(notApplicable);
        batch.setStatus("SUCCESS"); batch.setFinishedAt(LocalDateTime.now());
        batchMapper.updateById(batch);
    }

    private void attachOrCreateSemanticIssue(SemanticCheckBatch batch, DbColumnMeta column, DbTableMeta table,
                                             FieldSemanticEvaluation evaluation, SemanticCheckResult result) {
        String semanticKey = batch.getSnapshotId() + ":" + column.getId();
        MetadataQualityIssue issue = issueMapper.selectOne(new LambdaQueryWrapper<MetadataQualityIssue>()
                .eq(MetadataQualityIssue::getIssueDomain, MetadataQualityIssue.DOMAIN_SEMANTIC)
                .eq(MetadataQualityIssue::getSemanticKey, semanticKey));
        boolean isIssue = "ISSUE".equals(result.getOutcome());
        if (issue == null && !isIssue) return;
        if (issue == null) {
            issue = new MetadataQualityIssue();
            issue.setSnapshotId(batch.getSnapshotId()); issue.setDatasourceId(batch.getDatasourceId());
            issue.setRuleId(null); issue.setIssueDomain(MetadataQualityIssue.DOMAIN_SEMANTIC);
            issue.setSemanticKey(semanticKey); issue.setStatus(MetadataQualityIssue.STATUS_OPEN);
            issue.setCreatedAt(LocalDateTime.now());
        } else if (isIssue && (MetadataQualityIssue.STATUS_RESOLVED.equals(issue.getStatus())
                || MetadataQualityIssue.STATUS_REJECTED.equals(issue.getStatus())
                || MetadataQualityIssue.STATUS_AUTO_CLOSED.equals(issue.getStatus()))) {
            String oldStatus = issue.getStatus();
            int updated = issueMapper.update(null, new LambdaUpdateWrapper<MetadataQualityIssue>()
                    .eq(MetadataQualityIssue::getId, issue.getId())
                    .eq(MetadataQualityIssue::getStatus, oldStatus)
                    .set(MetadataQualityIssue::getStatus, MetadataQualityIssue.STATUS_REOPENED)
                    .set(MetadataQualityIssue::getResolvedBy, null)
                    .set(MetadataQualityIssue::getResolvedAt, null)
                    .set(MetadataQualityIssue::getResolutionNote, null)
                    .set(MetadataQualityIssue::getUpdatedAt, LocalDateTime.now()));
            if (updated != 1) throw new BusinessException(409, "语义问题状态已变化，请重新复查");
            issue = issueMapper.selectById(issue.getId());
        }
        issue.setTableName(table.getTableName()); issue.setColumnName(column.getColumnName());
        issue.setColumnMetaId(column.getId()); issue.setSemanticCheckBatchId(batch.getId());
        issue.setSemanticCheckResultId(result.getId());
        if (isIssue) issue.setSemanticRuleCode(evaluation.ruleCode());
        issue.setDimension("SEMANTIC"); issue.setSeverity("LOW");
        issue.setUpdatedAt(LocalDateTime.now());
        if (isIssue) {
            issue.setIssueDescription("MISSING_FIELD_SEMANTIC".equals(evaluation.ruleCode())
                    ? "字段缺少经过审核且适用于当前快照的业务解释。"
                    : "字段的已审核解释、来源绑定或完整依赖已失效，需要复核。" );
            issue.setSuggestion("请在字段治理的业务解释工作面补充或复核解释；此问题不影响来源质量评分或快照发布门禁。");
        }
        if (issue.getId() == null) issueMapper.insert(issue);
        else issueMapper.updateById(issue);
        result.setIssueId(issue.getId());
        resultMapper.updateById(result);
    }
}
