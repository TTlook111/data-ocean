package com.dataocean.module.user.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 重置密码返回视图对象。
 * <p>
 * 管理员为用户重置密码后返回此对象，
 * 包含系统生成的临时密码，用户首次登录后需强制修改。
 * </p>
 *
 * @author dataocean
 */
@Data
@AllArgsConstructor
public class ResetPasswordVO {

    /** 系统生成的临时密码 */
    private String tempPassword;
}
