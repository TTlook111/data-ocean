package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1TableRequestDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot;
import com.dataocean.module.permission.s1.enums.IamS1ColumnUsage;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1DataAuthorizationResolver;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B4：实际权限预览必须复用真实查询的同一个统一 Resolver。 */
class IamS1EffectivePermissionServiceImplTest {

    @Test
    void previewOtherUserRequiresEffectiveViewOnResponsibleDatasource() {
        Fixture fixture = new Fixture();
        doThrow(new BusinessException(403, "没有“查看用户实际权限”权限"))
                .when(fixture.adminGuard)
                .requireDatasourceFunction(eq(1L), eq("security:effective:view"), eq(5L));

        IamS1DataAuthorizationRequestDTO request = request(9L, 5L, "orders", Set.of("amount"));

        Throwable thrown = catchThrowable(() -> fixture.service.preview(1L, request));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("查看用户实际权限");
    }

    @Test
    void previewRejectsUnknownProtocol() {
        Fixture fixture = new Fixture();
        IamS1DataAuthorizationRequestDTO request = request(1L, 5L, "orders", Set.of("amount"));
        request.setProtocolVersion("LEGACY-PERMISSION");

        Throwable thrown = catchThrowable(() -> fixture.service.preview(1L, request));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("不是 IAM-SIMPLE-1 协议");
    }

    @Test
    void previewRequiresAtLeastOneTable() {
        Fixture fixture = new Fixture();
        IamS1DataAuthorizationRequestDTO request = new IamS1DataAuthorizationRequestDTO();
        request.setDatasourceId(5L);
        request.setUserId(1L);

        Throwable thrown = catchThrowable(() -> fixture.service.preview(1L, request));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("至少选择一张表和字段");
    }

    @Test
    void previewRejectsTableWithoutExplicitColumns() {
        Fixture fixture = new Fixture();
        IamS1TableRequestDTO table = new IamS1TableRequestDTO("orders", Set.of());
        IamS1DataAuthorizationRequestDTO request = new IamS1DataAuthorizationRequestDTO();
        request.setDatasourceId(5L);
        request.setUserId(1L);
        request.setTables(List.of(table));

        Throwable thrown = catchThrowable(() -> fixture.service.preview(1L, request));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("空字段不表示全部字段");
    }

    @Test
    void previewNormalizesMissingUsageToDocumentedDefaultAndForcesServerSideIdentity() {
        Fixture fixture = new Fixture();
        when(fixture.dataAuthorizationResolver.preview(any())).thenReturn(snapshot());
        IamS1DataAuthorizationRequestDTO request = request(9L, 5L, "orders", Set.of("amount", "region"));
        request.setProtocolVersion(null);

        fixture.service.preview(1L, request);

        ArgumentCaptor<IamS1DataAuthorizationRequestDTO> captor =
                ArgumentCaptor.forClass(IamS1DataAuthorizationRequestDTO.class);
        verify(fixture.dataAuthorizationResolver).preview(captor.capture());
        IamS1DataAuthorizationRequestDTO forwarded = captor.getValue();
        assertThat(forwarded.getProtocolVersion()).isEqualTo("IAM-SIMPLE-1");
        assertThat(forwarded.getUserId()).isEqualTo(9L);
        assertThat(forwarded.getCalculatedAt()).isNotNull();
        assertThat(forwarded.getTables()).hasSize(1);
        // 预览必须与问数入口使用同一套默认使用位置，否则会出现“预览通过、查询被拒”的假象。
        // 同时钉住默认集合：改动这里必须同步前端 IAM_S1_DEFAULT_QUERY_USAGES。
        assertThat(com.dataocean.module.permission.s1.support.IamS1UsageDefaults.defaults())
                .containsExactlyInAnyOrder(IamS1ColumnUsage.PROJECTION, IamS1ColumnUsage.FILTER,
                        IamS1ColumnUsage.JOIN);
        assertThat(forwarded.getTables().get(0).getColumnUsages().get("amount"))
                .containsExactlyInAnyOrderElementsOf(
                        com.dataocean.module.permission.s1.support.IamS1UsageDefaults.defaults());
        assertThat(forwarded.getTables().get(0).getColumnUsages().get("region"))
                .containsExactlyInAnyOrderElementsOf(
                        com.dataocean.module.permission.s1.support.IamS1UsageDefaults.defaults());
    }

    private IamS1DataAuthorizationSnapshot snapshot() {
        return new IamS1DataAuthorizationSnapshot(true, "ALLOWED", "IAM-SIMPLE-1", 9L, 5L, "销售库",
                88L, 7L, LocalDateTime.now(), null, List.of());
    }

    private IamS1DataAuthorizationRequestDTO request(Long userId, Long datasourceId,
                                                     String tableName, Set<String> columns) {
        IamS1DataAuthorizationRequestDTO request = new IamS1DataAuthorizationRequestDTO();
        request.setProtocolVersion("IAM-SIMPLE-1");
        request.setUserId(userId);
        request.setDatasourceId(datasourceId);
        request.setTables(List.of(new IamS1TableRequestDTO(tableName, columns)));
        return request;
    }

    private static final class Fixture {
        private final IamS1DataAuthorizationResolver dataAuthorizationResolver =
                mock(IamS1DataAuthorizationResolver.class);
        private final IamS1AuthorizationResolver authorizationResolver = mock(IamS1AuthorizationResolver.class);
        private final IamS1AdminGuard adminGuard = mock(IamS1AdminGuard.class);
        private final IamS1EffectivePermissionServiceImpl service =
                new IamS1EffectivePermissionServiceImpl(dataAuthorizationResolver, authorizationResolver,
                        adminGuard);

        {
            // 本人预览只需要“使用问数”；查看他人时走 security:effective:view + 负责源。
            when(authorizationResolver.hasGlobalFunction(1L, "query:use")).thenReturn(true);
        }
    }
}
