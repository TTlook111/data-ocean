package com.dataocean.module.fieldsemantic.service.impl;

import com.dataocean.module.fieldsemantic.service.GovernanceTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 专用后台调度，不在 HTTP 线程执行整批模型调用；一次最多两个字段。 */
@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name="dataocean.governance.worker-enabled", havingValue="true", matchIfMissing=true)
public class GovernanceTaskWorker {
    private final GovernanceTaskService service;
    @Scheduled(fixedDelayString="${dataocean.governance.worker-delay-ms:10000}", initialDelay=30000)
    public void tick() {
        try { service.processQueued(); }
        catch (java.util.concurrent.RejectedExecutionException exception) { log.debug("上一批治理仍在处理，稍后继续"); }
        catch (Exception exception) { log.warn("治理后台任务暂不可用 type={}", exception.getClass().getSimpleName()); }
    }
}
