package com.dataocean.module.system.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.permission.s1.annotation.IamS1ScopedList;
import com.dataocean.module.system.aspect.AdminAuditLog;
import com.dataocean.module.metadata.scheduler.AutoSyncScheduler;
import com.dataocean.module.system.entity.dto.SyncScheduleDTO;
import com.dataocean.module.system.entity.vo.SyncScheduleVO;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.system.service.SysConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;

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
@AdminAuditLog
public class SyncScheduleController {

    /** 查看采集计划。 */
    private static final String VIEW_FUNCTION = "metadata:collect:view";
    /** 修改采集计划。 */
    private static final String RUN_FUNCTION = "metadata:collect:run";

    private final SysConfigService configService;
    private final IamS1AdminGuard adminGuard;
    private final AutoSyncScheduler autoSyncScheduler;

    /**
     * 查询当前自动同步计划。
     *
     * @return 自动同步配置和运行状态
     */
    @GetMapping("/sync-schedule")
    @IamS1ScopedList(VIEW_FUNCTION)
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
     * <p><b>本模型的同步计划是全局的</b>：没有“单数据源计划”，改一次会影响**所有**启用数据源的采集。
     * 因此除了 `metadata:collect:run` 之外，这里还要求受保护 S1 系统管理员——
     * 不能让只负责一个数据源的人改变所有数据源的采集计划。</p>
     *
     * @param dto 自动同步配置
     * @return 更新后的自动同步配置和运行状态
     */
    @PutMapping("/sync-schedule")
    @IamS1ScopedList(RUN_FUNCTION)
    public Result<SyncScheduleVO> updateSchedule(@Valid @RequestBody SyncScheduleDTO dto) {
        if (!adminGuard.isSystemAdmin(UserContext.currentUserId())) {
            throw new BusinessException(403, "自动同步计划是全局配置，只能由受保护的系统管理员修改");
        }
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
