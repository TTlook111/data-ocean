package com.dataocean.module.versioning.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.pagination.PageRequest;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.system.aspect.AdminAuditLog;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.entity.vo.SchemaDiffVO;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.permission.s1.annotation.IamS1Resource;
import com.dataocean.module.permission.s1.annotation.IamS1ScopedList;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.dataocean.module.versioning.entity.SnapshotAuditLog;
import com.dataocean.module.versioning.entity.dto.SnapshotStatusChangeDTO;
import com.dataocean.module.versioning.entity.vo.SnapshotAuditLogVO;
import com.dataocean.module.versioning.entity.vo.SnapshotVersionHistoryVO;
import com.dataocean.module.versioning.service.SnapshotAuditLogService;
import com.dataocean.module.versioning.service.SnapshotLifecycleService;
import com.dataocean.module.versioning.service.SnapshotPublishService;
import com.dataocean.module.user.service.UserService;
import com.dataocean.module.user.entity.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 元数据快照版本控制器。
 * <p>
 * 提供快照状态流转、发布、撤回、版本历史、当前发布版本、审计日志和版本对比接口。
 * </p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@AdminAuditLog
public class SnapshotVersionController {

    /** 查看版本与审计：与审核/发布拆开的独立查看码。 */
    private static final String RELEASE_VIEW_FUNCTION = "metadata:release:view";
    /** 审核快照状态流转，不含发布。 */
    private static final String RELEASE_REVIEW_FUNCTION = "metadata:release:review";
    /** 发布与撤回；审核码不会自动带来此码。 */
    private static final String RELEASE_PUBLISH_FUNCTION = "metadata:release:publish";

    private final SnapshotLifecycleService lifecycleService;
    private final SnapshotPublishService publishService;
    private final SnapshotAuditLogService auditLogService;
    private final UserService userService;
    private final MetadataSnapshotMapper snapshotMapper;
    private final IamS1AdminGuard adminGuard;
    private final IamS1CapabilityService capabilityService;

    /** 调用者在指定功能上负责的数据源 ID。 */
    private List<Long> visibleDatasourceIds(Long userId, String functionCode) {
        return capabilityService.responsibleDatasourcesWithFunction(userId, functionCode)
                .stream()
                .map(IamS1DatasourceRefVO::id)
                .toList();
    }

    /**
     * 变更快照状态。
     *
     * @param snapshotId 快照 ID
     * @param request    状态变更请求
     * @return 操作结果
     */
    @PatchMapping("/snapshots/{snapshotId}/status")
    @IamS1Resource(function = RELEASE_REVIEW_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
    public Result<Void> changeStatus(@PathVariable Long snapshotId,
                                      @Valid @RequestBody SnapshotStatusChangeDTO request) {
        Long operatorId = UserContext.currentUserId();
        // 审核码只允许状态流转，发布有独立的功能码，审核不会自动带来发布权。
        lifecycleService.changeStatus(snapshotId, request.getTargetStatus(), operatorId, request.getReason());
        return Result.success();
    }

    /**
     * 发布快照版本。
     *
     * @param snapshotId 快照 ID
     * @return 操作结果
     */
    @PostMapping("/snapshots/{snapshotId}/publish")
    @IamS1Resource(function = RELEASE_PUBLISH_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
    public Result<Void> publish(@PathVariable Long snapshotId) {
        Long operatorId = UserContext.currentUserId();
        publishService.publishSnapshot(snapshotId, operatorId);
        return Result.success();
    }

    /**
     * 撤回已发布快照。
     *
     * @param snapshotId 快照 ID
     * @param request    撤回请求，必须包含原因
     * @return 操作结果
     */
    @PostMapping("/snapshots/{snapshotId}/revoke")
    @IamS1Resource(function = RELEASE_PUBLISH_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
    public Result<Void> revoke(@PathVariable Long snapshotId,
                                @RequestBody SnapshotStatusChangeDTO request) {
        if (request.getReason() == null || request.getReason().isBlank()) {
            return Result.error(400, "撤回操作必须填写原因");
        }
        Long operatorId = UserContext.currentUserId();
        publishService.revokeSnapshot(snapshotId, operatorId, request.getReason());
        return Result.success();
    }

    /**
     * 查询数据源快照版本历史。
     *
     * @param datasourceId 数据源 ID
     * @param page         页码
     * @param size         每页条数
     * @return 快照版本历史分页列表
     */
    @GetMapping("/datasources/{datasourceId}/version-history")
    @IamS1Resource(function = RELEASE_VIEW_FUNCTION, resourceType = IamS1ResourceType.DATASOURCE, resourceIds = "#datasourceId")
    public Result<Page<SnapshotVersionHistoryVO>> versionHistory(
            @PathVariable Long datasourceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        adminGuard.requireDatasourceFunction(UserContext.currentUserId(), RELEASE_VIEW_FUNCTION, datasourceId);
        return Result.success(lifecycleService.listVersionHistory(datasourceId,
                (int) PageRequest.page(page), (int) PageRequest.size(size)));
    }

    /**
     * 查询快照版本历史；未传数据源时返回所有数据源的版本历史。
     *
     * @param datasourceId 可选数据源 ID
     * @param page         页码
     * @param size         每页条数
     * @return 快照版本历史分页列表
     */
    @GetMapping("/version-history")
    @IamS1ScopedList(RELEASE_VIEW_FUNCTION)
    public Result<Page<SnapshotVersionHistoryVO>> allVersionHistory(
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long userId = UserContext.currentUserId();
        // 未传数据源时按负责源范围收窄；传了还要确认该源在范围内。
        if (datasourceId != null) {
            adminGuard.requireDatasourceFunction(userId, RELEASE_VIEW_FUNCTION, datasourceId);
        }
        List<Long> scope = datasourceId != null
                ? List.of(datasourceId)
                : visibleDatasourceIds(userId, RELEASE_VIEW_FUNCTION);
        return Result.success(lifecycleService.listVersionHistoryInDatasources(scope,
                (int) PageRequest.page(page), (int) PageRequest.size(size)));
    }

    /**
     * 查询数据源当前已发布快照。
     *
     * @param datasourceId 数据源 ID
     * @return 当前已发布快照；不存在时返回 null
     */
    @GetMapping("/datasources/{datasourceId}/published-snapshot")
    @IamS1Resource(function = RELEASE_VIEW_FUNCTION, resourceType = IamS1ResourceType.DATASOURCE, resourceIds = "#datasourceId")
    public Result<SnapshotVersionHistoryVO> publishedSnapshot(@PathVariable Long datasourceId) {
        adminGuard.requireDatasourceFunction(UserContext.currentUserId(), RELEASE_VIEW_FUNCTION, datasourceId);
        MetadataSnapshot snapshot = lifecycleService.getPublishedSnapshot(datasourceId);
        if (snapshot == null) {
            return Result.success(null);
        }
        SnapshotVersionHistoryVO vo = new SnapshotVersionHistoryVO();
        vo.setSnapshotId(snapshot.getId());
        vo.setDatasourceId(snapshot.getDatasourceId());
        vo.setSnapshotVersion(snapshot.getSnapshotVersion());
        vo.setStatus(snapshot.getStatus());
        vo.setQualityScore(snapshot.getQualityScore());
        vo.setTableCount(snapshot.getTableCount());
        vo.setColumnCount(snapshot.getColumnCount());
        vo.setPublishedAt(snapshot.getPublishedAt());
        return Result.success(vo);
    }

    /**
     * 查询指定快照的审计日志。
     *
     * @param snapshotId 快照 ID
     * @param page       页码
     * @param size       每页条数
     * @return 审计日志分页列表
     */
    @GetMapping("/snapshots/{snapshotId}/audit-logs")
    @IamS1Resource(function = RELEASE_VIEW_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
    public Result<Page<SnapshotAuditLogVO>> auditLogs(
            @PathVariable Long snapshotId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SnapshotAuditLog> logPage = auditLogService.listAuditLogs(snapshotId, page, size);
        Page<SnapshotAuditLogVO> voPage = convertToVOPage(logPage);
        return Result.success(voPage);
    }

    /**
     * 查询数据源维度的快照审计日志。
     *
     * @param datasourceId 数据源 ID
     * @param action       可选操作类型
     * @param page         页码
     * @param size         每页条数
     * @return 审计日志分页列表
     */
    @GetMapping("/datasources/{datasourceId}/audit-logs")
    @IamS1Resource(function = RELEASE_VIEW_FUNCTION, resourceType = IamS1ResourceType.DATASOURCE, resourceIds = "#datasourceId")
    public Result<Page<SnapshotAuditLogVO>> datasourceAuditLogs(
            @PathVariable Long datasourceId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        adminGuard.requireDatasourceFunction(UserContext.currentUserId(), RELEASE_VIEW_FUNCTION, datasourceId);
        Page<SnapshotAuditLog> logPage = auditLogService.listByDatasource(datasourceId, action, page, size);
        Page<SnapshotAuditLogVO> voPage = convertToVOPage(logPage);
        return Result.success(voPage);
    }

    /**
     * 对比两个快照版本的结构差异。
     *
     * @param snapshotId         基准快照 ID
     * @param compareSnapshotId  对比快照 ID
     * @return 快照差异结果
     */
    @GetMapping("/snapshots/{snapshotId}/diff/{compareSnapshotId}")
    @IamS1Resource(function = RELEASE_VIEW_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = {"#snapshotId", "#compareSnapshotId"})
    public Result<SchemaDiffVO> compareVersions(@PathVariable Long snapshotId,
                                                 @PathVariable Long compareSnapshotId) {
        Long userId = UserContext.currentUserId();
        // 两个快照都要校验：只校验其中一个就能借无权快照读到无权数据源的差异。
        return Result.success(lifecycleService.compareVersions(snapshotId, compareSnapshotId));
    }

    private Page<SnapshotAuditLogVO> convertToVOPage(Page<SnapshotAuditLog> logPage) {
        Page<SnapshotAuditLogVO> voPage = new Page<>(logPage.getCurrent(), logPage.getSize(), logPage.getTotal());
        List<SnapshotAuditLogVO> voList = logPage.getRecords().stream()
                .map(this::toAuditLogVO)
                .collect(Collectors.toList());
        voPage.setRecords(voList);
        return voPage;
    }

    private SnapshotAuditLogVO toAuditLogVO(SnapshotAuditLog log) {
        SnapshotAuditLogVO vo = new SnapshotAuditLogVO();
        vo.setId(log.getId());
        vo.setAction(log.getAction());
        vo.setOldStatus(log.getOldStatus());
        vo.setNewStatus(log.getNewStatus());
        vo.setReason(log.getReason());
        vo.setCreatedAt(log.getCreatedAt());
        try {
            UserVO user = userService.getUserById(log.getOperatorId());
            vo.setOperatorName(user != null ? user.getRealName() : "未知用户");
        } catch (Exception e) {
            vo.setOperatorName("未知用户");
        }
        return vo;
    }
}
