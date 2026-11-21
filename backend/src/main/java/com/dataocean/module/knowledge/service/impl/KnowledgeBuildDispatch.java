package com.dataocean.module.knowledge.service.impl;

import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Status;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * 按持久构建编号可靠提交和核实 Python 队列状态。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dataocean.governance-ai.enabled", havingValue = "true")
public class KnowledgeBuildDispatch {

    /**
     * 复用带机器身份和有界网络时限的 Python 客户端。
     */
    private final RestClient worker;

    /**
     * 派发保留原尝试，并由业务服务核对结果是否仍然有效。
     */
    private final KnowledgeBuilds builds;

    /**
     * 仅连接既有的内部 AI 服务。
     */
    public KnowledgeBuildDispatch(@Qualifier("governancePythonClient") RestClient worker, KnowledgeBuilds builds) {
        this.worker = worker;
        this.builds = builds;
    }

    /**
     * 原编号重复接收只读取确切代次的实际结果。
     */
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        var attempt = builds.claimDispatch();
        if (attempt == null) {
            return;
        }
        Status status;
        try {
            // Redis 以原构建编号幂等接收，Java 重启后继续从已提交记录派发。
            status = worker.put()
                    .uri("/internal/v1/tasks/knowledge/{id}/1", attempt.id())
                    .retrieve()
                    .body(Status.class);
        } catch (RestClientException unavailable) {
            // 网络故障不伪造任务结果，稍后核实同一尝试直到预算用尽。
            log.debug("知识构建待核实，buildId={}", attempt.id());
            status = null;
        }
        builds.apply(attempt, status);
    }
}
