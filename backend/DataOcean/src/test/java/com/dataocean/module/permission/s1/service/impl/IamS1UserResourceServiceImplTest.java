package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.entity.IamS1FieldProtection;
import com.dataocean.module.permission.s1.entity.IamS1SnapshotOption;
import com.dataocean.module.permission.s1.entity.IamS1TableOptionFact;
import com.dataocean.module.permission.s1.entity.vo.IamS1ColumnOptionVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1TableOptionVO;
import com.dataocean.module.permission.s1.mapper.IamS1CapabilityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DatasourceIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1FieldProtectionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1ResourceOptionMapper;
import com.dataocean.module.permission.s1.service.IamS1DataAuthorizationResolver;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户侧资源选择：普通问数用户不需要后台“负责源”。
 * <p>
 * QUERY 的可见性必须来自“主体命中且当前有效的数据授权”（复用统一 Resolver），
 * APPLY 只要求“使用问数” + 已发布快照。
 * </p>
 */
class IamS1UserResourceServiceImplTest {

    @Test
    void queryScopeOnlyShowsDatasourcesWithEffectiveAllowGrant() {
        Fixture fixture = new Fixture();
        when(fixture.capabilityMapper.selectAllEnabledDatasources())
                .thenReturn(List.of(Map.of("id", 5L, "name", "销售库"), Map.of("id", 6L, "name", "人事库")));
        when(fixture.dataAuthorizationResolver.hasEffectiveAllowGrant(eq(2L), eq(5L), any(LocalDateTime.class)))
                .thenReturn(true);
        when(fixture.dataAuthorizationResolver.hasEffectiveAllowGrant(eq(2L), eq(6L), any(LocalDateTime.class)))
                .thenReturn(false);

        List<IamS1DatasourceRefVO> datasources = fixture.service.datasources(2L, "QUERY");

        assertThat(datasources).extracting(IamS1DatasourceRefVO::id).containsExactly(5L);
        // 用户侧资源选择不得读取后台负责源关系
        verify(fixture.adminGuard, never()).requireDatasourceFunction(any(), any(), any());
    }

    @Test
    void applyScopeShowsDatasourcesWithPublishedSnapshotWithoutAnyGrant() {
        Fixture fixture = new Fixture();
        when(fixture.resourceOptionMapper.selectEnabledDatasourcesWithPublishedSnapshot())
                .thenReturn(List.of(Map.of("id", 5L, "name", "销售库")));

        List<IamS1DatasourceRefVO> datasources = fixture.service.datasources(2L, "APPLY");

        assertThat(datasources).extracting(IamS1DatasourceRefVO::id).containsExactly(5L);
        verify(fixture.dataAuthorizationResolver, never())
                .hasEffectiveAllowGrant(any(), any(), any(LocalDateTime.class));
    }

    @Test
    void queryScopeRefusesTablesWhenRequesterHasNoGrantOnThatDatasource() {
        Fixture fixture = new Fixture();
        when(fixture.datasourceIdentityMapper.countEnabledDatasource(5L)).thenReturn(1L);
        when(fixture.dataAuthorizationResolver.hasEffectiveAllowGrant(eq(2L), eq(5L), any(LocalDateTime.class)))
                .thenReturn(false);

        Throwable thrown = catchThrowable(() -> fixture.service.tables(2L, "QUERY", 5L, 88L));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("访问申请");
        verify(fixture.resourceOptionMapper, never()).selectTables(any(), any());
    }

    @Test
    void applyScopeAllowsTableListingWithOnlyQueryUseAndMarksBlockedNonSelectable() {
        Fixture fixture = new Fixture();
        when(fixture.datasourceIdentityMapper.countEnabledDatasource(5L)).thenReturn(1L);
        when(fixture.resourceOptionMapper.selectPublishedSnapshots(5L))
                .thenReturn(List.of(snapshot(88L)));
        when(fixture.resourceOptionMapper.countPublishedSnapshot(5L, 88L)).thenReturn(1L);
        IamS1TableOptionFact blocked = new IamS1TableOptionFact();
        blocked.setDatasourceId(5L);
        blocked.setSnapshotId(88L);
        blocked.setTableName("legacy_orders");
        blocked.setGovernanceStatus("BLOCKED");
        IamS1TableOptionFact normal = new IamS1TableOptionFact();
        normal.setDatasourceId(5L);
        normal.setSnapshotId(88L);
        normal.setTableName("orders");
        normal.setGovernanceStatus("RELEASED");
        when(fixture.resourceOptionMapper.selectTables(5L, 88L)).thenReturn(List.of(blocked, normal));
        when(fixture.resourceOptionMapper.selectColumns(5L, 88L, "legacy_orders")).thenReturn(List.of());
        when(fixture.resourceOptionMapper.selectColumns(5L, 88L, "orders")).thenReturn(List.of());

        List<IamS1TableOptionVO> tables = fixture.service.tables(2L, "APPLY", 5L, 88L);

        assertThat(tables).hasSize(2);
        assertThat(tables.stream().filter(IamS1TableOptionVO::selectable))
                .extracting(IamS1TableOptionVO::tableName).containsExactly("orders");
    }

    @Test
    void hiddenColumnIsNotSelectableForQueryOrApply() {
        Fixture fixture = new Fixture();
        when(fixture.datasourceIdentityMapper.countEnabledDatasource(5L)).thenReturn(1L);
        when(fixture.dataAuthorizationResolver.hasEffectiveAllowGrant(eq(2L), eq(5L), any(LocalDateTime.class)))
                .thenReturn(true);
        when(fixture.resourceOptionMapper.countPublishedSnapshot(5L, 88L)).thenReturn(1L);
        com.dataocean.module.permission.s1.entity.IamS1ColumnOptionFact hidden =
                new com.dataocean.module.permission.s1.entity.IamS1ColumnOptionFact();
        hidden.setId(11L);
        hidden.setColumnName("secret");
        hidden.setDataType("VARCHAR(64)");
        hidden.setGovernanceStatus("RELEASED");
        when(fixture.resourceOptionMapper.selectColumns(5L, 88L, "orders")).thenReturn(List.of(hidden));
        IamS1FieldProtection protection = new IamS1FieldProtection();
        protection.setColumnMetaId(11L);
        protection.setColumnName("secret");
        protection.setProtectionLevel("HIDDEN");
        when(fixture.fieldProtectionMapper.selectActiveBySnapshot("IAM-SIMPLE-1", 5L, 88L))
                .thenReturn(List.of(protection));
        // 整表核对通过时，隐藏字段仍必须在选项中标记为不可选（不因整表通过而放行）。
        when(fixture.dataAuthorizationResolver.resolve(any())).thenReturn(allowSnapshot());

        List<IamS1ColumnOptionVO> columns = fixture.service.columns(2L, "QUERY", 5L, 88L, "orders");

        assertThat(columns).hasSize(1);
        assertThat(columns.get(0).selectable()).isFalse();
        assertThat(columns.get(0).protectionLevel()).isEqualTo("HIDDEN");
    }

    @Test
    void queryScopeHidesTablesTheRequesterCannotQuery() {
        Fixture fixture = new Fixture();
        when(fixture.datasourceIdentityMapper.countEnabledDatasource(5L)).thenReturn(1L);
        when(fixture.dataAuthorizationResolver.hasEffectiveAllowGrant(eq(2L), eq(5L), any(LocalDateTime.class)))
                .thenReturn(true);
        when(fixture.resourceOptionMapper.countPublishedSnapshot(5L, 88L)).thenReturn(1L);
        when(fixture.resourceOptionMapper.selectTables(5L, 88L)).thenReturn(List.of(tableFact("orders")));
        when(fixture.resourceOptionMapper.selectColumns(5L, 88L, "orders")).thenReturn(List.of());
        // 统一 Resolver 明确拒绝该表的任何字段：QUERY 模式不得返回该表
        when(fixture.dataAuthorizationResolver.resolve(any()))
                .thenReturn(denySnapshot("NO_ALLOW_COVERING_FIELDS"));

        assertThat(fixture.service.tables(2L, "QUERY", 5L, 88L)).isEmpty();
    }

    @Test
    void queryScopeKeepsOnlyActuallyAllowedColumns() {
        Fixture fixture = new Fixture();
        when(fixture.datasourceIdentityMapper.countEnabledDatasource(5L)).thenReturn(1L);
        when(fixture.dataAuthorizationResolver.hasEffectiveAllowGrant(eq(2L), eq(5L), any(LocalDateTime.class)))
                .thenReturn(true);
        when(fixture.resourceOptionMapper.countPublishedSnapshot(5L, 88L)).thenReturn(1L);
        com.dataocean.module.permission.s1.entity.IamS1ColumnOptionFact amount =
                columnFact(21L, "amount");
        com.dataocean.module.permission.s1.entity.IamS1ColumnOptionFact secret =
                columnFact(22L, "secret_cost");
        when(fixture.resourceOptionMapper.selectColumns(5L, 88L, "orders"))
                .thenReturn(List.of(amount, secret));
        // 整表核对失败，逐字段核对只有 amount 通过
        when(fixture.dataAuthorizationResolver.resolve(any())).thenAnswer(invocation -> {
            com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO request =
                    invocation.getArgument(0);
            var table = request.getTables().get(0);
            return table.getReferencedColumns().size() == 1 && table.getReferencedColumns().contains("amount")
                    ? allowSnapshot()
                    : denySnapshot("NO_ALLOW_COVERING_FIELDS");
        });

        List<IamS1ColumnOptionVO> columns = fixture.service.columns(2L, "QUERY", 5L, 88L, "orders");

        assertThat(columns).extracting(IamS1ColumnOptionVO::columnName).containsExactly("amount");
    }

    @Test
    void queryScopeProbeCarriesRequesterAndUsagePerProtectionLevel() {
        Fixture fixture = new Fixture();
        when(fixture.datasourceIdentityMapper.countEnabledDatasource(5L)).thenReturn(1L);
        when(fixture.dataAuthorizationResolver.hasEffectiveAllowGrant(eq(2L), eq(5L), any(LocalDateTime.class)))
                .thenReturn(true);
        when(fixture.resourceOptionMapper.countPublishedSnapshot(5L, 88L)).thenReturn(1L);
        com.dataocean.module.permission.s1.entity.IamS1ColumnOptionFact masked = columnFact(23L, "phone");
        when(fixture.resourceOptionMapper.selectColumns(5L, 88L, "users")).thenReturn(List.of(masked));
        IamS1FieldProtection protection = new IamS1FieldProtection();
        protection.setColumnMetaId(23L);
        protection.setColumnName("phone");
        protection.setProtectionLevel("MASKED");
        protection.setMaskPolicy("PHONE");
        when(fixture.fieldProtectionMapper.selectActiveBySnapshot("IAM-SIMPLE-1", 5L, 88L))
                .thenReturn(List.of(protection));
        when(fixture.dataAuthorizationResolver.resolve(any())).thenReturn(allowSnapshot());

        fixture.service.columns(2L, "QUERY", 5L, 88L, "users");

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO.class);
        verify(fixture.dataAuthorizationResolver).resolve(captor.capture());
        var forwarded = captor.getValue();
        assertThat(forwarded.getUserId()).isEqualTo(2L);
        assertThat(forwarded.getCalculatedAt()).isNotNull();
        // 脱敏字段只能直接投影，否则统一 Resolver 会判定 MASKED_FIELD_USAGE_FORBIDDEN
        assertThat(forwarded.getTables().get(0).getColumnUsages().get("phone"))
                .containsExactly(com.dataocean.module.permission.s1.enums.IamS1ColumnUsage.PROJECTION);
    }

    private IamS1TableOptionFact tableFact(String tableName) {
        IamS1TableOptionFact fact = new IamS1TableOptionFact();
        fact.setDatasourceId(5L);
        fact.setSnapshotId(88L);
        fact.setTableName(tableName);
        fact.setGovernanceStatus("RELEASED");
        return fact;
    }

    private com.dataocean.module.permission.s1.entity.IamS1ColumnOptionFact columnFact(Long id, String name) {
        com.dataocean.module.permission.s1.entity.IamS1ColumnOptionFact fact =
                new com.dataocean.module.permission.s1.entity.IamS1ColumnOptionFact();
        fact.setId(id);
        fact.setColumnName(name);
        fact.setDataType("VARCHAR(64)");
        fact.setGovernanceStatus("RELEASED");
        return fact;
    }

    private com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot allowSnapshot() {
        return new com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot(true, "ALLOWED",
                "IAM-SIMPLE-1", 2L, 5L, "销售库", 88L, 1L, LocalDateTime.now(), null, List.of());
    }

    private com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot denySnapshot(
            String reasonCode) {
        return com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot.deny(
                reasonCode, null, 1L, "销售库");
    }

    @Test
    void unknownScopeIsRejected() {
        Fixture fixture = new Fixture();

        Throwable thrown = catchThrowable(() -> fixture.service.datasources(2L, "ADMIN"));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("QUERY 或 APPLY");
    }

    private IamS1SnapshotOption snapshot(Long id) {
        IamS1SnapshotOption option = new IamS1SnapshotOption();
        option.setId(id);
        option.setDatasourceId(5L);
        option.setSnapshotVersion(3);
        option.setStatus("PUBLISHED");
        return option;
    }

    private static final class Fixture {
        private final IamS1AdminGuard adminGuard = mock(IamS1AdminGuard.class);
        private final IamS1DataAuthorizationResolver dataAuthorizationResolver =
                mock(IamS1DataAuthorizationResolver.class);
        private final IamS1CapabilityMapper capabilityMapper = mock(IamS1CapabilityMapper.class);
        private final IamS1ResourceOptionMapper resourceOptionMapper = mock(IamS1ResourceOptionMapper.class);
        private final IamS1FieldProtectionMapper fieldProtectionMapper = mock(IamS1FieldProtectionMapper.class);
        private final IamS1DatasourceIdentityMapper datasourceIdentityMapper =
                mock(IamS1DatasourceIdentityMapper.class);
        private final IamS1UserResourceServiceImpl service = new IamS1UserResourceServiceImpl(
                adminGuard, dataAuthorizationResolver, capabilityMapper, resourceOptionMapper,
                fieldProtectionMapper, datasourceIdentityMapper);
    }
}
