package com.dataocean.module.governance.service.impl;

import com.dataocean.module.governance.service.GovernanceGenerationWire.Status;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * Java 恢复同一已接收任务并回读 Python 持久状态。
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "dataocean.governance-ai.enabled", havingValue = "true")
public class GovernanceGenerationDispatch {

    /**
     * Python 内部服务请求使用固定身份与超时。
     */
    private final RestClient worker;

    /**
     * 已接收背景命令通过原租约提交其真实结果。
     */
    private final GovernanceGenerationTasks tasks;

    /**
     * 仅使用治理机器连接，避免复用其他上游请求。
     */
    public GovernanceGenerationDispatch(@Qualifier("governancePythonClient") RestClient worker,
            GovernanceGenerationTasks tasks) {
        this.worker = worker;
        this.tasks = tasks;
    }

    /**
     * 对同一句柄派发和回读，HTTP 超时后继续核实原任务。
     */
    @Scheduled(fixedDelay = 750)
    public void tick() {
        var task = tasks.claim();
        if (task == null) {
            // 无已提交任务时不向模型服务发请求。
            return;
        }
        Status state;
        try {
            // 幂等接收返回已存在的当前状态，丢失回包不会重新入队。
            state = worker.put()
                    .uri("/internal/v1/tasks/governance/{request}/{run}", task.getRequestId(), task.getRunVersion())
                    .retrieve().body(Status.class);
        } catch (RestClientException unavailable) {
            // 保留原请求定位和有界重试，避免输出原始 HTTP 内容。
            log.info("治理任务暂无法核实 Python 状态，taskId={}", task.getRequestId());
            state = null;
        }
        tasks.advance(task, state);
    }
}
