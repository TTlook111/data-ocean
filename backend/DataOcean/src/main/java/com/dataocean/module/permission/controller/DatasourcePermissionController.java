package com.dataocean.module.permission.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.datasource.entity.vo.DatasourcePermissionDecisionVO;
import com.dataocean.module.datasource.service.DatasourceAccessService;
import com.dataocean.module.permission.entity.dto.DatasourcePermissionGrantDTO;
import com.dataocean.module.permission.entity.vo.DatasourcePermissionVO;
import com.dataocean.module.permission.service.DatasourcePermissionService;
import com.dataocean.module.system.aspect.AdminAuditLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据源访问权限管理控制器
 * <p>
 * 提供数据源级别的访问授权管理接口，支持按用户/角色/部门维度配置。
 * </p>
 *
 * @author dataocean
 */
@RestController
@RequestMapping("/api/admin/datasource-access")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('security:manage', '*')")
@AdminAuditLog
public class DatasourcePermissionController {

    private final DatasourcePermissionService permissionService;
    private final DatasourceAccessService datasourceAccessService;

    /**
     * 创建数据源访问授权
     */
    @PostMapping
    public Result<Map<String, Long>> grant(@Valid @RequestBody DatasourcePermissionGrantDTO dto) {
        Long id = permissionService.grant(dto);
        return Result.success("授权成功", Map.of("id", id));
    }

    /**
     * 更新数据源访问权限
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @RequestBody Map<String, Object> body) {
        permissionService.update(id,
                asBoolean(body.get("canQuery")),
                asBoolean(body.get("canExport")),
                asBoolean(body.get("canViewSql")),
                body.get("accessEffect") == null ? null : String.valueOf(body.get("accessEffect")));
        return Result.success("更新成功", null);
    }

    /**
     * 撤销数据源访问授权
     */
    @DeleteMapping("/{id}")
    public Result<Void> revoke(@PathVariable Long id) {
        permissionService.revoke(id);
        return Result.success("已取消授权", null);
    }

    /**
     * 查询数据源授权列表
     */
    @GetMapping
    public Result<List<DatasourcePermissionVO>> list(
            @RequestParam Long datasourceId,
            @RequestParam(required = false) String subjectType) {
        return Result.success(permissionService.listByDatasource(datasourceId, subjectType));
    }

    /**
     * 预览指定用户对指定数据源的最终权限决策。
     */
    @GetMapping("/decision")
    public Result<DatasourcePermissionDecisionVO> decision(@RequestParam Long datasourceId,
                                                          @RequestParam Long userId) {
        return Result.success(datasourceAccessService.calculateDecision(userId, datasourceId));
    }

    private Boolean asBoolean(Object value) {
        return value instanceof Boolean bool ? bool : null;
    }
}
