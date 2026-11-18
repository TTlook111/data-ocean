package com.dataocean.module.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.iam.entity.po.DepartmentPO;
import com.dataocean.module.iam.entity.po.MembershipPO;
import com.dataocean.module.iam.entity.po.RoleAssignmentPO;
import com.dataocean.module.iam.entity.po.RolePO;
import com.dataocean.module.iam.entity.po.UserPO;
import com.dataocean.module.iam.entity.vo.DepartmentMemberVO;
import com.dataocean.module.iam.entity.vo.RoleRefVO;
import com.dataocean.module.iam.mapper.DepartmentMapper;
import com.dataocean.module.iam.mapper.MembershipMapper;
import com.dataocean.module.iam.mapper.RoleAssignmentMapper;
import com.dataocean.module.iam.mapper.RoleMapper;
import com.dataocean.module.iam.mapper.UserMapper;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 验证人员分页组装只批量加载当前页关联，并保留原有列表语义。
 */
class IamViewAssemblerTest {

    /**
     * 多名人员共享部门与角色时，按人员分别组装主兼任、命中关系和角色保护标记。
     */
    @Test
    void departmentMembersKeepPageOrderAndLoadRelationsInBatches() {
        var users = mock(UserMapper.class);
        var roles = mock(RoleMapper.class);
        var assignments = mock(RoleAssignmentMapper.class);
        var departments = mock(DepartmentMapper.class);
        var memberships = mock(MembershipMapper.class);
        var permissions = mock(PermissionService.class);
        var views = new IamViewAssembler(
                users, roles, assignments, departments, memberships, permissions, mock(JsonCodec.class));

        // 分页顺序和人员编号顺序不同，两个成员都命中同一个部门。
        var page = new Page<UserPO>(2, 2, 5);
        page.setRecords(List.of(user(8L), user(7L)));
        when(memberships.forUsers(List.of(8L, 7L)))
                .thenReturn(List.of(
                        membership(1L, 8L, 11L, true), membership(2L, 7L, 12L, true), membership(3L, 7L, 11L, false)));
        when(departments.selectByIds(Set.of(11L, 12L))).thenReturn(List.of(department(12L), department(11L)));

        var assignment = new RoleAssignmentPO();
        assignment.setUserId(8L);
        assignment.setRoleId(13L);
        when(assignments.assignmentsForUsers(List.of(8L, 7L))).thenReturn(List.of(assignment));

        var role = new RolePO();
        role.setId(13L);
        role.setVersion(2);
        when(roles.selectByIds(Set.of(13L))).thenReturn(List.of(role));
        when(permissions.protectedRoles(Set.of(13L))).thenReturn(Set.of(13L));

        var result = views.departmentMembers(page, List.of(11L));
        assertThat(result.getTotal()).isEqualTo(5);
        assertThat(result.getPageNum()).isEqualTo(2);
        assertThat(result.getItems()).extracting(DepartmentMemberVO::getId).containsExactly("8", "7");
        var first = result.getItems().get(0);
        var second = result.getItems().get(1);
        assertThat(first.getRoles()).extracting(RoleRefVO::getId).containsExactly("13");
        assertThat(first.getMatchingMemberships().get(0).isPrimary()).isTrue();
        assertThat(second.getPrimaryDepartment().getId()).isEqualTo("12");
        assertThat(second.getRoles()).isEmpty();
        assertThat(second.getMatchingMemberships()).hasSize(1);
        assertThat(second.getMatchingMemberships().get(0).getDepartment().getId())
                .isEqualTo("11");

        // 明确核对批量方法只被调用一次，列表组装无逐行持久化调用。
        verify(memberships).forUsers(List.of(8L, 7L));
        verify(departments).selectByIds(Set.of(11L, 12L));
        verify(assignments).assignmentsForUsers(List.of(8L, 7L));
        verify(roles).selectByIds(Set.of(13L));
        verify(permissions).protectedRoles(Set.of(13L));
        verifyNoMoreInteractions(users, memberships, departments, assignments, roles, permissions);
    }

    /**
     * 创建仅携带列表展示所需字段的人员。
     */
    private UserPO user(long id) {
        var data = new UserPO();
        data.setId(id);
        data.setLifecycle("ENABLED");
        data.setVersion(1);
        return data;
    }

    /**
     * 创建可供关系引用复用的部门。
     */
    private DepartmentPO department(long id) {
        var data = new DepartmentPO();
        data.setId(id);
        return data;
    }

    /**
     * 创建尚未结束的主兼任关系。
     */
    private MembershipPO membership(long id, long user, long department, boolean primary) {
        var row = new MembershipPO();
        row.setId(id);
        row.setUserId(user);
        row.setDepartmentId(department);
        row.setPrimary(primary);
        return row;
    }
}
