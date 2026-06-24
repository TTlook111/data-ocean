package com.dataocean.module.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.DatasourceAccess;
import com.dataocean.module.datasource.entity.vo.DatasourcePermissionDecisionVO;
import com.dataocean.module.datasource.entity.vo.DatasourceReadinessVO;
import com.dataocean.module.datasource.mapper.DatasourceAccessMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.service.DatasourceAccessService;
import com.dataocean.module.datasource.service.DatasourceReadinessService;
import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.enums.DocStatus;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据源可询问状态聚合服务实现。
 */
@Service
@RequiredArgsConstructor
public class DatasourceReadinessServiceImpl implements DatasourceReadinessService {

    private static final List<String> BLOCKING_ISSUE_STATUSES = List.of(
            MetadataQualityIssue.STATUS_OPEN,
            MetadataQualityIssue.STATUS_CONFIRMED,
            MetadataQualityIssue.STATUS_REOPENED
    );

    private final DatasourceMapper datasourceMapper;
    private final MetadataSnapshotMapper snapshotMapper;
    private final MetadataQualityIssueMapper qualityIssueMapper;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final DatasourceAccessMapper accessMapper;
    private final DatasourceAccessService accessService;

    @Override
    public DatasourceReadinessVO getAdminReadiness(Long datasourceId) {
        return buildReadiness(datasourceId, false);
    }

    @Override
    public DatasourceReadinessVO getCurrentUserReadiness(Long datasourceId) {
        return buildReadiness(datasourceId, true);
    }

    private DatasourceReadinessVO buildReadiness(Long datasourceId, boolean currentUserScope) {
        Datasource datasource = datasourceMapper.selectById(datasourceId);
        if (datasource == null || (datasource.getDeleted() != null && datasource.getDeleted() != 0)) {
            throw new BusinessException(404, "数据源不存在");
        }

        MetadataSnapshot publishedSnapshot = latestPublishedSnapshot(datasourceId);
        KnowledgeDoc publishedDoc = latestPublishedKnowledgeDoc(datasourceId);

        DatasourceReadinessVO vo = DatasourceReadinessVO.builder()
                .datasourceId(datasource.getId())
                .datasourceName(datasource.getName())
                .connectionReady(isConnectionReady(datasource))
                .metadataReady(publishedSnapshot != null)
                .knowledgeReady(publishedDoc != null)
                .build();
        if (publishedSnapshot != null) {
            vo.setPublishedSnapshotId(publishedSnapshot.getId());
            vo.setSnapshotVersion(publishedSnapshot.getSnapshotVersion());
        }
        if (publishedDoc != null) {
            vo.setPublishedKnowledgeDocId(publishedDoc.getId());
            vo.setKnowledgeVersion(publishedDoc.getCurrentVersion());
        }

        long blockingIssueCount = countBlockingIssues(publishedSnapshot);
        vo.setGovernanceReady(blockingIssueCount == 0);
        vo.setPermissionReady(currentUserScope ? currentUserCanQuery(datasourceId) : hasAnyQueryGrant(datasourceId));

        appendBlockReasons(vo, datasource, blockingIssueCount, currentUserScope);
        vo.setAskable(vo.isConnectionReady()
                && vo.isMetadataReady()
                && vo.isGovernanceReady()
                && vo.isKnowledgeReady()
                && vo.isPermissionReady());
        applyStage(vo);
        return vo;
    }

    private boolean isConnectionReady(Datasource datasource) {
        return Integer.valueOf(Datasource.STATUS_ENABLED).equals(datasource.getStatus())
                && Datasource.HEALTH_HEALTHY.equals(datasource.getHealthStatus());
    }

    private MetadataSnapshot latestPublishedSnapshot(Long datasourceId) {
        return snapshotMapper.selectOne(new LambdaQueryWrapper<MetadataSnapshot>()
                .eq(MetadataSnapshot::getDatasourceId, datasourceId)
                .eq(MetadataSnapshot::getStatus, MetadataSnapshot.STATUS_PUBLISHED)
                .orderByDesc(MetadataSnapshot::getSnapshotVersion)
                .last("LIMIT 1"));
    }

    private KnowledgeDoc latestPublishedKnowledgeDoc(Long datasourceId) {
        return knowledgeDocMapper.selectOne(new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getDatasourceId, datasourceId)
                .eq(KnowledgeDoc::getStatus, DocStatus.PUBLISHED.name())
                .eq(KnowledgeDoc::getDeleted, 0)
                .orderByDesc(KnowledgeDoc::getCurrentVersion)
                .last("LIMIT 1"));
    }

    private long countBlockingIssues(MetadataSnapshot snapshot) {
        if (snapshot == null) {
            return 0L;
        }
        return qualityIssueMapper.selectCount(new LambdaQueryWrapper<MetadataQualityIssue>()
                .eq(MetadataQualityIssue::getSnapshotId, snapshot.getId())
                .eq(MetadataQualityIssue::getSeverity, "HIGH")
                .in(MetadataQualityIssue::getStatus, BLOCKING_ISSUE_STATUSES));
    }

    private boolean hasAnyQueryGrant(Long datasourceId) {
        return accessMapper.selectCount(new LambdaQueryWrapper<DatasourceAccess>()
                .eq(DatasourceAccess::getDatasourceId, datasourceId)
                .eq(DatasourceAccess::getCanQuery, true)
                .and(wrapper -> wrapper.isNull(DatasourceAccess::getExpiresAt)
                        .or()
                        .gt(DatasourceAccess::getExpiresAt, LocalDateTime.now()))
                .and(wrapper -> wrapper.isNull(DatasourceAccess::getAccessEffect)
                        .or()
                        .ne(DatasourceAccess::getAccessEffect, "DENY"))) > 0;
    }

    private boolean currentUserCanQuery(Long datasourceId) {
        DatasourcePermissionDecisionVO decision = accessService.calculateCurrentUserDecision(datasourceId);
        return decision != null && decision.isCanQuery() && !"DENY".equals(decision.getAccessEffect());
    }

    private void appendBlockReasons(DatasourceReadinessVO vo,
                                    Datasource datasource,
                                    long blockingIssueCount,
                                    boolean currentUserScope) {
        if (!Integer.valueOf(Datasource.STATUS_ENABLED).equals(datasource.getStatus())) {
            addReason(vo, "DATASOURCE_DISABLED", "数据源已禁用，暂不可查询", "数据管理员", "启用数据源", "/admin/datasources");
            return;
        }
        if (!Datasource.HEALTH_HEALTHY.equals(datasource.getHealthStatus())) {
            addReason(vo, "CONNECTION_NOT_HEALTHY", "数据源连接未通过健康检查", "数据管理员", "测试连接", "/admin/datasources");
        }
        if (!vo.isMetadataReady()) {
            addReason(vo, "SNAPSHOT_NOT_PUBLISHED", "尚未发布元数据快照", "治理负责人", "发布快照", "/admin/metadata/lifecycle");
        }
        if (blockingIssueCount > 0) {
            addReason(vo, "BLOCKING_GOVERNANCE_ISSUES",
                    "存在 " + blockingIssueCount + " 条高危未解决治理问题",
                    "数据管理员", "处理治理问题", "/admin/governance/issues");
        }
        if (!vo.isKnowledgeReady()) {
            addReason(vo, "KNOWLEDGE_NOT_PUBLISHED", "skills.md 尚未发布或向量化未完成", "数据分析师", "前往知识审核", "/admin/knowledge/review");
        }
        if (!vo.isPermissionReady()) {
            addReason(vo,
                    currentUserScope ? "CURRENT_USER_NOT_GRANTED" : "QUERY_PERMISSION_NOT_CONFIGURED",
                    currentUserScope ? "你尚未获得该数据源查询权限" : "尚未配置有效查询授权",
                    "数据安全管理员",
                    currentUserScope ? "联系管理员授权" : "配置数据源权限",
                    currentUserScope ? null : "/admin/permission/access");
        }
    }

    private void applyStage(DatasourceReadinessVO vo) {
        if (vo.isAskable()) {
            setStage(vo, "ASKABLE", "可询问", 100);
            return;
        }
        if (!vo.isConnectionReady()) {
            setStage(vo, "CONNECTION_CHECK_REQUIRED", "连接待处理", 12);
            return;
        }
        if (!vo.isMetadataReady()) {
            setStage(vo, "SNAPSHOT_PENDING", "快照待发布", 35);
            return;
        }
        if (!vo.isGovernanceReady()) {
            setStage(vo, "GOVERNANCE_BLOCKED", "治理阻塞", 50);
            return;
        }
        if (!vo.isKnowledgeReady()) {
            setStage(vo, "KNOWLEDGE_PENDING", "知识待发布", 75);
            return;
        }
        if (!vo.isPermissionReady()) {
            setStage(vo, "PERMISSION_PENDING", "权限待配置", 88);
            return;
        }
        setStage(vo, "UNKNOWN", "待确认", 0);
    }

    private void setStage(DatasourceReadinessVO vo, String stage, String stageLabel, int progress) {
        vo.setStage(stage);
        vo.setStageLabel(stageLabel);
        vo.setProgress(progress);
    }

    private void addReason(DatasourceReadinessVO vo, String code, String message,
                           String ownerRole, String actionText, String actionPath) {
        vo.getBlockReasons().add(DatasourceReadinessVO.BlockReason.builder()
                .code(code)
                .message(message)
                .ownerRole(ownerRole)
                .actionText(actionText)
                .actionPath(actionPath)
                .build());
    }
}
