package com.dataocean.common.security;

import com.dataocean.common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

public final class UserContext {

    private UserContext() {
    }

    public static LoginUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            throw new BusinessException(401, "未登录");
        }
        return loginUser;
    }

    public static Long currentUserId() {
        return currentUser().getUserId();
    }

    public static String currentUsername() {
        return currentUser().getUsername();
    }

    public static String currentRealName() {
        return currentUser().getRealName();
    }

    public static List<String> currentRoles() {
        return currentUser().getRoles();
    }

    public static List<String> currentPermissions() {
        return currentUser().getPermissions();
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
