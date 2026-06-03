package com.dataocean.module.user.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.result.Result;
import com.dataocean.module.system.aspect.AdminAuditLog;
import com.dataocean.module.user.entity.dto.StatusUpdateDTO;
import com.dataocean.module.user.entity.dto.UserCreateDTO;
import com.dataocean.module.user.entity.dto.UserUpdateDTO;
import com.dataocean.module.user.entity.query.UserQuery;
import com.dataocean.module.user.entity.vo.ResetPasswordVO;
import com.dataocean.module.user.entity.vo.UserVO;
import com.dataocean.module.user.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('user:manage')")
@AdminAuditLog
@Slf4j
public class UserController {

    private final UserService userService;

    @GetMapping
    public Result<Page<UserVO>> listUsers(@ModelAttribute UserQuery request) {
        log.debug("list users page={} pageSize={}", request.resolvedPage(), request.resolvedPageSize());
        return Result.success(userService.listUsers(request));
    }

    @GetMapping("/{id}")
    public Result<UserVO> getUser(@PathVariable Long id) {
        return Result.success(userService.getUserById(id));
    }

    @PostMapping
    public Result<Map<String, Long>> createUser(@Valid @RequestBody UserCreateDTO request) {
        Long id = userService.createUser(request);
        return Result.success("创建成功", Map.of("id", id));
    }

    @PutMapping("/{id}")
    public Result<Void> updateUser(@PathVariable Long id, @Valid @RequestBody UserUpdateDTO request) {
        userService.updateUser(id, request);
        return Result.success("更新成功", null);
    }

    @RequestMapping(value = "/{id}/status", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public Result<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody StatusUpdateDTO request) {
        userService.updateStatus(id, request.getStatus());
        return Result.success("状态更新成功", null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return Result.success("删除成功", null);
    }

    @PostMapping("/{id}/reset-password")
    public Result<ResetPasswordVO> resetPassword(@PathVariable Long id) {
        String tempPassword = userService.resetPassword(id);
        return Result.success("密码重置成功", new ResetPasswordVO(tempPassword));
    }

    @GetMapping("/import-template")
    public void downloadImportTemplate(HttpServletResponse response) throws IOException {
        writeCsv(response, "dataocean-user-import-template.csv", List.of(
                List.of("username", "password", "realName", "email", "phone", "departmentId", "roleIds"),
                List.of("demo_user", "DataOcean123", "演示用户", "demo@example.com", "13800000000", "1", "1")
        ));
    }

    @PostMapping("/import")
    public Result<Map<String, Object>> importUsers(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new BusinessException("导入文件不能为空");
        }
        String content = new String(file.getBytes(), StandardCharsets.UTF_8).replace("\uFEFF", "");
        String[] lines = content.split("\\R");
        if (lines.length <= 1) {
            throw new BusinessException("导入文件缺少数据行");
        }
        int success = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            try {
                List<String> cells = parseCsvLine(lines[i]);
                if (cells.size() < 7) {
                    throw new BusinessException("列数不足");
                }
                UserCreateDTO dto = new UserCreateDTO();
                dto.setUsername(cells.get(0).trim());
                dto.setPassword(cells.get(1).trim());
                dto.setRealName(cells.get(2).trim());
                dto.setEmail(blankToNull(cells.get(3)));
                dto.setPhone(blankToNull(cells.get(4)));
                dto.setDepartmentId(parseLong(cells.get(5)));
                dto.setRoleIds(Arrays.stream(cells.get(6).split("[;|]"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Long::parseLong)
                        .toList());
                userService.createUser(dto);
                success++;
            } catch (Exception e) {
                errors.add("第 " + (i + 1) + " 行：" + e.getMessage());
            }
        }
        return Result.success(Map.of("success", success, "failed", errors.size(), "errors", errors));
    }

    @GetMapping("/export")
    public void exportUsers(@ModelAttribute UserQuery query, HttpServletResponse response) throws IOException {
        query.setPage(1L);
        query.setPageSize(100L);
        Page<UserVO> page = userService.listUsers(query);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("id", "username", "realName", "email", "phone", "departmentName", "roleNames", "status", "createdAt"));
        for (UserVO user : page.getRecords()) {
            rows.add(List.of(
                    String.valueOf(user.getId()),
                    nullToBlank(user.getUsername()),
                    nullToBlank(user.getRealName()),
                    nullToBlank(user.getEmail()),
                    nullToBlank(user.getPhone()),
                    nullToBlank(user.getDepartmentName()),
                    user.getRoleNames() == null ? "" : String.join("|", user.getRoleNames()),
                    String.valueOf(user.getStatus()),
                    user.getCreatedAt() == null ? "" : user.getCreatedAt().toString()
            ));
        }
        writeCsv(response, "dataocean-users.csv", rows);
    }

    private void writeCsv(HttpServletResponse response, String filename, List<List<String>> rows) throws IOException {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv;charset=UTF-8");
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        String csv = rows.stream()
                .map(row -> row.stream().map(this::escapeCsv).collect(Collectors.joining(",")))
                .collect(Collectors.joining("\r\n"));
        response.getWriter().write('\uFEFF');
        response.getWriter().write(csv);
    }

    private String escapeCsv(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString());
        return cells;
    }

    private Long parseLong(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? null : Long.parseLong(trimmed);
    }

    private String blankToNull(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
