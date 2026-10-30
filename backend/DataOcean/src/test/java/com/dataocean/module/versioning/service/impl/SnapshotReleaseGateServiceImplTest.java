package com.dataocean.module.versioning.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.po.MetadataQualityIssue;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.versioning.entity.vo.SnapshotReleaseGateVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotReleaseGateServiceImplTest {

    private MetadataSnapshotMapper snapshotMapper;
    private MetadataQualityIssueMapper issueMapper;
    private DbTableMetaMapper tableMetaMapper;
    private SnapshotReleaseGateServiceImpl service;
    private MetadataSnapshot snapshot;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, MetadataSnapshot.class);
        TableInfoHelper.initTableInfo(assistant, MetadataQualityIssue.class);
        TableInfoHelper.initTableInfo(assistant, DbTableMeta.class);
    }

    @BeforeEach
    void setUp() {
        snapshotMapper = mock(MetadataSnapshotMapper.class);
        issueMapper = mock(MetadataQualityIssueMapper.class);
        tableMetaMapper = mock(DbTableMetaMapper.class);
        service = new SnapshotReleaseGateServiceImpl(snapshotMapper, issueMapper, tableMetaMapper);
        snapshot = new MetadataSnapshot();
        snapshot.setId(80L);
        snapshot.setDatasourceId(5L);
        snapshot.setStatus(MetadataSnapshot.STATUS_APPROVED);
        when(snapshotMapper.selectById(80L)).thenReturn(snapshot);
    }

    @Test
    void sourceHighBlocksApprovalAndPublishWhileSemanticHighOnlyWarns() {
        when(issueMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 1L);
        when(tableMetaMapper.selectCount(any(Wrapper.class))).thenReturn(2L);

        SnapshotReleaseGateVO gate = service.evaluate(snapshot);

        assertThat(gate.isApprovable()).isFalse();
        assertThat(gate.isPublishable()).isFalse();
        assertThat(gate.getBlockingCount()).isEqualTo(1);
        assertThat(gate.getWarningCount()).isEqualTo(1);
        assertThat(item(gate, "SOURCE_HIGH_UNRESOLVED").getStatus()).isEqualTo("FAIL");
        assertThat(item(gate, "NO_NORMAL_RECOMMENDED_TABLE").getStatus()).isEqualTo("PASS");
        assertThat(item(gate, "SEMANTIC_HIGH_UNRESOLVED").getStatus()).isEqualTo("WARN");
        assertThat(item(gate, "SEMANTIC_HIGH_UNRESOLVED").isBlocksPublish()).isFalse();
    }

    @Test
    void semanticHighAloneDoesNotBlockPublish() {
        when(issueMapper.selectCount(any(Wrapper.class))).thenReturn(0L, 1L);
        when(tableMetaMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        SnapshotReleaseGateVO gate = service.evaluate(snapshot);

        assertThat(gate.isApprovable()).isTrue();
        assertThat(gate.isPublishable()).isTrue();
        assertThat(gate.getBlockingCount()).isZero();
        assertThat(gate.getWarningCount()).isEqualTo(1);
    }

    @Test
    void noValidTableBlocksPublishButNotApproval() {
        when(issueMapper.selectCount(any(Wrapper.class))).thenReturn(0L, 0L);
        when(tableMetaMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        SnapshotReleaseGateVO gate = service.evaluate(snapshot);

        assertThat(gate.isApprovable()).isTrue();
        assertThat(gate.isPublishable()).isFalse();
        assertThat(gate.getBlockingCount()).isEqualTo(1);
        assertThat(item(gate, "NO_NORMAL_RECOMMENDED_TABLE").isBlocksApproval()).isFalse();
        assertThat(item(gate, "NO_NORMAL_RECOMMENDED_TABLE").isBlocksPublish()).isTrue();
    }

    @Test
    void sourceQueryUsesUnifiedUnresolvedStatusesAndTableQueryUsesValidStatuses() {
        when(issueMapper.selectCount(any(Wrapper.class))).thenReturn(0L, 0L);
        when(tableMetaMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        service.evaluate(snapshot);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Wrapper<MetadataQualityIssue>> issueCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(issueMapper, org.mockito.Mockito.times(2)).selectCount(issueCaptor.capture());
        // 参数映射在首次生成 SQL 片段时才填充，先逐个触发再读取。
        issueCaptor.getAllValues().forEach(Wrapper::getCustomSqlSegment);
        String sourceWhere = issueCaptor.getAllValues().get(0).getCustomSqlSegment().toLowerCase();
        assertThat(sourceWhere).contains("issue_domain", "severity", "status");
        List<Object> allParams = issueCaptor.getAllValues().stream()
                .flatMap(wrapper -> ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values().stream())
                .toList();
        assertThat(allParams).contains("SOURCE", "SEMANTIC", "HIGH",
                MetadataQualityIssue.STATUS_OPEN,
                MetadataQualityIssue.STATUS_CONFIRMED,
                MetadataQualityIssue.STATUS_REOPENED);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Wrapper<DbTableMeta>> tableCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(tableMetaMapper).selectCount(tableCaptor.capture());
        tableCaptor.getValue().getCustomSqlSegment();
        assertThat(((AbstractWrapper<?, ?, ?>) tableCaptor.getValue())
                .getParamNameValuePairs().values().toString())
                .contains("NORMAL", "RECOMMENDED");
    }

    @Test
    void missingSnapshotIsReportedAsNotFound() {
        when(snapshotMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.evaluate(404L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("快照不存在");
    }

    @Test
    void publishAssertionMergesMultipleBlockers() {
        when(issueMapper.selectCount(any(Wrapper.class))).thenReturn(2L, 0L);
        when(tableMetaMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> service.assertPublishable(snapshot))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("高风险问题")
                .hasMessageContaining("NORMAL/RECOMMENDED");
    }

    private SnapshotReleaseGateVO.GateItem item(SnapshotReleaseGateVO gate, String code) {
        return gate.getItems().stream()
                .filter(candidate -> code.equals(candidate.getCode()))
                .findFirst()
                .orElseThrow();
    }
}
