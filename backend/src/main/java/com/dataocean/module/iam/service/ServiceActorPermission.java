package com.dataocean.module.iam.service;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.module.iam.mapper.UserMapper;

import java.util.Set;

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
        var user = users.selectById(actor);
        if (user == null || !"ENABLED".equals(user.getLifecycle()) || user.isMustChangePassword()
                || user.getAuthenticationVersion() != authenticationVersion) {
            // 已停用或失效的原主体不能继续对外调用。
            throw BizIllegalException.denied();
        }
        var actual = permissions.effective(actor);
        if (!actual.containsAll(Set.of(required))) {
            // 任务分派、机器身份不扩大原用户的业务能力。
            throw BizIllegalException.denied();
        }
        return actual;
    }
}
