package com.dataocean.module.versioning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.constant.GovernanceStatuses;
import com.dataocean.module.governance.entity.po.MetadataQualityIssue;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.versioning.entity.vo.SnapshotReleaseGateVO;
import com.dataocean.module.versioning.service.SnapshotReleaseGateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 快照发布门禁实现。
 * <p>
 * 只有来源（SOURCE）高风险问题和可用表数量参与阻断；语义（SEMANTIC）高风险
 * 只作为提示返回，避免把字段解释问题误当成发布阻断。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class SnapshotReleaseGateServiceImpl implements SnapshotReleaseGateService {

    private static final String HIGH = "HIGH";
    private static final String ITEM_PASS = "PASS";
    private static final String ITEM_FAIL = "FAIL";
    private static final String ITEM_WARN = "WARN";

    private static final String CODE_SOURCE_HIGH = "SOURCE_HIGH_UNRESOLVED";
    private static final String CODE_NO_VALID_TABLE = "NO_NORMAL_RECOMMENDED_TABLE";
    private static final String CODE_SEMANTIC_HIGH = "SEMANTIC_HIGH_UNRESOLVED";

    private final MetadataSnapshotMapper snapshotMapper;
    private final MetadataQualityIssueMapper qualityIssueMapper;
    private final DbTableMetaMapper tableMetaMapper;

    @Override
    public SnapshotReleaseGateVO evaluate(Long snapshotId) {
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new BusinessException(404, "快照不存在");
        }
        return evaluate(snapshot);
    }

    @Override
    public SnapshotReleaseGateVO evaluate(MetadataSnapshot snapshot) {
        long sourceHigh = countUnresolvedHigh(snapshot.getId(), MetadataQualityIssue.DOMAIN_SOURCE);
        long semanticHigh = countUnresolvedHigh(snapshot.getId(), MetadataQualityIssue.DOMAIN_SEMANTIC);
        long validTables = countValidTables(snapshot.getId());

        List<SnapshotReleaseGateVO.GateItem> items = new ArrayList<>();
        items.add(sourceHighItem(sourceHigh));
        items.add(validTableItem(validTables));
        items.add(semanticHighItem(semanticHigh));

        int blockingCount = (int) items.stream().filter(item -> item.isBlocksPublish() && ITEM_FAIL.equals(item.getStatus())).count();
        int warningCount = (int) items.stream().filter(item -> ITEM_WARN.equals(item.getStatus())).count();
        boolean approvable = items.stream().noneMatch(item -> item.isBlocksApproval() && ITEM_FAIL.equals(item.getStatus()));

        SnapshotReleaseGateVO vo = new SnapshotReleaseGateVO();
        vo.setSnapshotId(snapshot.getId());
        vo.setDatasourceId(snapshot.getDatasourceId());
        vo.setSnapshotStatus(snapshot.getStatus());
        vo.setApprovable(approvable);
        vo.setPublishable(approvable
                && MetadataSnapshot.STATUS_APPROVED.equals(snapshot.getStatus())
                && blockingCount == 0);
        vo.setBlockingCount(blockingCount);
        vo.setWarningCount(warningCount);
        vo.setUnresolvedSourceHigh(sourceHigh);
        vo.setUnresolvedSemanticHigh(semanticHigh);
        vo.setValidTableCount(validTables);
        vo.setEvaluatedAt(LocalDateTime.now());
        vo.setItems(items);
        return vo;
    }

    @Override
    public void assertApprovable(MetadataSnapshot snapshot) {
        SnapshotReleaseGateVO gate = evaluate(snapshot);
        if (gate.isApprovable()) {
            return;
        }
        String message = gate.getItems().stream()
                .filter(item -> item.isBlocksApproval() && ITEM_FAIL.equals(item.getStatus()))
                .map(SnapshotReleaseGateVO.GateItem::getMessage)
                .reduce((first, second) -> first + "；" + second)
                .orElse("存在未解决的治理问题，无法审核通过");
        throw new BusinessException(409, message);
    }

    @Override
    public void assertPublishable(MetadataSnapshot snapshot) {
        SnapshotReleaseGateVO gate = evaluate(snapshot);
        if (gate.getBlockingCount() == 0) {
            return;
        }
        String message = gate.getItems().stream()
                .filter(item -> item.isBlocksPublish() && ITEM_FAIL.equals(item.getStatus()))
                .map(SnapshotReleaseGateVO.GateItem::getMessage)
                .reduce((first, second) -> first + "；" + second)
                .orElse("快照未通过发布门禁");
        throw new BusinessException(409, message);
    }

    private long countUnresolvedHigh(Long snapshotId, String domain) {
        return qualityIssueMapper.selectCount(new LambdaQueryWrapper<MetadataQualityIssue>()
                .eq(MetadataQualityIssue::getSnapshotId, snapshotId)
                .eq(MetadataQualityIssue::getIssueDomain, domain)
                .eq(MetadataQualityIssue::getSeverity, HIGH)
                .in(MetadataQualityIssue::getStatus, MetadataQualityIssue.UNRESOLVED_STATUSES));
    }

    private long countValidTables(Long snapshotId) {
        return tableMetaMapper.selectCount(new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getSnapshotId, snapshotId)
                .in(DbTableMeta::getGovernanceStatus,
                        GovernanceStatuses.NORMAL, GovernanceStatuses.RECOMMENDED));
    }

    private SnapshotReleaseGateVO.GateItem sourceHighItem(long count) {
        boolean pass = count == 0;
        SnapshotReleaseGateVO.GateItem item = new SnapshotReleaseGateVO.GateItem();
        item.setCode(CODE_SOURCE_HIGH);
        item.setCategory(MetadataQualityIssue.DOMAIN_SOURCE);
        item.setSeverity(HIGH);
        item.setStatus(pass ? ITEM_PASS : ITEM_FAIL);
        item.setBlocksApproval(!pass);
        item.setBlocksPublish(!pass);
        item.setCount(count);
        item.setMessage(pass
                ? "没有未解决的来源高风险问题"
                : "无法发布：存在 " + count + " 个未解决的高风险问题");
        return item;
    }

    private SnapshotReleaseGateVO.GateItem validTableItem(long count) {
        boolean pass = count > 0;
        SnapshotReleaseGateVO.GateItem item = new SnapshotReleaseGateVO.GateItem();
        item.setCode(CODE_NO_VALID_TABLE);
        item.setCategory("STRUCTURE");
        item.setSeverity(HIGH);
        item.setStatus(pass ? ITEM_PASS : ITEM_FAIL);
        item.setBlocksApproval(false);
        item.setBlocksPublish(!pass);
        item.setCount(count);
        item.setMessage(pass
                ? "存在 " + count + " 张 NORMAL/RECOMMENDED 状态的表"
                : "无法发布：快照中无 NORMAL/RECOMMENDED 状态的表");
        return item;
    }

    private SnapshotReleaseGateVO.GateItem semanticHighItem(long count) {
        boolean pass = count == 0;
        SnapshotReleaseGateVO.GateItem item = new SnapshotReleaseGateVO.GateItem();
        item.setCode(CODE_SEMANTIC_HIGH);
        item.setCategory(MetadataQualityIssue.DOMAIN_SEMANTIC);
        item.setSeverity(HIGH);
        item.setStatus(pass ? ITEM_PASS : ITEM_WARN);
        item.setBlocksApproval(false);
        item.setBlocksPublish(false);
        item.setCount(count);
        item.setMessage(pass
                ? "没有未解决的语义高风险问题"
                : "存在 " + count + " 个未解决的语义高风险问题，语义问题不阻断发布，请在发布前确认");
        return item;
    }
}
