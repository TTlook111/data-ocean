package com.dataocean.module.user.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.LoginUser;
import com.dataocean.module.user.dto.CurrentUserResponse;
import com.dataocean.module.user.dto.LoginRequest;
import com.dataocean.module.user.dto.LoginResponse;
import com.dataocean.module.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        authService.logout(request.getHeader("Authorization"));
        return Result.success("退出成功", null);
    }

    @GetMapping("/me")
    public Result<CurrentUserResponse> me() {
        LoginUser user = authService.currentUser();
        return Result.success(CurrentUserResponse.builder()
                .id(user.getUserId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .roles(user.getRoles())
                .permissions(user.getPermissions())
                .build());
    }
}
