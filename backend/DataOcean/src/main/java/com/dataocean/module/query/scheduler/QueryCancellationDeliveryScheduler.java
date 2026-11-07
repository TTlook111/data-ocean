package com.dataocean.module.query.scheduler;

import com.dataocean.module.query.service.IamS1QueryCancellationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 取消补投独立于历史任务清理开关，重启后继续处理数据库中的待投递意图。 */
@Component
@Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class QueryCancellationDeliveryScheduler {
    private final IamS1QueryCancellationService cancellationService;

    @Scheduled(fixedDelay = 5000)
    public void retryPendingCancellations() {
        // 调度线程只查询并排队；单次故障不终止后续扫描，也不在这里执行远端 HTTP。
        try {
            cancellationService.retryPending();
        } catch (Exception ex) {
            log.warn("S1 取消补投扫描失败，等待下轮重试 reason={}", ex.getClass().getSimpleName());
        }
    }
}
