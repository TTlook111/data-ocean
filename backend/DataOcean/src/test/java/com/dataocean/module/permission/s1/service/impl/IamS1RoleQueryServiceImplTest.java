package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.module.permission.s1.entity.IamS1ResponsibleDatasourceFact;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import com.dataocean.module.permission.s1.entity.IamS1UserRole;
import com.dataocean.module.permission.s1.entity.vo.IamS1RoleVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1UserRoleBindingVO;
import com.dataocean.module.permission.s1.mapper.IamS1CapabilityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1SubjectQueryMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserRoleMapper;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B4：角色与用户角色绑定视图区分“能管理哪些数据源”和“能查询哪些数据”。 */
class IamS1RoleQueryServiceImplTest {

    @Test
    void roleViewShowsChineseCapabilitySummaryAndMemberCount() {
        Fixture fixture = new Fixture();
        IamS1Role role = role(20L, "DATA_REVIEWER", "数据复核员", 0);
        when(fixture.roleMapper.selectList(any())).thenReturn(List.of(role));
        when(fixture.roleMapper.selectFunctionCodesByRoleId(20L))
                .thenReturn(List.of("query:use", "query:sql:view"));
        when(fixture.userRoleMapper.selectCount(any())).thenReturn(3L);

        List<IamS1RoleVO> roles = fixture.service.listRoles(1L, null);

        assertThat(roles).hasSize(1);
        IamS1RoleVO vo = roles.get(0);
        assertThat(vo.roleName()).isEqualTo("数据复核员");
        assertThat(vo.capabilitySummary()).isEqualTo("可以使用：使用问数、查看 SQL");
        assertThat(vo.functionNames()).containsExactly("使用问数", "查看 SQL");
        assertThat(vo.memberCount()).isEqualTo(3L);
        assertThat(vo.protectedRole()).isFalse();
    }

    @Test
    void bindingViewSeparatesResponsibleDatasourcesFromBusinessData() {
        Fixture fixture = new Fixture();
        IamS1Role role = role(20L, "DATA_REVIEWER", "数据复核员", 0);
        IamS1UserRole binding = new IamS1UserRole();
        binding.setId(70L);
        binding.setUserId(2L);
        binding.setRoleId(20L);
        binding.setStatus(1);
        when(fixture.userRoleMapper.selectList(any())).thenReturn(List.of(binding));
        when(fixture.roleMapper.selectById(20L)).thenReturn(role);
        when(fixture.roleMapper.selectFunctionCodesByRoleId(20L)).thenReturn(List.of("datasource:view"));
        IamS1ResponsibleDatasourceFact fact = new IamS1ResponsibleDatasourceFact();
        fact.setUserRoleId(70L);
        fact.setDatasourceId(5L);
        fact.setDatasourceName("销售库");
        fact.setDatasourceStatus(1);
        when(fixture.capabilityMapper.selectResponsibleDatasourcesByBinding(70L)).thenReturn(List.of(fact));

        List<IamS1UserRoleBindingVO> bindings = fixture.service.listUserRoles(1L, 2L);

        assertThat(bindings).hasSize(1);
        IamS1UserRoleBindingVO vo = bindings.get(0);
        assertThat(vo.responsibleDatasources()).hasSize(1);
        assertThat(vo.responsibleDatasources().get(0).name()).isEqualTo("销售库");
        assertThat(vo.responsibleDatasourceSummary()).isEqualTo("负责数据源：销售库");
        assertThat(vo.functionNames()).containsExactly("查看数据源");
    }

    @Test
    void bindingWithoutResponsibleDatasourceExplainsWhyWorkspaceIsUnreachable() {
        Fixture fixture = new Fixture();
        IamS1Role role = role(20L, "QUERY_ANALYST", "分析人员", 0);
        IamS1UserRole binding = new IamS1UserRole();
        binding.setId(70L);
        binding.setUserId(2L);
        binding.setRoleId(20L);
        binding.setStatus(1);
        when(fixture.userRoleMapper.selectList(any())).thenReturn(List.of(binding));
        when(fixture.roleMapper.selectById(20L)).thenReturn(role);
        when(fixture.roleMapper.selectFunctionCodesByRoleId(20L)).thenReturn(List.of("query:use"));
        when(fixture.capabilityMapper.selectResponsibleDatasourcesByBinding(70L)).thenReturn(List.of());

        List<IamS1UserRoleBindingVO> bindings = fixture.service.listUserRoles(1L, 2L);

        assertThat(bindings.get(0).responsibleDatasourceSummary())
                .isEqualTo("未选择负责数据源，无法进入数据源相关后台工作区");
    }

    private IamS1Role role(Long id, String code, String name, int protectedRole) {
        IamS1Role role = new IamS1Role();
        role.setId(id);
        role.setRoleCode(code);
        role.setRoleName(name);
        role.setStatus(1);
        role.setProtectedRole(protectedRole);
        role.setBuiltIn(protectedRole);
        return role;
    }

    private static final class Fixture {
        private final IamS1RoleMapper roleMapper = mock(IamS1RoleMapper.class);
        private final IamS1UserRoleMapper userRoleMapper = mock(IamS1UserRoleMapper.class);
        private final IamS1CapabilityMapper capabilityMapper = mock(IamS1CapabilityMapper.class);
        private final IamS1SubjectQueryMapper subjectQueryMapper = mock(IamS1SubjectQueryMapper.class);
        private final IamS1AdminGuard adminGuard = mock(IamS1AdminGuard.class);
        private final IamS1RoleQueryServiceImpl service = new IamS1RoleQueryServiceImpl(
                roleMapper, userRoleMapper, capabilityMapper, subjectQueryMapper, adminGuard);
    }
}
