package com.dataocean.common.security;

import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.exception.BizIllegalException;
import org.springframework.security.core.context.SecurityContextHolder;

public final class UserContext {
    private UserContext() {}
    public static IamPrincipal current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof IamPrincipal principal))
            throw new BizIllegalException(ErrorCode.AUTH_REQUIRED,"请先登录");
        return principal;
    }
    public static long getUser() { return current().userId(); }
}
