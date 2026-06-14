package com.dataocean.module.permission.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataocean.module.datasource.mapper.DatasourceAccessMapper;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.service.SchemaSnapshotService;
import com.dataocean.module.permission.entity.DatasourceAccessPolicy;
import com.dataocean.module.permission.entity.vo.PermissionContextVO;
import com.dataocean.module.permission.mapper.DatasourceAccessPolicyMapper;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.mapper.RoleMapper;
import com.dataocean.module.user.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionCalculatorImplTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DbTableMeta.class
        );
    }

    @Test
    void calculateUsesUnionForDenyAndMaskPolicies() {
        TestFixture fixture = new TestFixture();
        SysRole role = new SysRole();
        role.setId(20L);
        when(fixture.roleMapper.selectByUserId(10L)).thenReturn(List.of(role));
        when(fixture.userMapper.selectDepartmentIdByUserId(10L)).thenReturn(30L);
        when(fixture.accessMapper.selectBySubject(any(), any(), any())).thenReturn(List.of());
        when(fixture.schemaSnapshotService.getPublishedSnapshot(1L)).thenReturn(null);
        when(fixture.policyMapper.selectAllByDatasourceId(1L)).thenReturn(List.of(
                policy("USER", 10L, "orders", "secret", "DENY", null, null),
                policy("ROLE", 20L, "orders", "phone", "MASK", "PHONE", null),
                policy("DEPARTMENT", 30L, "customers", null, "DENY", null, null),
                policy("USER", 10L, "orders", null, "ALLOW", null, null),
                policy("USER", 10L, "customers", null, "ALLOW", null, null)
        ));

        PermissionContextVO context = fixture.service.calculate(10L, 1L);

        assertThat(context.getDeniedColumns()).containsExactly("orders.secret");
        assertThat(context.getMaskColumns())
                .extracting(item -> item.getTableName() + "." + item.getColumnName() + ":" + item.getMaskType())
                .containsExactly("orders.phone:PHONE");
        assertThat(context.getAllowedTables()).containsExactly("orders");
        assertThat(context.getTableScopeMode()).isEqualTo("ALLOWLIST");
    }

    @Test
    void calculateTreatsAllowAllWithDenyAsWhitelistExclusion() {
        TestFixture fixture = new TestFixture();
        when(fixture.roleMapper.selectByUserId(10L)).thenReturn(List.of());
        when(fixture.userMapper.selectDepartmentIdByUserId(10L)).thenReturn(null);
        when(fixture.accessMapper.selectBySubject(any(), any(), any())).thenReturn(List.of());
        when(fixture.schemaSnapshotService.getPublishedSnapshot(1L)).thenReturn(null);
        when(fixture.policyMapper.selectAllByDatasourceId(1L)).thenReturn(List.of(
                policy("USER", 10L, "*", null, "ALLOW", null, null),
                policy("USER", 10L, "customers", null, "DENY", null, null)
        ));
        when(fixture.tableMetaMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                table("orders"),
                table("customers"),
                table("products")
        ));

        PermissionContextVO context = fixture.service.calculate(10L, 1L);

        assertThat(context.getAllowedTables()).containsExactlyInAnyOrder("orders", "products");
        assertThat(context.getTableScopeMode()).isEqualTo("ALLOWLIST");
    }

    @Test
    void calculateTreatsDenyAllAsEmptyAllowlist() {
        TestFixture fixture = new TestFixture();
        when(fixture.roleMapper.selectByUserId(10L)).thenReturn(List.of());
        when(fixture.userMapper.selectDepartmentIdByUserId(10L)).thenReturn(null);
        when(fixture.accessMapper.selectBySubject(any(), any(), any())).thenReturn(List.of());
        when(fixture.schemaSnapshotService.getPublishedSnapshot(1L)).thenReturn(null);
        when(fixture.policyMapper.selectAllByDatasourceId(1L)).thenReturn(List.of(
                policy("USER", 10L, "*", null, "DENY", null, null)
        ));

        PermissionContextVO context = fixture.service.calculate(10L, 1L);

        assertThat(context.getAllowedTables()).isEmpty();
        assertThat(context.getTableScopeMode()).isEqualTo("ALLOWLIST");
    }

    @Test
    void calculateWithoutPoliciesIsExplicitlyUnrestricted() {
        TestFixture fixture = new TestFixture();
        when(fixture.roleMapper.selectByUserId(10L)).thenReturn(List.of());
        when(fixture.userMapper.selectDepartmentIdByUserId(10L)).thenReturn(null);
        when(fixture.accessMapper.selectBySubject(any(), any(), any())).thenReturn(List.of());
        when(fixture.schemaSnapshotService.getPublishedSnapshot(1L)).thenReturn(null);
        when(fixture.policyMapper.selectAllByDatasourceId(1L)).thenReturn(List.of());

        PermissionContextVO context = fixture.service.calculate(10L, 1L);

        assertThat(context.getAllowedTables()).isEmpty();
        assertThat(context.getTableScopeMode()).isEqualTo("UNRESTRICTED");
    }

    private static DatasourceAccessPolicy policy(
            String subjectType,
            Long subjectId,
            String tableName,
            String columnName,
            String accessType,
            String maskStrategy,
            String rowFilterExpression
    ) {
        DatasourceAccessPolicy policy = new DatasourceAccessPolicy();
        policy.setDatasourceId(1L);
        policy.setSubjectType(subjectType);
        policy.setSubjectId(subjectId);
        policy.setTableName(tableName);
        policy.setColumnName(columnName);
        policy.setAccessType(accessType);
        policy.setMaskStrategy(maskStrategy);
        policy.setRowFilterExpression(rowFilterExpression);
        return policy;
    }

    private static DbTableMeta table(String tableName) {
        DbTableMeta table = new DbTableMeta();
        table.setTableName(tableName);
        return table;
    }

    private static class TestFixture {
        private final DatasourceAccessMapper accessMapper = mock(DatasourceAccessMapper.class);
        private final DatasourceAccessPolicyMapper policyMapper = mock(DatasourceAccessPolicyMapper.class);
        private final RoleMapper roleMapper = mock(RoleMapper.class);
        private final UserMapper userMapper = mock(UserMapper.class);
        private final DbTableMetaMapper tableMetaMapper = mock(DbTableMetaMapper.class);
        private final DbColumnMetaMapper columnMetaMapper = mock(DbColumnMetaMapper.class);
        private final SchemaSnapshotService schemaSnapshotService = mock(SchemaSnapshotService.class);
        private final PermissionCalculatorImpl service = new PermissionCalculatorImpl(
                accessMapper,
                policyMapper,
                roleMapper,
                userMapper,
                tableMetaMapper,
                columnMetaMapper,
                schemaSnapshotService
        );
    }
}
