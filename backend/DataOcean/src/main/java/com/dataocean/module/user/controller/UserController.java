package com.dataocean.module.user.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.result.Result;
import com.dataocean.module.permission.s1.annotation.IamS1Global;
import com.dataocean.module.system.aspect.AdminAuditLog;
import com.dataocean.module.user.entity.dto.StatusUpdateDTO;
import com.dataocean.module.user.entity.dto.UserCreateDTO;
import com.dataocean.module.user.entity.dto.UserUpdateDTO;
import com.dataocean.module.user.entity.query.UserQuery;
import com.dataocean.module.user.entity.vo.ResetPasswordVO;
import com.dataocean.module.user.entity.vo.UserVO;
import com.dataocean.module.user.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@AdminAuditLog
@Slf4j
public class UserController {

    /** 用户列表、详情与导入模板：只读。 */
    private static final String VIEW_FUNCTION = "organization:user:view";
    /** 创建、修改、启停、删除、重置密码与导入。 */
    private static final String MANAGE_FUNCTION = "organization:user:manage";
    /** 导出是独立功能：拥有查看权不等于可以批量导出。 */
    private static final String EXPORT_FUNCTION = "organization:user:export";
    /** 导出分页大小：逐页读完，不再用“第一页 100 条”冒充完整导出。 */
    private static final long EXPORT_PAGE_SIZE = 500L;

    private final UserService userService;

    @GetMapping
    @IamS1Global(VIEW_FUNCTION)
    public Result<Page<UserVO>> listUsers(@ModelAttribute UserQuery request) {
        log.debug("list users page={} pageSize={}", request.resolvedPage(), request.resolvedPageSize());
        return Result.success(userService.listUsers(request));
    }

    @GetMapping("/{id}")
    @IamS1Global(VIEW_FUNCTION)
    public Result<UserVO> getUser(@PathVariable Long id) {
        return Result.success(userService.getUserById(id));
    }

    @PostMapping
    @IamS1Global(MANAGE_FUNCTION)
    public Result<Map<String, Long>> createUser(@Valid @RequestBody UserCreateDTO request) {
        Long id = userService.createUser(request);
        return Result.success("创建成功", Map.of("id", id));
    }

    @PutMapping("/{id}")
    @IamS1Global(MANAGE_FUNCTION)
    public Result<Void> updateUser(@PathVariable Long id, @Valid @RequestBody UserUpdateDTO request) {
        userService.updateUser(id, request);
        return Result.success("更新成功", null);
    }

    @RequestMapping(value = "/{id}/status", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @IamS1Global(MANAGE_FUNCTION)
    public Result<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody StatusUpdateDTO request) {
        userService.updateStatus(id, request.getStatus());
        return Result.success("状态更新成功", null);
    }

    @DeleteMapping("/{id}")
    @IamS1Global(MANAGE_FUNCTION)
    public Result<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return Result.success("删除成功", null);
    }

    @PostMapping("/{id}/reset-password")
    @IamS1Global(MANAGE_FUNCTION)
    public Result<ResetPasswordVO> resetPassword(@PathVariable Long id) {
        String tempPassword = userService.resetPassword(id);
        return Result.success("密码重置成功", new ResetPasswordVO(tempPassword));
    }

    @GetMapping("/import-template")
    @IamS1Global(VIEW_FUNCTION)
    public void downloadImportTemplate(HttpServletResponse response) throws IOException {
        // 模板不再包含 roleIds：用户角色绑定属于 S1 体系，必须走
        // /api/iam-s1/users/{id}/roles，不能靠导入旧角色 ID 建立任何权限事实。
        writeCsv(response, "dataocean-user-import-template.csv", List.of(
                List.of("username", "password", "realName", "email", "phone", "departmentId"),
                List.of("demo_user", "DataOcean123", "演示用户", "demo@example.com", "13800000000", "1")
        ));
    }

    @PostMapping("/import")
    @IamS1Global(MANAGE_FUNCTION)
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
                if (cells.size() < 6) {
                    throw new BusinessException(
                            "列数不足：需要 username、password、realName、email、phone、departmentId");
                }
                UserCreateDTO dto = new UserCreateDTO();
                dto.setUsername(cells.get(0).trim());
                dto.setPassword(cells.get(1).trim());
                dto.setRealName(cells.get(2).trim());
                dto.setEmail(blankToNull(cells.get(3)));
                dto.setPhone(blankToNull(cells.get(4)));
                dto.setDepartmentId(parseLong(cells.get(5)));
                // 导入**不绑定任何角色**：角色绑定是权限事实，只能由 S1 角色服务建立，
                // 不能靠 CSV 里的旧 roleIds 推导。导入后由管理员在「角色与负责源」里显式绑定。
                dto.setRoleIds(List.of());
                userService.createUser(dto);
                success++;
            } catch (Exception e) {
                errors.add("第 " + (i + 1) + " 行：" + e.getMessage());
            }
        }
        return Result.success(Map.of("success", success, "failed", errors.size(), "errors", errors));
    }

    /**
     * 导出用户列表。
     *
     * <p>此前固定取第 1 页 100 条当作「导出」：用户数超过 100 时结果被静默截断，界面上没有任何提示。
     * 现在按同一套筛选条件**逐页读到底**并直接写入响应流——既不截断，也不把全量结果堆在内存里。</p>
     *
     * <p>字段范围与列表一致（同一个 {@link UserVO}），不含密码、密码哈希或任何认证秘密；
     * 导出使用独立的 {@code organization:user:export}，拥有查看权不等于可以批量导出。</p>
     */
    @GetMapping("/export")
    @IamS1Global(EXPORT_FUNCTION)
    public void exportUsers(@ModelAttribute UserQuery query, HttpServletResponse response) throws IOException {
        prepareCsvResponse(response, "dataocean-users.csv");
        PrintWriter writer = response.getWriter();
        writer.write('﻿');
        writer.write("id,username,realName,email,phone,departmentName,roleNames,status,createdAt");
        long exported = 0;
        long page = 1;
        while (true) {
            query.setPage(page);
            query.setPageSize(EXPORT_PAGE_SIZE);
            Page<UserVO> result = userService.listUsers(query);
            List<UserVO> records = result.getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }
            for (UserVO user : records) {
                writer.write("\r\n" + escapeCsv(String.valueOf(user.getId()))
                        + "," + escapeCsv(user.getUsername())
                        + "," + escapeCsv(user.getRealName())
                        + "," + escapeCsv(user.getEmail())
                        + "," + escapeCsv(user.getPhone())
                        + "," + escapeCsv(user.getDepartmentName())
                        + "," + escapeCsv(user.getRoleNames() == null ? "" : String.join("|", user.getRoleNames()))
                        + "," + escapeCsv(String.valueOf(user.getStatus()))
                        + "," + escapeCsv(user.getCreatedAt() == null ? "" : user.getCreatedAt().toString()));
                exported++;
            }
            if (page * EXPORT_PAGE_SIZE >= result.getTotal()) {
                break;
            }
            page++;
        }
        writer.flush();
        // 导出属于批量读取，留一条可追溯的审计记录：数量 + 使用的筛选条件，不含任何秘密值。
        log.info("导出用户列表 rows={} departmentId={} status={} username={} realName={}",
                exported, query.getDepartmentId(), query.getStatus(), query.getUsername(), query.getRealName());
    }

    private void prepareCsvResponse(HttpServletResponse response, String filename) {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv;charset=UTF-8");
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
    }

    private void writeCsv(HttpServletResponse response, String filename, List<List<String>> rows) throws IOException {
        prepareCsvResponse(response, filename);
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
