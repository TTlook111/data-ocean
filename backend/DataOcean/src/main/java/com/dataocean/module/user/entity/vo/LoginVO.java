package com.dataocean.module.user.entity.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 登录成功返回视图对象。
 * <p>
 * 登录接口 /api/auth/login 成功后返回此对象，
 * 包含 JWT Token 和用户基本信息，
 * 前端据此完成本地存储和路由守卫初始化。
 * </p>
 *
 * @author dataocean
 */
@Data
@Builder
public class LoginVO {

    /** JWT 访问令牌 */
    private String token;

    /** 令牌类型，固定为 "Bearer" */
    private String tokenType;

    /** 令牌过期时间（秒） */
    private Long expiresIn;

    /** 用户ID */
    private Long userId;

    /** 登录用户名 */
    private String username;

    /** 用户真实姓名 */
    private String realName;

    /** 是否已修改初始密码 */
    private Boolean passwordChanged;

}
