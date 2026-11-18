package com.dataocean.module.iam.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.module.iam.entity.po.GuardPO;
import com.dataocean.module.iam.entity.po.RolePO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.PermissionMapper;
import com.dataocean.module.iam.mapper.RoleAssignmentMapper;
import com.dataocean.module.iam.mapper.RoleMapper;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证最后恢复途径的完整实际图，避免仅计人数或拼接不同动作的上限。
 */
class RecoveryInvariantTest {

    /**
     * 系统当前目录及按动作计算的真实授权查询。
     */
    private final PermissionMapper mapper = mock(PermissionMapper.class);

    /**
     * 测试初始化例外已关闭后的配置边界。
     */
    private final GuardMapper guard = mock(GuardMapper.class);

    /**
     * 可恢复角色的完整使用和授予内容。
     */
    private final RoleMapper roles = mock(RoleMapper.class);

    /**
     * 直接验证同一业务规则，不替代真实事务并发验收。
     */
    private final PermissionServiceImpl service =
            new PermissionServiceImpl(mapper, guard, roles, mock(RoleAssignmentMapper.class));

    /**
     * 候选已经完成改密且具备页面与恢复操作，后续测试分别改变授予边界。
     */
    @BeforeEach
    void currentCandidate() {
        var state = new GuardPO();
        state.setBootstrapCompleted(true);
        when(guard.selectById(1)).thenReturn(state);
        when(mapper.recoveryCandidates()).thenReturn(List.of(1L));
        when(mapper.used(1L)).thenReturn(List.of("users.view", "users.recover"));
    }

    /**
     * 会使用恢复功能但无任何可恢复完整角色，不算实际恢复途径。
     */
    @Test
    void rejectsIncompleteGrantDespiteRecoveryUsage() {
        var role = role(10);
        when(roles.all()).thenReturn(List.of(role));
        when(roles.permissions(10)).thenReturn(List.of("users.view", "users.recover", "roles.protected"));
        when(roles.grantable(10)).thenReturn(List.of());
        when(mapper.grantable(1L, "users.recover")).thenReturn(List.of("users.view", "users.recover"));
        assertThrows(BizIllegalException.class, service::ensureRecovery);
    }

    /**
     * 恢复动作的上限不能由其他管理动作的授予资格补足。
     */
    @Test
    void rejectsGrantsOwnedOnlyByAnotherOperation() {
        when(roles.all()).thenReturn(List.of(role(10)));
        when(roles.permissions(10)).thenReturn(List.of("users.view", "users.recover"));
        when(roles.grantable(10)).thenReturn(List.of());
        when(mapper.grantable(1L, "users.recover")).thenReturn(List.of());
        when(mapper.grantable(1L, "roles.protected")).thenReturn(List.of("users.view", "users.recover"));
        assertThrows(BizIllegalException.class, service::ensureRecovery);
    }

    /**
     * 多个完整可授予角色可以共同形成页面和恢复操作的有效图。
     */
    @Test
    void acceptsCompleteMultiRoleRecoveryGraph() {
        when(roles.all()).thenReturn(List.of(role(10), role(11)));
        when(roles.permissions(10)).thenReturn(List.of("users.view"));
        when(roles.permissions(11)).thenReturn(List.of("users.recover"));
        when(roles.grantable(10)).thenReturn(List.of());
        when(roles.grantable(11)).thenReturn(List.of());
        when(mapper.grantable(1L, "users.recover")).thenReturn(List.of("users.view", "users.recover"));
        assertDoesNotThrow(service::ensureRecovery);
    }

    /**
     * 停用角色不提供实际恢复能力，即使配置内容仍完整保留。
     */
    @Test
    void disabledRoleDoesNotProtectRecoveryRoute() {
        var role = role(10);
        role.setLifecycle("DISABLED");
        when(roles.all()).thenReturn(List.of(role));
        when(mapper.grantable(1L, "users.recover")).thenReturn(List.of("users.view", "users.recover"));
        assertThrows(BizIllegalException.class, service::ensureRecovery);
    }

    /**
     * 构造最小正常角色，其具体能力由每个用例明确提供。
     */
    private RolePO role(long id) {
        var result = new RolePO();
        result.setId(id);
        result.setLifecycle("ENABLED");
        return result;
    }
}
