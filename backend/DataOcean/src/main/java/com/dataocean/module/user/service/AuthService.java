package com.dataocean.module.user.service;
import com.dataocean.module.user.entity.dto.ChangePasswordDTO;
import com.dataocean.module.user.entity.dto.LoginDTO;
import com.dataocean.module.user.entity.dto.ProfileUpdateDTO;
import com.dataocean.module.user.entity.vo.CurrentUserVO;
import com.dataocean.module.user.entity.vo.LoginVO;

/**
 * 认证与个人账户业务接口。
 * <p>
 * 提供用户登录认证、退出登录、获取当前用户信息、修改密码和更新个人资料等功能。
 * 登录流程包含失败次数限制和账户锁定机制。
 * </p>
 *
 * @author DataOcean
 */
public interface AuthService {

    /**
     * 用户登录认证。
     * <p>
     * 校验用户名密码，检查账户状态和登录失败次数限制，
     * 认证通过后签发 JWT 令牌并更新最后登录时间。
     * </p>
     *
     * @param request 登录请求参数（用户名、密码）
     * @return 登录结果（含 JWT 令牌和是否需要强制改密标识）
     */
    LoginVO login(LoginDTO request);

    /**
     * 用户退出登录。
     * <p>
     * 将当前 JWT 加入 Redis 黑名单使其立即失效。
     * </p>
     *
     * @param authorizationHeader HTTP Authorization 请求头（Bearer token）
     */
    void logout(String authorizationHeader);

    /**
     * 获取当前登录用户信息。
     * <p>
     * 从 SecurityContext 中获取当前认证用户，返回用户基本信息、角色和权限列表。
     * </p>
     *
     * @return 当前用户视图对象
     */
    CurrentUserVO currentUserInfo();

    /**
     * 修改当前用户密码。
     * <p>
     * 校验旧密码正确性后更新为新密码，同时标记密码已修改并使旧 JWT 失效。
     * </p>
     *
     * @param request 修改密码请求参数（旧密码、新密码）
     */
    void changePassword(ChangePasswordDTO request);

    /**
     * 更新当前用户个人资料。
     * <p>
     * 允许用户修改自己的姓名、邮箱、手机号等非敏感信息。
     * </p>
     *
     * @param request 个人资料更新请求参数
     */
    void updateProfile(ProfileUpdateDTO request);
}
