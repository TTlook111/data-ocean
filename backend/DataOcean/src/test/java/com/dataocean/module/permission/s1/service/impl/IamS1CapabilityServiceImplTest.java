package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.module.permission.s1.catalog.IamS1FunctionCatalog;
import com.dataocean.module.permission.s1.entity.IamS1ResponsibleDatasourceFact;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import com.dataocean.module.permission.s1.entity.vo.IamS1CapabilitySnapshotVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1RoleTemplateVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1SubjectOptionVO;
import com.dataocean.module.permission.s1.mapper.IamS1CapabilityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1PermissionRevisionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1SubjectQueryMapper;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B4：能力摘要按 Java 结论返回，模板只引用固定目录中的功能码。 */
class IamS1CapabilityServiceImplTest {

    @Test
    void systemAdminGetsAllCatalogFunctionsAndAllEnabledDatasources() {
        Fixture fixture = new Fixture();
        when(fixture.authorizationResolver.isSystemAdmin(1L)).thenReturn(true);
        Map<String, Object> datasource = new LinkedHashMap<>();
        datasource.put("id", 5L);
        datasource.put("name", "销售库");
        when(fixture.capabilityMapper.selectAllEnabledDatasources()).thenReturn(List.of(datasource));
        when(fixture.permissionRevisionMapper.selectCurrentRevision()).thenReturn(42L);

        IamS1CapabilitySnapshotVO snapshot = fixture.service.snapshot(1L);

        assertThat(snapshot.globalFunctions()).hasSize(IamS1FunctionCatalog.definitions().size());
        assertThat(snapshot.systemAdmin()).isTrue();
        assertThat(snapshot.queryUse()).isTrue();
        assertThat(snapshot.viewSql()).isTrue();
        assertThat(snapshot.export()).isTrue();
        assertThat(snapshot.permissionRevision()).isEqualTo(42L);
        assertThat(snapshot.datasourceCapabilities()).hasSize(1);
        assertThat(snapshot.datasourceCapabilities().get(0).datasourceName()).isEqualTo("销售库");
        assertThat(snapshot.datasourceCapabilities().get(0).functionCodes())
                .hasSize(IamS1FunctionCatalog.definitions().size());
    }

    @Test
    void ordinaryUserOnlyGetsGrantedFunctionsAndSameBindingDatasourceFunctions() {
        Fixture fixture = new Fixture();
        when(fixture.authorizationResolver.isSystemAdmin(2L)).thenReturn(false);
        when(fixture.capabilityMapper.selectGrantedFunctionCodes(2L)).thenReturn(List.of("query:use"));
        IamS1ResponsibleDatasourceFact fact = new IamS1ResponsibleDatasourceFact();
        fact.setUserRoleId(9L);
        fact.setDatasourceId(5L);
        fact.setDatasourceName("销售库");
        fact.setDatasourceStatus(1);
        when(fixture.capabilityMapper.selectResponsibleDatasources(2L)).thenReturn(List.of(fact));
        when(fixture.capabilityMapper.selectDatasourceFunctionCodes(2L, 5L)).thenReturn(List.of("query:use"));
        when(fixture.permissionRevisionMapper.selectCurrentRevision()).thenReturn(7L);

        IamS1CapabilitySnapshotVO snapshot = fixture.service.snapshot(2L);

        assertThat(snapshot.systemAdmin()).isFalse();
        assertThat(snapshot.globalFunctions()).containsExactly("query:use");
        assertThat(snapshot.queryUse()).isTrue();
        assertThat(snapshot.viewSql()).isFalse();
        assertThat(snapshot.export()).isFalse();
        assertThat(snapshot.datasourceCapabilities()).hasSize(1);
        assertThat(snapshot.datasourceCapabilities().get(0).functionNames()).containsExactly("使用问数");
    }

    @Test
    void roleTemplatesOnlyReferenceKnownCatalogCodes() {
        Fixture fixture = new Fixture();

        List<IamS1RoleTemplateVO> templates = fixture.service.roleTemplates();

        assertThat(templates).isNotEmpty();
        for (IamS1RoleTemplateVO template : templates) {
            assertThat(template.name()).isNotBlank();
            assertThat(template.capabilitySummary()).isNotBlank();
            assertThat(template.dataHint()).isNotBlank();
            for (String code : template.functionCodes()) {
                assertThat(IamS1FunctionCatalog.find(code)).as("模板功能码必须在固定目录中：" + code).isNotNull();
            }
        }
    }

    @Test
    void subjectOptionsSkipProtectedSystemAdminRole() {
        Fixture fixture = new Fixture();
        IamS1Role protectedRole = new IamS1Role();
        protectedRole.setId(1L);
        protectedRole.setRoleCode("IAM_S1_SYSTEM_ADMIN");
        protectedRole.setRoleName("系统管理员");
        protectedRole.setStatus(1);
        IamS1Role ordinaryRole = new IamS1Role();
        ordinaryRole.setId(2L);
        ordinaryRole.setRoleCode("DATA_REVIEWER");
        ordinaryRole.setRoleName("数据复核员");
        ordinaryRole.setStatus(1);
        when(fixture.roleMapper.selectList(any())).thenReturn(List.of(protectedRole, ordinaryRole));

        List<IamS1SubjectOptionVO> options = fixture.service.subjectOptions(1L, "ROLE", null);

        assertThat(options).hasSize(1);
        assertThat(options.get(0).name()).isEqualTo("数据复核员");
        assertThat(options.get(0).subjectTypeName()).isEqualTo("角色");
    }

    private static final class Fixture {
        private final IamS1AuthorizationResolver authorizationResolver = mock(IamS1AuthorizationResolver.class);
        private final IamS1CapabilityMapper capabilityMapper = mock(IamS1CapabilityMapper.class);
        private final IamS1RoleMapper roleMapper = mock(IamS1RoleMapper.class);
        private final IamS1SubjectQueryMapper subjectQueryMapper = mock(IamS1SubjectQueryMapper.class);
        private final IamS1PermissionRevisionMapper permissionRevisionMapper =
                mock(IamS1PermissionRevisionMapper.class);
        private final IamS1AdminGuard adminGuard = mock(IamS1AdminGuard.class);
        private final IamS1CapabilityServiceImpl service = new IamS1CapabilityServiceImpl(
                authorizationResolver, capabilityMapper, roleMapper, subjectQueryMapper,
                permissionRevisionMapper, adminGuard);
    }
}
