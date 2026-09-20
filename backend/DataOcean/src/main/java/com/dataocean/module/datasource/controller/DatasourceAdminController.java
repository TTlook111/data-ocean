package com.dataocean.module.datasource.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.datasource.entity.dto.DatasourceCreateDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceStatusUpdateDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceTestDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceUpdateDTO;
import com.dataocean.module.datasource.entity.query.DatasourceQuery;
import com.dataocean.module.datasource.entity.vo.DatasourceConnectionTestVO;
import com.dataocean.module.datasource.entity.vo.DatasourceReadinessVO;
import com.dataocean.module.datasource.entity.vo.DatasourceSimpleVO;
import com.dataocean.module.datasource.entity.vo.DatasourceVO;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.service.DatasourceReadinessService;
import com.dataocean.module.datasource.service.DatasourceService;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.dataocean.module.system.aspect.AdminAuditLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据源管理端控制器
 * <p>
 * 提供管理员对数据源的完整管理 API，包括 CRUD、状态变更和连接测试。
 * 权限判定使用 IAM-SIMPLE-1：读取要求 `datasource:view`，写入要求 `datasource:manage`，
 * 两者都是“源”语义——除创建与连接测试外，一律校验目标数据源在调用者负责范围内。
 * 列表范围 **下推到 SQL**，不做取回后过滤。
 * </p>
 * <p>
 * 原“数据源访问授权”三个端点（`/{id}/access`）是旧权限链路的数据授权入口，
 * 按 B0 冻结在 B4 移除：新体系的数据授权走 `/api/iam-s1/data-grants`。
 * </p>
 *
 * @author dataocean
 */
@RestController
@RequestMapping("/api/admin/datasources")
@RequiredArgsConstructor
@AdminAuditLog
@Slf4j
public class DatasourceAdminController {

    /** 读取数据源：负责源范围内的查看。 */
    private static final String VIEW_FUNCTION = "datasource:view";
    /** 维护数据源：负责源范围内的管理。 */
    private static final String MANAGE_FUNCTION = "datasource:manage";

    private final DatasourceService datasourceService;
    private final DatasourceReadinessService readinessService;
    private final DatasourceMapper datasourceMapper;
    private final IamS1AdminGuard adminGuard;
    private final IamS1CapabilityService capabilityService;

    /**
     * 获取所有启用数据源的简要列表（供权限管理等跨模块使用）。
     * <p>只返回调用者在 `datasource:view` 上负责的源；数据源总量有限，这里用内存过滤即可。</p>
     */
    @GetMapping("/simple")
    public Result<List<DatasourceSimpleVO>> listSimple() {
        Long userId = UserContext.currentUserId();
        adminGuard.requireGlobalFunction(userId, VIEW_FUNCTION);
        List<Long> visible = visibleDatasourceIds(userId, VIEW_FUNCTION);
        if (visible.isEmpty()) {
            return Result.success(List.of());
        }
        return Result.success(datasourceMapper.selectEnabledSimple().stream()
                .filter(item -> visible.contains(item.getId()))
                .toList());
    }

    /**
     * 分页查询数据源列表
     *
     * @param request 查询条件（名称、状态、健康状态、分页参数）
     * @return 分页数据源列表（只含负责范围内的源）
     */
    @GetMapping
    public Result<Page<DatasourceVO>> listDatasources(@ModelAttribute DatasourceQuery request) {
        Long userId = UserContext.currentUserId();
        adminGuard.requireGlobalFunction(userId, VIEW_FUNCTION);
        return Result.success(datasourceService.listDatasources(request, visibleDatasourceIds(userId, VIEW_FUNCTION)));
    }

    /**
     * 获取数据源详情
     *
     * @param id 数据源 ID
     * @return 数据源详情
     */
    @GetMapping("/{id}")
    public Result<DatasourceVO> getDatasource(@PathVariable Long id) {
        adminGuard.requireDatasourceFunction(UserContext.currentUserId(), VIEW_FUNCTION, id);
        return Result.success(datasourceService.getDatasourceById(id));
    }

    /**
     * 获取数据源可询问状态。
     *
     * @param id 数据源 ID
     * @return 可询问状态
     */
    @GetMapping("/{id}/readiness")
    public Result<DatasourceReadinessVO> getReadiness(@PathVariable Long id) {
        adminGuard.requireDatasourceFunction(UserContext.currentUserId(), VIEW_FUNCTION, id);
        return Result.success(readinessService.getAdminReadiness(id));
    }

    /**
     * 批量获取数据源可询问状态。
     *
     * @param datasourceIds 数据源 ID 列表（逗号分隔）
     * @return 可询问状态列表
     */
    @GetMapping("/readiness/batch")
    public Result<List<DatasourceReadinessVO>> getBatchReadiness(@RequestParam List<Long> datasourceIds) {
        if (datasourceIds == null || datasourceIds.isEmpty()) {
            return Result.success(List.of());
        }
        // 限制批量查询数量，防止滥用
        if (datasourceIds.size() > 20) {
            throw new BusinessException("批量查询最多支持20个数据源");
        }
        Long userId = UserContext.currentUserId();
        // 逐个校验：批量接口不能让调用者借一个有权源读到无权源的状态。
        for (Long datasourceId : datasourceIds) {
            adminGuard.requireDatasourceFunction(userId, VIEW_FUNCTION, datasourceId);
        }
        return Result.success(readinessService.getBatchAdminReadiness(datasourceIds));
    }

    /**
     * 创建数据源
     *
     * <p>创建时目标数据源尚不存在，没有可校验的负责源，因此只要求调用者在某个绑定上持有
     * `datasource:manage`。新建的源需由系统管理员通过
     * `/api/iam-s1/user-roles/{id}/datasources` 显式绑定负责源，不自动授予业务数据权。</p>
     *
     * @param request 创建请求参数
     * @return 创建后的数据源详情
     */
    @PostMapping
    public Result<DatasourceVO> createDatasource(@Valid @RequestBody DatasourceCreateDTO request) {
        adminGuard.requireGlobalFunction(UserContext.currentUserId(), MANAGE_FUNCTION);
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
        adminGuard.requireDatasourceFunction(UserContext.currentUserId(), MANAGE_FUNCTION, id);
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
        adminGuard.requireDatasourceFunction(UserContext.currentUserId(), MANAGE_FUNCTION, id);
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
        adminGuard.requireDatasourceFunction(UserContext.currentUserId(), MANAGE_FUNCTION, id);
        return Result.success("状态更新成功", datasourceService.updateStatus(id, request.getStatus()));
    }

    /**
     * 使用指定参数测试数据库连接（不保存）
     *
     * <p>连接参数由请求体直接给出，没有目标数据源可校验；只要求调用者是数据源管理员。</p>
     *
     * @param request 连接测试请求参数
     * @return 连接测试结果
     */
    @PostMapping("/test-connection")
    public Result<DatasourceConnectionTestVO> testConnection(@Valid @RequestBody DatasourceTestDTO request) {
        adminGuard.requireGlobalFunction(UserContext.currentUserId(), MANAGE_FUNCTION);
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
        adminGuard.requireDatasourceFunction(UserContext.currentUserId(), MANAGE_FUNCTION, id);
        return Result.success(datasourceService.testSavedConnection(id));
    }

    /** 调用者在指定功能上负责的数据源 ID。 */
    private List<Long> visibleDatasourceIds(Long userId, String functionCode) {
        return capabilityService.responsibleDatasourcesWithFunction(userId, functionCode)
                .stream()
                .map(IamS1DatasourceRefVO::id)
                .toList();
    }
}
