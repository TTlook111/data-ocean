package com.dataocean.module.datasource.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.datasource.entity.query.DatasourceQuery;
import com.dataocean.module.datasource.entity.dto.DatasourceAccessGrantDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceCreateDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceStatusUpdateDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceTestDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceUpdateDTO;
import com.dataocean.module.datasource.entity.vo.DatasourceAccessVO;
import com.dataocean.module.datasource.entity.vo.DatasourceConnectionTestVO;
import com.dataocean.module.datasource.entity.vo.DatasourceVO;
import com.dataocean.module.datasource.service.DatasourceAccessService;
import com.dataocean.module.datasource.service.DatasourceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据源管理端控制器
 * <p>
 * 提供管理员对数据源的完整管理 API，包括 CRUD、状态变更、连接测试和访问授权管理。
 * 所有接口需要 datasource:manage 权限。
 * </p>
 *
 * @author dataocean
 */
@RestController
@RequestMapping("/api/admin/datasources")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('datasource:manage')")
@Slf4j
public class DatasourceAdminController {

    private final DatasourceService datasourceService;
    private final DatasourceAccessService accessService;

    /**
     * 分页查询数据源列表
     *
     * @param request 查询条件（名称、状态、健康状态、分页参数）
     * @return 分页数据源列表
     */
    @GetMapping
    public Result<Page<DatasourceVO>> listDatasources(@ModelAttribute DatasourceQuery request) {
        return Result.success(datasourceService.listDatasources(request));
    }

    /**
     * 获取数据源详情
     *
     * @param id 数据源 ID
     * @return 数据源详情
     */
    @GetMapping("/{id}")
    public Result<DatasourceVO> getDatasource(@PathVariable Long id) {
        return Result.success(datasourceService.getDatasourceById(id));
    }

    /**
     * 创建数据源
     *
     * @param request 创建请求参数
     * @return 创建后的数据源详情
     */
    @PostMapping
    public Result<DatasourceVO> createDatasource(@Valid @RequestBody DatasourceCreateDTO request) {
        log.debug("收到创建数据源请求 name={} host={} database={}", request.getName(), request.getHost(), request.getDatabaseName());
        return Result.success("创建成功", datasourceService.createDatasource(request));
    }

    /**
     * 更新数据源
     *
     * @param id      数据源 ID
     * @param request 更新请求参数
     * @return 更新后的数据源详情
     */
    @PutMapping("/{id}")
    public Result<DatasourceVO> updateDatasource(@PathVariable Long id,
                                                 @Valid @RequestBody DatasourceUpdateDTO request) {
        log.debug("收到更新数据源请求 datasourceId={} name={}", id, request.getName());
        return Result.success("更新成功", datasourceService.updateDatasource(id, request));
    }

    /**
     * 删除数据源（软删除）
     *
     * @param id 数据源 ID
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteDatasource(@PathVariable Long id) {
        datasourceService.deleteDatasource(id);
        return Result.success("删除成功", null);
    }

    /**
     * 更新数据源启用/禁用状态
     *
     * @param id      数据源 ID
     * @param request 状态更新请求
     * @return 更新后的数据源详情
     */
    @PatchMapping("/{id}/status")
    public Result<DatasourceVO> updateStatus(@PathVariable Long id,
                                             @Valid @RequestBody DatasourceStatusUpdateDTO request) {
        return Result.success("状态更新成功", datasourceService.updateStatus(id, request.getStatus()));
    }

    /**
     * 使用指定参数测试数据库连接（不保存）
     *
     * @param request 连接测试请求参数
     * @return 连接测试结果
     */
    @PostMapping("/test-connection")
    public Result<DatasourceConnectionTestVO> testConnection(@Valid @RequestBody DatasourceTestDTO request) {
        log.debug("收到数据源连接测试请求 host={} database={}", request.getHost(), request.getDatabaseName());
        return Result.success(datasourceService.testConnection(request));
    }

    /**
     * 对已保存的数据源执行连接测试
     *
     * @param id 数据源 ID
     * @return 连接测试结果
     */
    @PostMapping("/{id}/test-connection")
    public Result<DatasourceConnectionTestVO> testSavedConnection(@PathVariable Long id) {
        return Result.success(datasourceService.testSavedConnection(id));
    }

    /**
     * 批量授予用户对数据源的访问权限
     *
     * @param id      数据源 ID
     * @param request 授权请求参数
     * @return 实际新增授权数量
     */
    @PostMapping("/{id}/access")
    public Result<Map<String, Integer>> grantAccess(@PathVariable Long id,
                                                    @Valid @RequestBody DatasourceAccessGrantDTO request) {
        int granted = accessService.grantAccess(id, request);
        return Result.success("授权成功", Map.of("granted", granted));
    }

    /**
     * 查询数据源的授权用户列表
     *
     * @param id 数据源 ID
     * @return 授权用户列表
     */
    @GetMapping("/{id}/access")
    public Result<List<DatasourceAccessVO>> listAccess(@PathVariable Long id) {
        return Result.success(accessService.listAccess(id));
    }

    /**
     * 撤销用户对数据源的访问权限
     *
     * @param id     数据源 ID
     * @param userId 被撤销权限的用户 ID
     * @return 操作结果
     */
    @DeleteMapping("/{id}/access/{userId}")
    public Result<Void> revokeAccess(@PathVariable Long id, @PathVariable Long userId) {
        accessService.revokeAccess(id, userId);
        return Result.success("撤销成功", null);
    }
}
