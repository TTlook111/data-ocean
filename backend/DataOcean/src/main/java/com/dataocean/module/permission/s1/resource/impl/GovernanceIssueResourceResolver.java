package com.dataocean.module.permission.s1.resource.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolver;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * GOVERNANCE_ISSUE：质量问题的真实归属。
 *
 * <p>问题行上同时存了 `datasource_id` 与 `snapshot_id`。归属以**快照的真实 datasourceId** 为准，
 * 并校验问题行上的 datasourceId 与之一致；两者任一缺失或彼此不一致都属于事实断链，
 * 直接 409 拒绝，而不是挑一个用、也不是回退成“信任快照”。</p>
 *
 * <p>为什么缺少问题行的 datasourceId 也要拒绝：`metadata_quality_issue.datasource_id`
 * 自 V11 迁移起就是 `NOT NULL`，为空不是正常历史形态而是事实缺失。若在这里容忍它，
 * 一个归属被清空/写坏的问题行仍会被判定为“属于快照的数据源”并放行，
 * 与冻结规则「datasourceId 或 snapshotId 缺失即拒绝」直接冲突。</p>
 */
@Component
@RequiredArgsConstructor
public class GovernanceIssueResourceResolver implements IamS1ResourceResolver {

    private final MetadataQualityIssueMapper issueMapper;
    private final MetadataSnapshotMapper snapshotMapper;

    @Override
    public IamS1ResourceType supports() {
        return IamS1ResourceType.GOVERNANCE_ISSUE;
    }

    @Override
    public IamS1ResolvedResource resolve(Object resourceId) {
        Long id = IamS1ResourceIds.asLong(resourceId);
        if (id == null) {
            throw new BusinessException(400, "缺少质量问题参数，无法判定负责范围");
        }
        MetadataQualityIssue issue = issueMapper.selectById(id);
        if (issue == null) {
            throw new BusinessException(404, "质量问题不存在");
        }
        Long snapshotId = issue.getSnapshotId();
        if (snapshotId == null) {
            // 归属断链：不能当成“无归属即可放行”。
            throw new BusinessException(409, "质量问题缺少快照归属，无法判定负责范围");
        }
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null || snapshot.getDatasourceId() == null) {
            throw new BusinessException(409, "质量问题关联的快照归属不完整，无法判定负责范围");
        }
        Long issueDatasourceId = issue.getDatasourceId();
        if (issueDatasourceId == null) {
            // 该列自 V11 起就是 NOT NULL：为空说明归属缺失，必须 fail-closed。
            throw new BusinessException(409, "质量问题缺少数据源归属，无法判定负责范围");
        }
        if (!issueDatasourceId.equals(snapshot.getDatasourceId())) {
            throw new BusinessException(409, "质量问题的数据源归属与快照不一致，拒绝判定");
        }
        return new IamS1ResolvedResource(IamS1ResourceType.GOVERNANCE_ISSUE, id,
                snapshot.getDatasourceId(), snapshotId, null, null);
    }
}
