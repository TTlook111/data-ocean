package com.dataocean.common.security;

import java.io.Serializable;
import java.security.Principal;

/**
 * 保存在服务端会话中的稳定用户身份与认证版本快照。
 */
public record IamPrincipal(long userId, long authenticationVersion, String username, String displayName)
        implements Principal, Serializable {

    /**
     * 以稳定用户标识作为安全主体名称和会话索引，不使用显示姓名。
     */
    @Override
    public String getName() {
        return Long.toString(userId);
    }
}
