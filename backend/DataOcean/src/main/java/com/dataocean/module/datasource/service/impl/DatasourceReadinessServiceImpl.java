package com.dataocean.module.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.vo.DatasourceReadinessVO;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.service.DatasourceReadinessService;
import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.enums.DocStatus;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1TableRequestDTO;
import com.dataocean.module.permission.s1.enums.IamS1ColumnUsage;
import com.dataocean.module.permission.s1.entity.IamS1DataGrant;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantMapper;
import com.dataocean.module.permission.s1.service.IamS1DataAuthorizationResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /**
     * 上线阶段进度常量。
     * <p>
     * 每个阶段的进度值表示该阶段完成时的总体进度百分比。
     * 进度值设计为非线性，反映各阶段的实际工作量和重要性。
     * </p>
     */
    private static final int PROGRESS_INITIAL = 0;
    private static final int PROGRESS_CONNECTION = 15;    // 接入验证：15%
    private static final int PROGRESS_SNAPSHOT = 35;       // 采集快照：35%
    private static final int PROGRESS_GOVERNANCE = 55;     // 治理处理：55%
    private static final int PROGRESS_KNOWLEDGE = 75;      // 语义发布：75%
    private static final int PROGRESS_PERMISSION = 90;     // 权限配置：90%
    private static final int PROGRESS_ASKABLE = 100;       // 可询问：100%

    private final DatasourceMapper datasourceMapper;
    private final MetadataSnapshotMapper snapshotMapper;
    private final MetadataQualityIssueMapper qualityIssueMapper;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final IamS1DataGrantMapper iamS1DataGrantMapper;
    private final DbTableMetaMapper tableMetaMapper;
    private final DbColumnMetaMapper columnMetaMapper;
    private final IamS1DataAuthorizationResolver iamS1DataAuthorizationResolver;

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
        vo.setPermissionReady(currentUserScope
                ? currentUserCanQuery(datasourceId, publishedSnapshot)
                : hasAnyQueryGrant(datasourceId));

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
                // 同一数据源可能有多个按业务域拆分的 published skills.md；
                // 不能只按 version（通常都为 1）取到任意旧文档，优先使用最近发布的文档。
                .orderByDesc(KnowledgeDoc::getUpdatedAt)
                .orderByDesc(KnowledgeDoc::getId)
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

    /**
     * 批量查询多个快照的阻塞性问题数量。
     *
     * @param snapshotIds 快照ID列表
     * @return 快照ID到问题数量的映射
     */
    private Map<Long, Long> countBlockingIssuesBySnapshotIds(List<Long> snapshotIds) {
        Map<Long, Long> result = new HashMap<>();
        // 为每个快照单独查询问题数量（简单可靠）
        for (Long snapshotId : snapshotIds) {
            long count = qualityIssueMapper.selectCount(new LambdaQueryWrapper<MetadataQualityIssue>()
                    .eq(MetadataQualityIssue::getSnapshotId, snapshotId)
                    .eq(MetadataQualityIssue::getSeverity, "HIGH")
                    .in(MetadataQualityIssue::getStatus, BLOCKING_ISSUE_STATUSES));
            if (count > 0) {
                result.put(snapshotId, count);
            }
        }
        return result;
    }

    /**
     * 检查数据源是否有任何有效的查询授权。
     * <p>
     * 排除条件：
     * <ul>
     *   <li>已过期的授权（expires_at 不为空且已过期）</li>
     *   <li>显式拒绝的授权（access_effect = 'DENY'）</li>
     * </ul>
     * </p>
     * <p>
     * 注意：access_effect 为 null 的记录视为 ALLOW（兼容旧数据）。
     * </p>
     */
    private boolean hasAnyQueryGrant(Long datasourceId) {
        LocalDateTime now = LocalDateTime.now();
        return iamS1DataGrantMapper.selectCount(new LambdaQueryWrapper<IamS1DataGrant>()
                .eq(IamS1DataGrant::getProtocolVersion, IamS1Constants.PROTOCOL_VERSION)
                .eq(IamS1DataGrant::getDatasourceId, datasourceId)
                .eq(IamS1DataGrant::getStatus, IamS1Constants.DATA_GRANT_STATUS_ACTIVE)
                .eq(IamS1DataGrant::getEffect, IamS1Constants.EFFECT_ALLOW)
                .and(wrapper -> wrapper.isNull(IamS1DataGrant::getValidFrom)
                        .or().le(IamS1DataGrant::getValidFrom, now))
                .and(wrapper -> wrapper.isNull(IamS1DataGrant::getValidUntil)
                        .or().gt(IamS1DataGrant::getValidUntil, now))) > 0;
    }

    private boolean currentUserCanQuery(Long datasourceId, MetadataSnapshot publishedSnapshot) {
        Long userId = UserContext.currentUserId();
        if (userId == null || publishedSnapshot == null) return false;
        List<DbTableMeta> tables = tableMetaMapper.selectList(new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getDatasourceId, datasourceId)
                .eq(DbTableMeta::getSnapshotId, publishedSnapshot.getId()));
        for (DbTableMeta table : tables) {
            List<DbColumnMeta> columns = columnMetaMapper.selectList(new LambdaQueryWrapper<DbColumnMeta>()
                    .eq(DbColumnMeta::getDatasourceId, datasourceId)
                    .eq(DbColumnMeta::getSnapshotId, publishedSnapshot.getId())
                    .eq(DbColumnMeta::getTableMetaId, table.getId()));
            for (DbColumnMeta column : columns) {
                IamS1TableRequestDTO tableRequest = new IamS1TableRequestDTO(
                        table.getTableName(), java.util.Set.of(column.getColumnName()));
                tableRequest.setColumnUsages(Map.of(column.getColumnName(),
                        java.util.Set.of(IamS1ColumnUsage.PROJECTION)));
                IamS1DataAuthorizationRequestDTO request = new IamS1DataAuthorizationRequestDTO();
                request.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
                request.setUserId(userId);
                request.setDatasourceId(datasourceId);
                request.setActiveMetadataSnapshotId(publishedSnapshot.getId());
                request.setCalculatedAt(LocalDateTime.now());
                request.setTables(List.of(tableRequest));
                var decision = iamS1DataAuthorizationResolver.resolve(request);
                if (decision != null && decision.isAllowed()) return true;
            }
        }
        return false;
    }

    private void appendBlockReasons(DatasourceReadinessVO vo,
                                    Datasource datasource,
                                    long blockingIssueCount,
                                    boolean currentUserScope) {
        if (!Integer.valueOf(Datasource.STATUS_ENABLED).equals(datasource.getStatus())) {
            addReason(vo, "DATASOURCE_DISABLED", "数据源已禁用，暂不可查询", "数据管理员", "启用数据源", "/admin/data-sources/" + datasource.getId());
            return;
        }
        if (!Datasource.HEALTH_HEALTHY.equals(datasource.getHealthStatus())) {
            addReason(vo, "CONNECTION_NOT_HEALTHY", "数据源连接未通过健康检查", "数据管理员", "测试连接", "/admin/data-sources/" + datasource.getId());
        }
        if (!vo.isMetadataReady()) {
            addReason(vo, "SNAPSHOT_NOT_PUBLISHED", "尚未发布元数据快照", "治理负责人", "发布快照", "/admin/releases?datasourceId=" + datasource.getId());
        }
        if (blockingIssueCount > 0) {
            addReason(vo, "BLOCKING_GOVERNANCE_ISSUES",
                    "存在 " + blockingIssueCount + " 条高危未解决治理问题",
                    "数据管理员", "处理治理问题", "/admin/governance/issues?datasourceId=" + datasource.getId()
                            + "&snapshotId=" + vo.getPublishedSnapshotId());
        }
        if (!vo.isKnowledgeReady()) {
            addReason(vo, "KNOWLEDGE_NOT_PUBLISHED", "skills.md 尚未发布或向量化未完成", "数据分析师", "前往知识审核", "/admin/semantics/knowledge?datasourceId=" + datasource.getId() + "&tab=review");
        }
        if (!vo.isPermissionReady()) {
            addReason(vo,
                    currentUserScope ? "CURRENT_USER_NOT_GRANTED" : "QUERY_PERMISSION_NOT_CONFIGURED",
                    currentUserScope ? "你尚未获得该数据源查询权限" : "尚未配置有效查询授权",
                    "数据安全管理员",
                    currentUserScope ? "联系管理员授权" : "配置数据源权限",
                    currentUserScope ? null : "/admin/access?datasourceId=" + datasource.getId() + "&tab=grants");
        }
    }

    /**
     * 应用上线阶段。
     * <p>
     * 根据各维度的就绪状态，确定当前所处的上线阶段和进度。
     * </p>
     */
    private void applyStage(DatasourceReadinessVO vo) {
        // 所有条件都满足，标记为可询问
        if (vo.isAskable()) {
            setStage(vo, "ASKABLE", "可询问", PROGRESS_ASKABLE);
            return;
        }

        // 按优先级检查各阶段
        if (!vo.isConnectionReady()) {
            setStage(vo, "CONNECTION_CHECK_REQUIRED", "连接待处理", PROGRESS_CONNECTION);
            return;
        }
        if (!vo.isMetadataReady()) {
            setStage(vo, "SNAPSHOT_PENDING", "快照待发布", PROGRESS_SNAPSHOT);
            return;
        }
        if (!vo.isGovernanceReady()) {
            setStage(vo, "GOVERNANCE_BLOCKED", "治理阻塞", PROGRESS_GOVERNANCE);
            return;
        }
        if (!vo.isKnowledgeReady()) {
            setStage(vo, "KNOWLEDGE_PENDING", "知识待发布", PROGRESS_KNOWLEDGE);
            return;
        }
        if (!vo.isPermissionReady()) {
            setStage(vo, "PERMISSION_PENDING", "权限待配置", PROGRESS_PERMISSION);
            return;
        }

        // 未知状态
        setStage(vo, "UNKNOWN", "待确认", PROGRESS_INITIAL);
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

    @Override
    public List<DatasourceReadinessVO> getBatchAdminReadiness(List<Long> datasourceIds) {
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            return List.of();
        }

        // 批量查询数据源信息
        List<Datasource> datasources = datasourceMapper.selectBatchIds(datasourceIds);
        Map<Long, Datasource> datasourceMap = datasources.stream()
                .collect(Collectors.toMap(Datasource::getId, d -> d));

        // 批量查询快照（一次查询所有数据源的最新快照）
        Map<Long, MetadataSnapshot> snapshotMap = snapshotMapper.selectList(
                new LambdaQueryWrapper<MetadataSnapshot>()
                        .in(MetadataSnapshot::getDatasourceId, datasourceIds)
                        .eq(MetadataSnapshot::getStatus, MetadataSnapshot.STATUS_PUBLISHED)
                        .orderByDesc(MetadataSnapshot::getSnapshotVersion)
        ).stream().collect(Collectors.toMap(
                MetadataSnapshot::getDatasourceId,
                s -> s,
                (existing, replacement) -> existing.getSnapshotVersion() > replacement.getSnapshotVersion()
                        ? existing : replacement
        ));

        // 批量查询知识文档
        Map<Long, KnowledgeDoc> knowledgeMap = knowledgeDocMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDoc>()
                        .in(KnowledgeDoc::getDatasourceId, datasourceIds)
                        .eq(KnowledgeDoc::getStatus, DocStatus.PUBLISHED.name())
                        .eq(KnowledgeDoc::getDeleted, 0)
                        .orderByDesc(KnowledgeDoc::getUpdatedAt)
                        .orderByDesc(KnowledgeDoc::getId)
        ).stream().collect(Collectors.toMap(
                KnowledgeDoc::getDatasourceId,
                d -> d,
                (existing, replacement) -> {
                    LocalDateTime existingUpdated = existing.getUpdatedAt();
                    LocalDateTime replacementUpdated = replacement.getUpdatedAt();
                    if (existingUpdated == null) return replacement;
                    if (replacementUpdated == null) return existing;
                    if (existingUpdated.isAfter(replacementUpdated)) return existing;
                    if (replacementUpdated.isAfter(existingUpdated)) return replacement;
                    return existing.getId() != null && replacement.getId() != null
                            && existing.getId() >= replacement.getId() ? existing : replacement;
                }
        ));

        // 批量查询治理问题（只查询 HIGH 级别的阻塞性问题）
        List<Long> snapshotIds = snapshotMap.values().stream()
                .map(MetadataSnapshot::getId)
                .toList();
        Map<Long, Long> blockingIssueCountMap = snapshotIds.isEmpty()
                ? Map.of()
                : countBlockingIssuesBySnapshotIds(snapshotIds);

        // 构建结果
        List<DatasourceReadinessVO> results = new ArrayList<>();
        for (Long datasourceId : datasourceIds) {
            Datasource datasource = datasourceMap.get(datasourceId);
            if (datasource == null) continue;

            MetadataSnapshot snapshot = snapshotMap.get(datasourceId);
            KnowledgeDoc knowledge = knowledgeMap.get(datasourceId);
            long blockingCount = snapshot != null
                    ? blockingIssueCountMap.getOrDefault(snapshot.getId(), 0L)
                    : 0;

            DatasourceReadinessVO vo = buildReadinessFromCache(datasource, snapshot, knowledge, blockingCount);
            results.add(vo);
        }

        return results;
    }

    /**
     * 从缓存数据构建 readiness 状态（用于批量查询）。
     */
    private DatasourceReadinessVO buildReadinessFromCache(Datasource datasource,
                                                          MetadataSnapshot snapshot,
                                                          KnowledgeDoc knowledge,
                                                          long blockingIssueCount) {
        DatasourceReadinessVO vo = DatasourceReadinessVO.builder()
                .datasourceId(datasource.getId())
                .datasourceName(datasource.getName())
                .connectionReady(isConnectionReady(datasource))
                .metadataReady(snapshot != null)
                .knowledgeReady(knowledge != null)
                .build();

        if (snapshot != null) {
            vo.setPublishedSnapshotId(snapshot.getId());
            vo.setSnapshotVersion(snapshot.getSnapshotVersion());
        }
        if (knowledge != null) {
            vo.setPublishedKnowledgeDocId(knowledge.getId());
            vo.setKnowledgeVersion(knowledge.getCurrentVersion());
        }

        vo.setGovernanceReady(blockingIssueCount == 0);
        vo.setPermissionReady(hasAnyQueryGrant(datasource.getId()));

        appendBlockReasons(vo, datasource, blockingIssueCount, false);
        vo.setAskable(vo.isConnectionReady()
                && vo.isMetadataReady()
                && vo.isGovernanceReady()
                && vo.isKnowledgeReady()
                && vo.isPermissionReady());
        applyStage(vo);
        return vo;
    }
}
