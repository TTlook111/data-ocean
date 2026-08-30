package com.dataocean.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.system.entity.SysOperationLog;
import com.dataocean.module.system.entity.dto.OperationLogQueryDTO;
import com.dataocean.module.system.mapper.SysOperationLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 操作日志多条件查询单测。
 */
class OperationLogServiceImplTest {

    private final SysOperationLogMapper mapper = mock(SysOperationLogMapper.class);
    private final OperationLogServiceImpl service = new OperationLogServiceImpl(mapper);

    @Test
    void listLogsWithEmptyQueryAppliesNoFilter() {
        when(mapper.selectPage(any(), any())).thenReturn(new Page<>());

        service.listLogs(new OperationLogQueryDTO());

        ArgumentCaptor<LambdaQueryWrapper<SysOperationLog>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        ArgumentCaptor<Page<SysOperationLog>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(mapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());

        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(20);
        assertThat(wrapperCaptor.getValue().getCustomSqlSegment())
                .doesNotContain("=")
                .doesNotContain("LIKE");
    }

    @Test
    void listLogsAppliesAllFilters() {
        when(mapper.selectPage(any(), any())).thenReturn(new Page<>());

        OperationLogQueryDTO q = new OperationLogQueryDTO();
        q.setOperatorName("admin");
        q.setOperationType("CREATE");
        q.setIsSuccess(true);
        q.setStartTime("2026-08-01 00:00:00");
        q.setEndTime("2026-08-14 23:59:59");
        q.setIpAddress("127.0.0.1");
        q.setRequestPath("/api/admin/datasources");
        q.setTargetId("5");
        q.setKeyword("datasource");

        service.listLogs(q);

        ArgumentCaptor<LambdaQueryWrapper<SysOperationLog>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(), wrapperCaptor.capture());
        String sql = wrapperCaptor.getValue().getCustomSqlSegment();

        assertThat(sql)
                .containsIgnoringCase("operator_name LIKE")
                .containsIgnoringCase("operation_type =")
                .containsIgnoringCase("is_success =")
                .containsIgnoringCase("created_at BETWEEN")
                .containsIgnoringCase("ip_address LIKE")
                .containsIgnoringCase("request_path LIKE")
                .containsIgnoringCase("target_id =")
                // keyword 跨字段 OR 组
                .containsIgnoringCase("target_resource LIKE");
    }

    @Test
    void listLogsHonorsPageOverrides() {
        when(mapper.selectPage(any(), any())).thenReturn(new Page<>());

        OperationLogQueryDTO q = new OperationLogQueryDTO();
        q.setPage(3);
        q.setPageSize(50);
        q.setIsSuccess(false);

        service.listLogs(q);

        ArgumentCaptor<Page<SysOperationLog>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<LambdaQueryWrapper<SysOperationLog>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());

        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(3);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(50);
        assertThat(wrapperCaptor.getValue().getCustomSqlSegment()).containsIgnoringCase("is_success =");
    }

    @Test
    void listLogsIgnoresInvalidTimeRangeWhenStartAfterEnd() {
        when(mapper.selectPage(any(), any())).thenReturn(new Page<>());

        OperationLogQueryDTO q = new OperationLogQueryDTO();
        q.setStartTime("2026-08-14 00:00:00");
        q.setEndTime("2026-08-01 00:00:00");

        service.listLogs(q);

        ArgumentCaptor<LambdaQueryWrapper<SysOperationLog>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getCustomSqlSegment())
                .doesNotContainIgnoringCase("created_at BETWEEN")
                .doesNotContainIgnoringCase("created_at >=")
                .doesNotContainIgnoringCase("created_at <=");
    }

    @Test
    void listLogsAppliesEndBoundWhenStartUnparseable() {
        when(mapper.selectPage(any(), any())).thenReturn(new Page<>());

        OperationLogQueryDTO q = new OperationLogQueryDTO();
        q.setStartTime("not-a-date");
        q.setEndTime("2026-08-14 23:59:59");

        service.listLogs(q);

        ArgumentCaptor<LambdaQueryWrapper<SysOperationLog>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getCustomSqlSegment())
                .containsIgnoringCase("created_at <=")
                .doesNotContain("created_at >=");
    }
}
