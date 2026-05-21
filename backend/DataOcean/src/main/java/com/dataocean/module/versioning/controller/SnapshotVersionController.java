package com.dataocean.module.versioning.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.entity.vo.SchemaDiffVO;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('metadata:manage')")
public class SnapshotVersionController {

    private final SnapshotLifecycleService lifecycleService;
    private final SnapshotPublishService publishService;
    private final SnapshotAuditLogService auditLogService;
    private final UserService userService;

    @PatchMapping("/snapshots/{snapshotId}/status")
    public Result<Void> changeStatus(@PathVariable Long snapshotId,
                                     @Valid @RequestBody SnapshotStatusChangeDTO request) {
        Long operatorId = UserContext.currentUserId();
        lifecycleService.changeStatus(snapshotId, request.getTargetStatus(), operatorId, request.getReason());
        return Result.success();
    }

    @PostMapping("/snapshots/{snapshotId}/publish")
    public Result<Void> publish(@PathVariable Long snapshotId) {
        Long operatorId = UserContext.currentUserId();
        publishService.publishSnapshot(snapshotId, operatorId);
        return Result.success();
    }

    @PostMapping("/snapshots/{snapshotId}/revoke")
    public Result<Void> revoke(@PathVariable Long snapshotId,
                               @RequestBody SnapshotStatusChangeDTO request) {
        if (request.getReason() == null || request.getReason().isBlank()) {
            return Result.error(400, "撤回操作必须填写原因");
        }
        Long operatorId = UserContext.currentUserId();
        publishService.revokeSnapshot(snapshotId, operatorId, request.getReason());
        return Result.success();
    }

    @GetMapping("/datasources/{datasourceId}/version-history")
    public Result<Page<SnapshotVersionHistoryVO>> versionHistory(
            @PathVariable Long datasourceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(lifecycleService.listVersionHistory(datasourceId, page, size));
    }

    @GetMapping("/datasources/{datasourceId}/published-snapshot")
    public Result<SnapshotVersionHistoryVO> publishedSnapshot(@PathVariable Long datasourceId) {
        MetadataSnapshot snapshot = lifecycleService.getPublishedSnapshot(datasourceId);
        if (snapshot == null) {
            return Result.success(null);
        }
        SnapshotVersionHistoryVO vo = new SnapshotVersionHistoryVO();
        vo.setSnapshotId(snapshot.getId());
        vo.setSnapshotVersion(snapshot.getSnapshotVersion());
        vo.setStatus(snapshot.getStatus());
        vo.setQualityScore(snapshot.getQualityScore());
        vo.setTableCount(snapshot.getTableCount());
        vo.setColumnCount(snapshot.getColumnCount());
        vo.setPublishedAt(snapshot.getPublishedAt());
        return Result.success(vo);
    }

    @GetMapping("/snapshots/{snapshotId}/audit-logs")
    public Result<Page<SnapshotAuditLogVO>> auditLogs(
            @PathVariable Long snapshotId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SnapshotAuditLog> logPage = auditLogService.listAuditLogs(snapshotId, page, size);
        Page<SnapshotAuditLogVO> voPage = convertToVOPage(logPage);
        return Result.success(voPage);
    }

    @GetMapping("/datasources/{datasourceId}/audit-logs")
    public Result<Page<SnapshotAuditLogVO>> datasourceAuditLogs(
            @PathVariable Long datasourceId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SnapshotAuditLog> logPage = auditLogService.listByDatasource(datasourceId, action, page, size);
        Page<SnapshotAuditLogVO> voPage = convertToVOPage(logPage);
        return Result.success(voPage);
    }

    @GetMapping("/snapshots/{snapshotId}/diff/{compareSnapshotId}")
    public Result<SchemaDiffVO> compareVersions(@PathVariable Long snapshotId,
                                                @PathVariable Long compareSnapshotId) {
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
