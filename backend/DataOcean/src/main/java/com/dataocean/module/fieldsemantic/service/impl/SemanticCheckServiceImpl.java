package com.dataocean.module.fieldsemantic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.fieldsemantic.entity.po.SemanticCheckBatch;
import com.dataocean.module.fieldsemantic.entity.vo.SemanticCheckBatchVO;
import com.dataocean.module.fieldsemantic.mapper.SemanticCheckBatchMapper;
import com.dataocean.module.fieldsemantic.service.SemanticCheckService;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Permission and durable batch boundary for isolated SEMANTIC checks. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCheckServiceImpl implements SemanticCheckService {
    public static final int RULE_VERSION = 2;
    private final MetadataSnapshotMapper snapshotMapper;
    private final SemanticCheckBatchMapper batchMapper;
    private final SemanticCheckExecutionService executionService;
    private final IamS1AdminGuard adminGuard;

    @Override
    public SemanticCheckBatchVO run(Long snapshotId, Long userId) {
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) throw new BusinessException(404, "快照不存在");
        if (snapshot.getDatasourceId() == null) throw new BusinessException(409, "快照缺少数据源归属");
        adminGuard.requireDatasourceFunction(userId, "governance:check", snapshot.getDatasourceId());
        SemanticCheckBatch batch = new SemanticCheckBatch();
        batch.setDatasourceId(snapshot.getDatasourceId()); batch.setSnapshotId(snapshotId);
        batch.setStatus("RUNNING"); batch.setRuleVersion(RULE_VERSION);
        batch.setStartedAt(LocalDateTime.now()); batch.setCreatedBy(userId);
        batchMapper.insert(batch);
        try {
            executionService.execute(batch.getId());
        } catch (RuntimeException exception) {
            batch.setStatus("FAILED");
            batch.setErrorSummary("语义复查未完成，原来源质量分、治理状态和问题保持不变。");
            batch.setFinishedAt(LocalDateTime.now());
            batchMapper.updateById(batch);
            log.warn("字段语义复查失败 batchId={} snapshotId={} reason={}", batch.getId(), snapshotId, exception.getMessage());
        }
        return toVO(batchMapper.selectById(batch.getId()));
    }

    @Override
    public SemanticCheckBatchVO latest(Long snapshotId, Long userId) {
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) throw new BusinessException(404, "快照不存在");
        if (snapshot.getDatasourceId() == null) throw new BusinessException(409, "快照缺少数据源归属");
        adminGuard.requireDatasourceFunction(userId, "governance:check", snapshot.getDatasourceId());
        SemanticCheckBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<SemanticCheckBatch>()
                .eq(SemanticCheckBatch::getSnapshotId, snapshotId)
                .orderByDesc(SemanticCheckBatch::getId).last("LIMIT 1"));
        return batch == null ? null : toVO(batch);
    }

    private SemanticCheckBatchVO toVO(SemanticCheckBatch batch) {
        if (batch == null) return null;
        SemanticCheckBatchVO vo = new SemanticCheckBatchVO();
        vo.setId(batch.getId()); vo.setDatasourceId(batch.getDatasourceId()); vo.setSnapshotId(batch.getSnapshotId());
        vo.setStatus(batch.getStatus()); vo.setApplicableCount(batch.getApplicableCount());
        vo.setValidCount(batch.getValidCount()); vo.setMissingCount(batch.getMissingCount());
        vo.setStaleCount(batch.getStaleCount()); vo.setNotApplicableCount(batch.getNotApplicableCount());
        vo.setErrorSummary(batch.getErrorSummary()); vo.setRuleVersion(batch.getRuleVersion());
        vo.setStartedAt(batch.getStartedAt()); vo.setFinishedAt(batch.getFinishedAt());
        return vo;
    }
}
