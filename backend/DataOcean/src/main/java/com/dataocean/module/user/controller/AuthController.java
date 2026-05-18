package com.dataocean.module.user.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.LoginUser;
import com.dataocean.module.user.entity.vo.CurrentUserResponse;
import com.dataocean.module.user.entity.req.LoginRequest;
import com.dataocean.module.user.entity.vo.LoginResponse;
import com.dataocean.module.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
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
    public Result<CurrentUserResponse> me() {
        LoginUser user = authService.currentUser();
        log.debug("当前登录用户解析完成 userId={} username={}", user.getUserId(), user.getUsername());
        return Result.success(CurrentUserResponse.builder()
                .id(user.getUserId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .roles(user.getRoles())
                .permissions(user.getPermissions())
                .build());
    }
}
