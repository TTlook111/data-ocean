package com.dataocean.module.user.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.user.entity.dto.ChangePasswordDTO;
import com.dataocean.module.user.entity.vo.CurrentUserVO;
import com.dataocean.module.user.entity.dto.LoginDTO;
import com.dataocean.module.user.entity.dto.ProfileUpdateDTO;
import com.dataocean.module.user.entity.vo.LoginVO;
import com.dataocean.module.user.service.AuthService;
import com.dataocean.module.user.service.CaptchaService;
import com.dataocean.module.user.entity.vo.CaptchaVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证与个人信息控制器。
 * <p>
 * 提供验证码、登录、登出、当前用户信息、密码修改和个人资料更新接口。
 * </p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;

    /**
     * 生成登录验证码。
     *
     * @return 验证码图片和验证码 key
     */
    @GetMapping("/captcha")
    public Result<CaptchaVO> captcha() {
        return Result.success(captchaService.generate());
    }

    /**
     * 用户登录。
     *
     * @param request 登录请求参数
     * @return 登录令牌和当前用户信息
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO request) {
        log.debug("收到登录请求 username={}", request.getUsername());
        return Result.success(authService.login(request));
    }

    /**
     * 用户登出。
     *
     * @param request HTTP 请求，用于读取 Authorization 请求头
     * @return 操作结果
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        log.debug("收到登出请求");
        authService.logout(request.getHeader("Authorization"));
        return Result.success("退出成功", null);
    }

    /**
     * 获取当前登录用户信息。
     *
     * @return 当前用户信息
     */
    @GetMapping("/me")
    public Result<CurrentUserVO> me() {
        CurrentUserVO user = authService.currentUserInfo();
        log.debug("当前登录用户解析完成 userId={} username={}", user.getId(), user.getUsername());
        return Result.success(user);
    }

    /**
     * 修改当前用户密码。
     *
     * @param request 修改密码请求参数
     * @return 操作结果
     */
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO request) {
        log.debug("收到修改密码请求");
        authService.changePassword(request);
        return Result.success("密码修改成功，请重新登录", null);
    }

    /**
     * 更新当前用户个人资料。
     *
     * @param request 个人资料更新请求参数
     * @return 操作结果
     */
    @PutMapping("/profile")
    public Result<Void> updateProfile(@Valid @RequestBody ProfileUpdateDTO request) {
        log.debug("收到个人资料修改请求");
        authService.updateProfile(request);
        return Result.success("个人资料修改成功", null);
    }
}
