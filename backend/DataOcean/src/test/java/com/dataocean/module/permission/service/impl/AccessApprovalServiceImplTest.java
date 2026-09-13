package com.dataocean.module.permission.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.service.SchemaSnapshotService;
import com.dataocean.module.permission.entity.AccessApprovalRequest;
import com.dataocean.module.permission.mapper.AccessApprovalRequestMapper;
import com.dataocean.module.permission.mapper.DatasourceAccessPolicyMapper;
import com.dataocean.module.permission.mapper.PermissionChangeLogMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据访问审批服务测试。
 * <p>
 * 重点覆盖列表范围：该接口原先没有任何权限限制，任何登录用户都能列出全部申请
 * （含他人申请理由与表字段范围），前端隐藏菜单不构成安全边界（开发指导 §3.12）。
 * </p>
 */
class AccessApprovalServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AccessApprovalRequest.class
        );
    }

    @Test
    void listRequestsAppliesRequesterFilterWhenProvided() {
        AccessApprovalRequestMapper mapper = mock(AccessApprovalRequestMapper.class);
        when(mapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenReturn(new Page<AccessApprovalRequest>().setRecords(List.of()));

        AccessApprovalServiceImpl service = newService(mapper);
        service.listRequests(null, null, 42L, 1, 20);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Wrapper<AccessApprovalRequest>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(Page.class), captor.capture());
        // 非管理员的范围收窄必须落到 SQL，而不是取回后在内存里过滤——
        // 否则分页条数与实际可见条数不一致
        assertThat(captor.getValue().getCustomSqlSegment()).contains("requester_id");
    }

    @Test
    void listRequestsDoesNotFilterRequesterWhenNull() {
        AccessApprovalRequestMapper mapper = mock(AccessApprovalRequestMapper.class);
        when(mapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenReturn(new Page<AccessApprovalRequest>().setRecords(List.of()));

        AccessApprovalServiceImpl service = newService(mapper);
        // 管理员视角：requesterId 为 null 表示查看全量审批队列
        service.listRequests(null, null, null, 1, 20);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Wrapper<AccessApprovalRequest>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getCustomSqlSegment()).doesNotContain("requester_id");
    }

    private AccessApprovalServiceImpl newService(AccessApprovalRequestMapper mapper) {
        AccessApprovalServiceImpl service = new AccessApprovalServiceImpl(
                mock(DatasourceAccessPolicyMapper.class),
                mock(PermissionChangeLogMapper.class),
                mock(ApplicationEventPublisher.class),
                mock(DbTableMetaMapper.class),
                mock(DbColumnMetaMapper.class),
                mock(SchemaSnapshotService.class));
        // ServiceImpl 的 baseMapper 是父类字段，非构造注入
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        return service;
    }
}
