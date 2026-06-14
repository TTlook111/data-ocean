package com.dataocean.module.permission.scheduler;

import com.dataocean.module.permission.service.AccessApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 数据访问审批过期清理定时任务。
 * <p>
 * 每小时扫描一次，将已过期的审批请求标记为 EXPIRED，
 * 同时删除对应的临时 ALLOW 策略并触发权限缓存失效。
 * </p>
 *
 * @author dataocean
 */
@Component
@ConditionalOnProperty(prefix = "dataocean.approval.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ApprovalExpiryScheduler {

    private final AccessApprovalService approvalService;

    /**
     * 每小时清理过期的审批请求
     */
    @Scheduled(cron = "0 0 * * * *")
    public void expireOverdueApprovals() {
        int expired = approvalService.expireOverdueRequests();
        if (expired > 0) {
            log.info("审批过期清理完成，过期数={}", expired);
        }
    }
}
