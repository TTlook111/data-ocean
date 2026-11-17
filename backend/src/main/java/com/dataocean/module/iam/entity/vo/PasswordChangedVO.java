package com.dataocean.module.iam.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 身份凭据的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class PasswordChangedVO {

    /**
     * 凭据变更后必须重新登录，不保留旧认证资格。
     */
    private boolean reloginRequired;
}
