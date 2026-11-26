package com.dataocean.module.iam.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.iam.entity.dto.ChangePasswordDTO;
import com.dataocean.module.iam.entity.dto.UpdateContactDTO;
import com.dataocean.module.iam.entity.vo.AccountVO;
import com.dataocean.module.iam.entity.vo.PasswordChangedVO;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.iam.service.AuthService;
import com.dataocean.module.iam.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 本人资料与密码入口，仅使用可信登录身份定位目标人员。
 */
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
public class AccountController {

    /**
     * 本人资料与联系方式的业务能力。
     */
    private final UserService users;

    /**
     * 本人密码的独立校验与提交。
     */
    private final AuthService auth;

    /**
     * 改密后清除当前会话的旧 CSRF。
     */
    private final HttpSessionCsrfTokenRepository csrf;

    /**
     * 返回当前本人资料，不依赖人员管理权限。
     */
    @GetMapping
    @RequirePermission(access = RequirePermission.Access.AUTHENTICATED)
    public Result<AccountVO> account() {
        return Result.success(users.account());
    }

    /**
     * 用完整版本和防重命令保存本人联系方式。
     */
    @PutMapping("/contact")
    @RequirePermission(access = RequirePermission.Access.AUTHENTICATED)
    public Result<AccountVO> contact(
            @Valid @RequestBody UpdateContactDTO data, @RequestHeader("Idempotency-Key") String command) {
        return Result.success(users.contact(data, command));
    }

    /**
     * 验证原密码后更新凭据，并立即结束当前浏览器会话。
     */
    @PostMapping("/password")
    @RequirePermission(access = RequirePermission.Access.AUTHENTICATED)
    public Result<PasswordChangedVO> password(
            @Valid @RequestBody ChangePasswordDTO data, HttpServletRequest request, HttpServletResponse response) {
        auth.changePassword(data);
        new SecurityContextLogoutHandler().logout(request, response, null);
        csrf.saveToken(null, request, response);
        return Result.success(new PasswordChangedVO(true));
    }
}
