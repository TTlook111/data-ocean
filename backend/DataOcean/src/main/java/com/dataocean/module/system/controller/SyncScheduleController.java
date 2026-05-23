package com.dataocean.module.system.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.metadata.scheduler.AutoSyncScheduler;
import com.dataocean.module.system.entity.dto.SyncScheduleDTO;
import com.dataocean.module.system.entity.vo.SyncScheduleVO;
import com.dataocean.module.system.service.SysConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * 元数据自动同步计划控制器。
 * <p>
 * 提供自动同步开关、cron 表达式查询和更新接口。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/system")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('metadata:manage')")
public class SyncScheduleController {

    private final SysConfigService configService;
    private final AutoSyncScheduler autoSyncScheduler;

    /**
     * 查询当前自动同步计划。
     *
     * @return 自动同步配置和运行状态
     */
    @GetMapping("/sync-schedule")
    public Result<SyncScheduleVO> getSchedule() {
        SyncScheduleVO vo = new SyncScheduleVO();
        vo.setCron(configService.getValue("metadata.auto-sync.cron", "0 0 2 * * ?"));
        vo.setEnabled("true".equalsIgnoreCase(configService.getValue("metadata.auto-sync.enabled", "false")));
        vo.setRunning(autoSyncScheduler.isRunning());
        return Result.success(vo);
    }

    /**
     * 更新自动同步计划。
     *
     * @param dto 自动同步配置
     * @return 更新后的自动同步配置和运行状态
     */
    @PutMapping("/sync-schedule")
    public Result<SyncScheduleVO> updateSchedule(@Valid @RequestBody SyncScheduleDTO dto) {
        if (StringUtils.hasText(dto.getCron())) {
            if (!CronExpression.isValidExpression(dto.getCron())) {
                return Result.error(400, "无效的 cron 表达式");
            }
            configService.setValue("metadata.auto-sync.cron", dto.getCron());
        }

        configService.setValue("metadata.auto-sync.enabled", dto.getEnabled().toString());
        autoSyncScheduler.refresh();

        return getSchedule();
    }
}
