package com.dataocean.module.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.DatasourceAccess;
import com.dataocean.module.datasource.mapper.DatasourceAccessMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.user.entity.SysDepartment;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.mapper.DepartmentMapper;
import com.dataocean.module.user.mapper.RoleMapper;
import com.dataocean.module.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatasourceAccessServiceImplTest {

    private DatasourceMapper datasourceMapper;
    private DatasourceAccessMapper accessMapper;
    private UserMapper userMapper;
    private RoleMapper roleMapper;
    private DepartmentMapper departmentMapper;
    private DatasourceAccessServiceImpl service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        datasourceMapper = mock(DatasourceMapper.class);
        accessMapper = mock(DatasourceAccessMapper.class);
        userMapper = mock(UserMapper.class);
        roleMapper = mock(RoleMapper.class);
        departmentMapper = mock(DepartmentMapper.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        service = new DatasourceAccessServiceImpl(
                datasourceMapper, accessMapper, userMapper, roleMapper, departmentMapper, eventPublisher);
        when(datasourceMapper.selectOne(any(Wrapper.class))).thenReturn(enabledDatasource());
    }

    @Test
    void calculateDecisionUsesTargetUserWildcardPermission() {
        when(roleMapper.selectByUserId(10L)).thenReturn(List.of());
        when(userMapper.selectDepartmentIdByUserId(10L)).thenReturn(null);
        when(userMapper.selectPermissionCodesByUserId(10L)).thenReturn(List.of("*"));

        var decision = service.calculateDecision(10L, 1L);

        assertThat(decision.isCanQuery()).isTrue();
        assertThat(decision.isCanExport()).isTrue();
        assertThat(decision.isCanViewSql()).isTrue();
        assertThat(decision.getDecisionSource()).isEqualTo("*");
    }

    @Test
    void calculateDecisionInheritsParentDepartmentGrant() {
        when(roleMapper.selectByUserId(10L)).thenReturn(List.of());
        when(userMapper.selectDepartmentIdByUserId(10L)).thenReturn(30L);
        when(userMapper.selectPermissionCodesByUserId(10L)).thenReturn(List.of());
        when(departmentMapper.selectById(30L)).thenReturn(department(30L, 20L));
        when(departmentMapper.selectById(20L)).thenReturn(department(20L, null));
        when(accessMapper.selectValidGrantsForUser(eq(1L), eq(10L), anyList(), eq(List.of(30L, 20L))))
                .thenReturn(List.of(grant("DEPARTMENT", 20L, true, false, true, "ALLOW")));

        var decision = service.calculateDecision(10L, 1L);

        assertThat(decision.isCanQuery()).isTrue();
        assertThat(decision.getDecisionSource()).isEqualTo("DEPARTMENT");
        assertThat(decision.getEffectiveDepartmentId()).isEqualTo(20L);
    }

    @Test
    void calculateDecisionMergesRolesWithOrSemantics() {
        SysRole roleA = new SysRole();
        roleA.setId(101L);
        SysRole roleB = new SysRole();
        roleB.setId(102L);
        when(roleMapper.selectByUserId(10L)).thenReturn(List.of(roleA, roleB));
        when(userMapper.selectDepartmentIdByUserId(10L)).thenReturn(null);
        when(userMapper.selectPermissionCodesByUserId(10L)).thenReturn(List.of());
        when(accessMapper.selectValidGrantsForUser(eq(1L), eq(10L), eq(List.of(101L, 102L)), anyList()))
                .thenReturn(List.of(
                        grant("ROLE", 101L, true, false, false, "ALLOW"),
                        grant("ROLE", 102L, false, true, true, "ALLOW")
                ));

        var decision = service.calculateDecision(10L, 1L);

        assertThat(decision.isCanQuery()).isTrue();
        assertThat(decision.isCanExport()).isTrue();
        assertThat(decision.isCanViewSql()).isTrue();
        assertThat(decision.getDecisionSource()).isEqualTo("ROLE");
    }

    @Test
    void calculateDecisionLetsUserDenyOverrideDepartmentAndRoles() {
        SysRole role = new SysRole();
        role.setId(101L);
        when(roleMapper.selectByUserId(10L)).thenReturn(List.of(role));
        when(userMapper.selectDepartmentIdByUserId(10L)).thenReturn(30L);
        when(userMapper.selectPermissionCodesByUserId(10L)).thenReturn(List.of());
        when(departmentMapper.selectById(30L)).thenReturn(department(30L, null));
        when(accessMapper.selectValidGrantsForUser(eq(1L), eq(10L), eq(List.of(101L)), eq(List.of(30L))))
                .thenReturn(List.of(
                        grant("DEPARTMENT", 30L, true, true, true, "ALLOW"),
                        grant("ROLE", 101L, true, true, true, "ALLOW"),
                        grant("USER", 10L, true, true, true, "DENY")
                ));

        var decision = service.calculateDecision(10L, 1L);

        assertThat(decision.isCanQuery()).isFalse();
        assertThat(decision.isCanExport()).isFalse();
        assertThat(decision.isCanViewSql()).isFalse();
        assertThat(decision.getDecisionSource()).isEqualTo("USER");
        assertThat(decision.getAccessEffect()).isEqualTo("DENY");
    }

    private static Datasource enabledDatasource() {
        Datasource datasource = new Datasource();
        datasource.setId(1L);
        datasource.setStatus(Datasource.STATUS_ENABLED);
        datasource.setDeleted(0L);
        return datasource;
    }

    private static SysDepartment department(Long id, Long parentId) {
        SysDepartment department = new SysDepartment();
        department.setId(id);
        department.setParentId(parentId);
        department.setStatus(1);
        return department;
    }

    private static DatasourceAccess grant(String subjectType, Long subjectId,
                                          boolean canQuery, boolean canExport, boolean canViewSql,
                                          String accessEffect) {
        DatasourceAccess access = new DatasourceAccess();
        access.setDatasourceId(1L);
        access.setSubjectType(subjectType);
        access.setSubjectId(subjectId);
        access.setCanQuery(canQuery);
        access.setCanExport(canExport);
        access.setCanViewSql(canViewSql);
        access.setAccessEffect(accessEffect);
        return access;
    }
}
