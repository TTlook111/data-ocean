package com.dataocean.module.user.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.user.entity.vo.ResetPasswordVO;
import com.dataocean.module.user.entity.dto.StatusUpdateDTO;
import com.dataocean.module.user.entity.dto.UserCreateDTO;
import com.dataocean.module.user.entity.query.UserQuery;
import com.dataocean.module.user.entity.dto.UserUpdateDTO;
import com.dataocean.module.user.entity.vo.UserVO;
import com.dataocean.module.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('user:manage')")
@Slf4j
public class UserController {

    private final UserService userService;

    @GetMapping
    public Result<Page<UserVO>> listUsers(@ModelAttribute UserQuery request) {
        log.debug("收到用户列表查询请求 page={} pageSize={} username={} status={}",
                request.resolvedPage(), request.resolvedPageSize(), request.getUsername(), request.getStatus());
        return Result.success(userService.listUsers(request));
    }

    @GetMapping("/{id}")
    public Result<UserVO> getUser(@PathVariable Long id) {
        return Result.success(userService.getUserById(id));
    }

    @PostMapping
    public Result<Map<String, Long>> createUser(@Valid @RequestBody UserCreateDTO request) {
        log.debug("收到创建用户请求 username={} roleIds={}", request.getUsername(), request.getRoleIds());
        Long id = userService.createUser(request);
        return Result.success("创建成功", Map.of("id", id));
    }

    @PutMapping("/{id}")
    public Result<Void> updateUser(@PathVariable Long id, @Valid @RequestBody UserUpdateDTO request) {
        log.debug("收到更新用户请求 userId={} roleIds={}", id, request.getRoleIds());
        userService.updateUser(id, request);
        return Result.success("更新成功", null);
    }

    @RequestMapping(value = "/{id}/status", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public Result<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody StatusUpdateDTO request) {
        log.debug("收到更新用户状态请求 userId={} status={}", id, request.getStatus());
        userService.updateStatus(id, request.getStatus());
        return Result.success("状态更新成功", null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteUser(@PathVariable Long id) {
        log.debug("收到删除用户请求 userId={}", id);
        userService.deleteUser(id);
        return Result.success("删除成功", null);
    }

    @PostMapping("/{id}/reset-password")
    public Result<ResetPasswordVO> resetPassword(@PathVariable Long id) {
        log.debug("收到重置用户密码请求 userId={}", id);
        String tempPassword = userService.resetPassword(id);
        return Result.success("密码重置成功", new ResetPasswordVO(tempPassword));
    }
}
