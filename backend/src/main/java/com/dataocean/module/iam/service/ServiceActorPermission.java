package com.dataocean.module.iam.service;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.IamPrincipal;
import com.dataocean.module.iam.entity.po.UserPO;
import com.dataocean.module.iam.mapper.UserMapper;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 后台任务身份始终按实际发起人和当前资格判断。
 */
@Service
@RequiredArgsConstructor
public class ServiceActorPermission {

    /**
     * 查询当前用户状态和认证版本。
     */
    private final UserMapper users;

    /**
     * 从权威角色读取当前实际功能资格。
     */
    private final PermissionService permissions;

    /**
     * 复核后台请求保存的认证版本及当前有效操作权限。
     */
    public Set<String> require(long actor, long authenticationVersion, String... required) {
        validated(actor, authenticationVersion);
        return rights(actor, required);
    }

    /**
     * 复用依赖读取中的既有核权逻辑。身份来自已保存任务和权威人员表，并在结束时恢复线程。
     */
    public <T> T asActor(long actor, long authenticationVersion, Supplier<T> work, String... required) {
        var user = validated(actor, authenticationVersion);
        rights(actor, required);
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new IamPrincipal(actor, authenticationVersion, user.getUsername(), user.getDisplayName()),
                null,
                List.of()));
        SecurityContextHolder.setContext(context);
        try {
            // 只在本次同步依赖读取内安装刚核验过的发起人。
            return work.get();
        } finally {
            // Worker 的机器身份及调度线程不继承业务用户身份。
            SecurityContextHolder.setContext(previous);
        }
    }

    /**
     * 在实际回查时读取当前人员与认证版本。
     */
    private UserPO validated(long actor, long authenticationVersion) {
        var user = users.selectById(actor);
        if (user == null
                || !"ENABLED".equals(user.getLifecycle())
                || user.isMustChangePassword()
                || user.getAuthenticationVersion() != authenticationVersion) {
            // 已停用或失效的原主体不能继续对外调用。
            throw BizIllegalException.denied();
        }
        return user;
    }

    /**
     * 当前角色仍须包含全部任务所需权限。
     */
    private Set<String> rights(long actor, String... required) {
        var actual = permissions.effective(actor);
        if (!actual.containsAll(Set.of(required))) {
            // 任务分派、机器身份不扩大原用户的业务能力。
            throw BizIllegalException.denied();
        }
        return actual;
    }
}
