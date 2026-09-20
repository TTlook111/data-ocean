package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.fieldtag.service.ConfidenceCalculator;
import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.permission.s1.resource.impl.GovernanceIssueResourceResolver;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 4 聚焦测试：质量问题的范围下推、快照归属判定与责任人校验。
 */
class QualityIssueServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MetadataQualityIssue.class
        );
    }

    // ---------- 列表：范围下推 ----------

    @Test
    void listIssuesDoesNotApplySnapshotFilterWhenSnapshotIdIsMissing() {
        Fixture fixture = new Fixture();
        fixture.emptyPage();

        fixture.service.listIssues(null, null, null, null, null, null, 1, 20);

        // 责任人未指定时同样不得下推过滤条件
        assertThat(fixture.capturedWrapper()).doesNotContain("snapshot_id").doesNotContain("assignee_id");
    }

    @Test
    void listIssuesAppliesAssigneeFilterWhenProvided() {
        Fixture fixture = new Fixture();
        fixture.emptyPage();

        fixture.service.listIssues(null, null, null, null, null, 42L, 1, 20);

        // 责任人筛选必须下推到 SQL；取回后再过滤会让分页条数与实际匹配数不一致
        assertThat(fixture.capturedWrapper()).contains("assignee_id");
    }

    @Test
    void listIssuesInDatasourcesReturnsEmptyPageWhenTheCallerHasNoResponsibleDatasource() {
        Fixture fixture = new Fixture();

        Page<?> result = fixture.service.listIssuesInDatasources(List.of(), null,
                null, null, null, null, null, 1, 20);

        // 空负责源必须是空页，不能退化成“不过滤 = 返回全部数据源”
        assertThat(result.getTotal()).isZero();
        assertThat(result.getRecords()).isEmpty();
        verify(fixture.issueMapper, never()).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void listIssuesInDatasourcesPushesTheResponsibleScopeIntoTheSql() {
        Fixture fixture = new Fixture();
        fixture.emptyPage();

        fixture.service.listIssuesInDatasources(List.of(5L, 6L), null,
                null, null, null, null, null, 1, 20);

        // 范围必须下推：先分页再在内存过滤会让总数和分页边界出错
        assertThat(fixture.capturedWrapper()).contains("datasource_id");
    }

    @Test
    void listIssuesInDatasourcesRejectsASnapshotOutsideTheResponsibleScope() {
        Fixture fixture = new Fixture();
        // 快照 8 真实归属数据源 6，调用者只负责 5
        fixture.snapshot(8L, 6L);

        assertThatThrownBy(() -> fixture.service.listIssuesInDatasources(List.of(5L), 8L,
                null, null, null, null, null, 1, 20))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(403));

        // 直接拒绝，而不是返回一个“看起来没有数据”的空页
        verify(fixture.issueMapper, never()).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void listIssuesInDatasourcesRejectsAMissingSnapshot() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.listIssuesInDatasources(List.of(5L), 404L,
                null, null, null, null, null, 1, 20))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(404));
    }

    @Test
    void listIssuesInDatasourcesRejectsASnapshotWithoutDatasourceOwnership() {
        Fixture fixture = new Fixture();
        fixture.snapshot(8L, null);

        assertThatThrownBy(() -> fixture.service.listIssuesInDatasources(List.of(5L), 8L,
                null, null, null, null, null, 1, 20))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(409));
    }

    // ---------- 单条状态流转 ----------

    @Test
    void handleIssueRejectsReopenedToResolvedWithoutConfirmation() {
        Fixture fixture = new Fixture();
        MetadataQualityIssue issue = new MetadataQualityIssue();
        issue.setId(1L);
        issue.setStatus(MetadataQualityIssue.STATUS_REOPENED);
        when(fixture.issueMapper.selectById(1L)).thenReturn(issue);

        assertThatThrownBy(() -> fixture.service.handleIssue(1L, MetadataQualityIssue.STATUS_RESOLVED, "done", 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许从 REOPENED 转换到 RESOLVED");
        verify(fixture.issueMapper, never()).updateById(any(MetadataQualityIssue.class));
    }

    @Test
    void handleIssueAllowsReopenedToConfirmed() {
        Fixture fixture = new Fixture();
        MetadataQualityIssue issue = new MetadataQualityIssue();
        issue.setId(1L);
        issue.setStatus(MetadataQualityIssue.STATUS_REOPENED);
        when(fixture.issueMapper.selectById(1L)).thenReturn(issue);

        fixture.service.handleIssue(1L, MetadataQualityIssue.STATUS_CONFIRMED, null, 99L);

        assertThat(issue.getStatus()).isEqualTo(MetadataQualityIssue.STATUS_CONFIRMED);
        verify(fixture.issueMapper).updateById(issue);
    }

    // ---------- 批量：权限与归属预校验整批原子 ----------

    @Test
    void batchHandleRejectsWholeBatchWhenOneIssueIsNotInScope() {
        // 回归：99 个有权 + 1 个无权 → 整批零修改，不能“先改 99 条再发现越权”。
        Fixture fixture = new Fixture();

        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList();
        List<MetadataQualityIssue> issues = ids.stream().map(id -> {
            MetadataQualityIssue issue = new MetadataQualityIssue();
            issue.setId(id);
            issue.setSnapshotId(8L);
            issue.setDatasourceId(id == 100L ? 6L : 5L);
            return issue;
        }).toList();
        when(fixture.issueMapper.selectBatchIds(ids)).thenReturn(issues);
        ids.forEach(id -> when(fixture.issueResolver.resolve(id)).thenReturn(
                IamS1ResolvedResource.of(IamS1ResourceType.GOVERNANCE_ISSUE, id, id == 100L ? 6L : 5L)));
        // 第 100 个问题在另一个数据源，调用者无权
        Mockito.doThrow(new BusinessException(403, "没有负责该数据源"))
                .when(fixture.adminGuard).requireDatasourceFunction(99L, "governance:issue:manage", 6L);

        assertThatThrownBy(() -> fixture.service.batchHandle(ids, MetadataQualityIssue.STATUS_CONFIRMED, 99L))
                .isInstanceOf(BusinessException.class);

        // 整批零修改
        verify(fixture.issueMapper, never()).updateById(any(MetadataQualityIssue.class));
    }

    @Test
    void batchHandleRejectsWholeBatchWhenAnIssueIdDoesNotExist() {
        Fixture fixture = new Fixture();
        List<Long> ids = List.of(1L, 2L);
        // 只查到 1 条：数量不一致 → 整批拒绝
        when(fixture.issueMapper.selectBatchIds(ids)).thenReturn(List.of(new MetadataQualityIssue()));

        assertThatThrownBy(() -> fixture.service.batchHandle(ids, MetadataQualityIssue.STATUS_CONFIRMED, 99L))
                .isInstanceOf(BusinessException.class);
        verify(fixture.issueMapper, never()).updateById(any(MetadataQualityIssue.class));
    }

    @Test
    void batchHandleDeduplicatesRepeatedIdsBeforeChecking() {
        Fixture fixture = new Fixture();
        MetadataQualityIssue issue = new MetadataQualityIssue();
        issue.setId(1L);
        issue.setSnapshotId(8L);
        issue.setDatasourceId(5L);
        issue.setStatus(MetadataQualityIssue.STATUS_OPEN);
        when(fixture.issueMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(issue));
        when(fixture.issueResolver.resolve(1L)).thenReturn(
                IamS1ResolvedResource.of(IamS1ResourceType.GOVERNANCE_ISSUE, 1L, 5L));

        fixture.service.batchHandle(List.of(1L, 1L, 1L), MetadataQualityIssue.STATUS_CONFIRMED, 99L);

        // 去重后只读取一次、只校验一次
        verify(fixture.issueMapper).selectBatchIds(List.of(1L));
        verify(fixture.adminGuard).requireDatasourceFunction(99L, "governance:issue:manage", 5L);
    }

    // ---------- 分派 ----------

    @Test
    void assignIssueRejectsAnUnknownAssigneeWithoutWritingAnything() {
        Fixture fixture = new Fixture();
        fixture.issue(1L, MetadataQualityIssue.STATUS_OPEN);
        when(fixture.userMapper.selectById(77L)).thenReturn(null);

        assertThatThrownBy(() -> fixture.service.assignIssue(1L, 77L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(404));

        verify(fixture.issueMapper, never()).updateById(any(MetadataQualityIssue.class));
    }

    @Test
    void assignIssueRejectsADisabledAssigneeWithoutWritingAnything() {
        Fixture fixture = new Fixture();
        fixture.issue(1L, MetadataQualityIssue.STATUS_OPEN);
        fixture.user(77L, SysUser.STATUS_DISABLED);

        assertThatThrownBy(() -> fixture.service.assignIssue(1L, 77L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(400));

        // 禁用账号不能被设为负责人，否则列表里会出现无人可处理的“幽灵责任人”
        verify(fixture.issueMapper, never()).updateById(any(MetadataQualityIssue.class));
    }

    @Test
    void assignIssueWritesOnlyTheAssigneeAndCreatesNoAuthorization() {
        Fixture fixture = new Fixture();
        MetadataQualityIssue issue = fixture.issue(1L, MetadataQualityIssue.STATUS_OPEN);
        fixture.user(77L, SysUser.STATUS_NORMAL);

        fixture.service.assignIssue(1L, 77L);

        assertThat(issue.getAssigneeId()).isEqualTo(77L);
        verify(fixture.issueMapper).updateById(issue);
        // 分派是工作归属而不是数据授权：不触碰任何 S1 授权判定入口
        verify(fixture.adminGuard, never()).requireDatasourceFunction(any(), any(), anyLong());
    }

    // ---------- 夹具 ----------

    private static final class Fixture {
        private final MetadataQualityIssueMapper issueMapper = mock(MetadataQualityIssueMapper.class);
        private final UserMapper userMapper = mock(UserMapper.class);
        private final DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        private final ConfidenceCalculator confidenceCalculator = mock(ConfidenceCalculator.class);
        private final IamS1AdminGuard adminGuard = mock(IamS1AdminGuard.class);
        private final GovernanceIssueResourceResolver issueResolver = mock(GovernanceIssueResourceResolver.class);
        private final MetadataSnapshotMapper snapshotMapper = mock(MetadataSnapshotMapper.class);
        private final QualityIssueServiceImpl service = new QualityIssueServiceImpl(
                issueMapper, userMapper, datasourceMapper, snapshotMapper,
                confidenceCalculator, adminGuard, issueResolver);

        void emptyPage() {
            when(issueMapper.selectPage(any(Page.class), any(Wrapper.class)))
                    .thenReturn(new Page<MetadataQualityIssue>().setRecords(List.of()));
        }

        /** 捕获列表查询真正下推的 SQL 片段，用于断言过滤条件是否进了 SQL 而不是内存。 */
        String capturedWrapper() {
            @SuppressWarnings({"rawtypes", "unchecked"})
            ArgumentCaptor<Wrapper<MetadataQualityIssue>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(issueMapper).selectPage(any(Page.class), captor.capture());
            return captor.getValue().getCustomSqlSegment();
        }

        MetadataQualityIssue issue(Long id, String status) {
            MetadataQualityIssue issue = new MetadataQualityIssue();
            issue.setId(id);
            issue.setSnapshotId(8L);
            issue.setDatasourceId(5L);
            issue.setStatus(status);
            when(issueMapper.selectById(id)).thenReturn(issue);
            return issue;
        }

        void snapshot(Long id, Long datasourceId) {
            MetadataSnapshot snapshot = new MetadataSnapshot();
            snapshot.setId(id);
            snapshot.setDatasourceId(datasourceId);
            when(snapshotMapper.selectById(id)).thenReturn(snapshot);
        }

        void user(Long id, int status) {
            SysUser user = new SysUser();
            user.setId(id);
            user.setStatus(status);
            when(userMapper.selectById(id)).thenReturn(user);
        }
    }
}
