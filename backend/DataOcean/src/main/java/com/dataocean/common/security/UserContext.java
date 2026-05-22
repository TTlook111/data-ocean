package com.dataocean.common.security;

import com.dataocean.common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * 用户上下文工具类
 * <p>
 * 提供静态方法从 Spring Security 上下文中获取当前登录用户信息，
 * 供 Service 层和其他组件便捷获取当前操作用户的 ID、用户名、角色等。
 * </p>
 */
public final class UserContext {

    /** 私有构造，禁止实例化 */
    private UserContext() {
    }

    /**
     * 获取当前登录用户对象
     *
     * @return 当前登录用户
     * @throws BusinessException 如果用户未登录则抛出 401 异常
     */
    public static LoginUser currentUser() {
        // 从 SecurityContext 中获取认证信息
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // 校验认证状态和 Principal 类型
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            throw new BusinessException(401, "未登录");
        }
        return loginUser;
    }

    /**
     * 获取当前登录用户 ID
     *
     * @return 用户 ID
     */
    public static Long currentUserId() {
        return currentUser().getUserId();
    }

    /**
     * 获取当前登录用户名
     *
     * @return 用户名
     */
    public static String currentUsername() {
        return currentUser().getUsername();
    }

    /**
     * 获取当前登录用户真实姓名
     *
     * @return 真实姓名
     */
    public static String currentRealName() {
        return currentUser().getRealName();
    }

    /**
     * 获取当前登录用户的角色列表
     *
     * @return 角色编码列表
     */
    public static List<String> currentRoles() {
        return currentUser().getRoles();
    }

    /**
     * 获取当前登录用户的权限列表
     *
     * @return 权限标识列表
     */
    public static List<String> currentPermissions() {
        return currentUser().getPermissions();
    }

    /**
     * 清除当前安全上下文（用于退出登录等场景）
     */
    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
