package com.dataocean.module.permission.s1.resource;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.permission.s1.resource.impl.GovernanceIssueResourceResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批次 4 聚焦测试：质量问题资源的归属解析。
 *
 * <p>问题行上同时存 `datasource_id` 与 `snapshot_id`，历史数据可能写成不一致。
 * 归属以**快照的真实 datasourceId** 为准并校验二者一致；任一缺失或彼此不一致都是事实断链，
 * 一律 fail-closed（409），既不挑一个用，也不回退成“信任快照”。</p>
 */
class GovernanceIssueResourceResolverTest {

    @Test
    void resolvesDatasourceFromTheSnapshot() {
        Fixture fixture = new Fixture();
        fixture.issue(1L, 8L, 5L);
        fixture.snapshot(8L, 5L);

        IamS1ResolvedResource resolved = fixture.resolver.resolve(1L);

        assertThat(resolved.datasourceId()).isEqualTo(5L);
        assertThat(resolved.snapshotId()).isEqualTo(8L);
        assertThat(resolved.type()).isEqualTo(IamS1ResourceType.GOVERNANCE_ISSUE);
    }

    @Test
    void rejectsBlankIssueId() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.resolver.resolve(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fixture.resolver.resolve("not-a-number"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsMissingIssue() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.resolver.resolve(404L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsIssueWithoutSnapshotOwnership() {
        Fixture fixture = new Fixture();
        fixture.issue(1L, null, 5L);

        assertThatThrownBy(() -> fixture.resolver.resolve(1L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsBrokenSnapshotOwnership() {
        Fixture fixture = new Fixture();
        fixture.issue(1L, 8L, 5L);
        // 快照不存在，或没有 datasourceId
        assertThatThrownBy(() -> fixture.resolver.resolve(1L)).isInstanceOf(BusinessException.class);

        Fixture orphan = new Fixture();
        orphan.issue(1L, 8L, 5L);
        orphan.snapshot(8L, null);
        assertThatThrownBy(() -> orphan.resolver.resolve(1L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsWhenIssueDatasourceDisagreesWithSnapshot() {
        Fixture fixture = new Fixture();
        // 问题行写 datasource 5，但它所属快照真实归属 6：事实断链，拒绝
        fixture.issue(1L, 8L, 5L);
        fixture.snapshot(8L, 6L);

        assertThatThrownBy(() -> fixture.resolver.resolve(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    void rejectsNullIssueDatasourceInsteadOfTrustingTheSnapshot() {
        // 回归：`metadata_quality_issue.datasource_id` 自 V11 起是 NOT NULL，
        // 为空是事实缺失而不是正常历史形态。曾允许为空并回退成“信任快照”，
        // 等于让一个归属被写坏的问题行照样通过判定，与冻结规则冲突。
        Fixture fixture = new Fixture();
        fixture.issue(1L, 8L, null);
        fixture.snapshot(8L, 5L);

        assertThatThrownBy(() -> fixture.resolver.resolve(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getMessage()).contains("缺少数据源归属");
                });
    }

    private static final class Fixture {
        private final MetadataQualityIssueMapper issueMapper = mock(MetadataQualityIssueMapper.class);
        private final MetadataSnapshotMapper snapshotMapper = mock(MetadataSnapshotMapper.class);
        private final GovernanceIssueResourceResolver resolver =
                new GovernanceIssueResourceResolver(issueMapper, snapshotMapper);

        void issue(Long id, Long snapshotId, Long datasourceId) {
            MetadataQualityIssue issue = new MetadataQualityIssue();
            issue.setId(id);
            issue.setSnapshotId(snapshotId);
            issue.setDatasourceId(datasourceId);
            when(issueMapper.selectById(id)).thenReturn(issue);
        }

        void snapshot(Long id, Long datasourceId) {
            MetadataSnapshot snapshot = new MetadataSnapshot();
            snapshot.setId(id);
            snapshot.setDatasourceId(datasourceId);
            when(snapshotMapper.selectById(id)).thenReturn(snapshot);
        }
    }
}
