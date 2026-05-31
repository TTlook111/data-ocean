package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
}
