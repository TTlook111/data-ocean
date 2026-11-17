package com.dataocean.common.security;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;

import org.springframework.security.core.context.SecurityContextHolder;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 从当前安全上下文读取可信身份，不接受客户端自报操作者。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UserContext {

    /**
     * 读取当前权威状态或可信登录主体，缺失时不回退默认身份。
     */
    public static IamPrincipal current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof IamPrincipal principal)) {
            throw new BizIllegalException(ErrorCode.AUTH_REQUIRED, "请先登录");
        }
        return principal;
    }

    /**
     * 从本次可信认证上下文取得当前用户标识。
     */
    public static long getUser() {
        return current().userId();
    }
}
