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

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;

    @GetMapping("/captcha")
    public Result<CaptchaVO> captcha() {
        return Result.success(captchaService.generate());
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO request) {
        log.debug("收到登录请求 username={}", request.getUsername());
        return Result.success(authService.login(request));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        log.debug("收到登出请求");
        authService.logout(request.getHeader("Authorization"));
        return Result.success("退出成功", null);
    }

    @GetMapping("/me")
    public Result<CurrentUserVO> me() {
        CurrentUserVO user = authService.currentUserInfo();
        log.debug("当前登录用户解析完成 userId={} username={}", user.getId(), user.getUsername());
        return Result.success(user);
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO request) {
        log.debug("收到修改密码请求");
        authService.changePassword(request);
        return Result.success("密码修改成功，请重新登录", null);
    }

    @PutMapping("/profile")
    public Result<Void> updateProfile(@Valid @RequestBody ProfileUpdateDTO request) {
        log.debug("收到个人资料修改请求");
        authService.updateProfile(request);
        return Result.success("个人资料修改成功", null);
    }
}
