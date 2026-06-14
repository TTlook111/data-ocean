package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.user.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityIssueServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MetadataQualityIssue.class
        );
    }

    @Test
    void listIssuesDoesNotApplySnapshotFilterWhenSnapshotIdIsMissing() {
        MetadataQualityIssueMapper issueMapper = mock(MetadataQualityIssueMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        QualityIssueServiceImpl service = new QualityIssueServiceImpl(issueMapper, userMapper, datasourceMapper);

        when(issueMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenReturn(new Page<MetadataQualityIssue>().setRecords(List.of()));

        service.listIssues(null, null, null, null, null, 1, 20);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Wrapper<MetadataQualityIssue>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(issueMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getCustomSqlSegment())
                .doesNotContain("snapshot_id");
    }

    @Test
    void handleIssueRejectsReopenedToResolvedWithoutConfirmation() {
        MetadataQualityIssueMapper issueMapper = mock(MetadataQualityIssueMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        QualityIssueServiceImpl service = new QualityIssueServiceImpl(issueMapper, userMapper, datasourceMapper);
        MetadataQualityIssue issue = new MetadataQualityIssue();
        issue.setId(1L);
        issue.setStatus(MetadataQualityIssue.STATUS_REOPENED);
        when(issueMapper.selectById(1L)).thenReturn(issue);

        assertThatThrownBy(() -> service.handleIssue(1L, MetadataQualityIssue.STATUS_RESOLVED, "done", 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许从 REOPENED 转换到 RESOLVED");
        verify(issueMapper, never()).updateById(any(MetadataQualityIssue.class));
    }

    @Test
    void handleIssueAllowsReopenedToConfirmed() {
        MetadataQualityIssueMapper issueMapper = mock(MetadataQualityIssueMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        QualityIssueServiceImpl service = new QualityIssueServiceImpl(issueMapper, userMapper, datasourceMapper);
        MetadataQualityIssue issue = new MetadataQualityIssue();
        issue.setId(1L);
        issue.setStatus(MetadataQualityIssue.STATUS_REOPENED);
        when(issueMapper.selectById(1L)).thenReturn(issue);

        service.handleIssue(1L, MetadataQualityIssue.STATUS_CONFIRMED, null, 99L);

        assertThat(issue.getStatus()).isEqualTo(MetadataQualityIssue.STATUS_CONFIRMED);
        verify(issueMapper).updateById(issue);
    }
}
