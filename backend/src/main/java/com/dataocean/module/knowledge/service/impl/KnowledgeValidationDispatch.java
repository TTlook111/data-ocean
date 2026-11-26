package com.dataocean.module.knowledge.service.impl;

import com.dataocean.module.knowledge.service.KnowledgeValidationWire.Status;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * 可靠派发并回读同一逐题召回运行。
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "dataocean.governance-ai.enabled", havingValue = "true")
public class KnowledgeValidationDispatch {

    /**
     * 复用现有有界机器身份 HTTP 客户端。
     */
    private final RestClient worker;

    /**
     * 确切运行及业务回执。
     */
    private final KnowledgeValidations validations;

    /**
     * 继续使用已有受认证的 Python 通信边界。
     */
    public KnowledgeValidationDispatch(
            @Qualifier("governancePythonClient") RestClient worker, KnowledgeValidations validations) {
        this.worker = worker;
        this.validations = validations;
    }

    /**
     * 网络结果未知时只重投原运行编号。
     */
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        var run = validations.claimDispatch();
        if (run == null) {
            return;
        }
        Status status = null;
        try {
            status = worker.put()
                    .uri("/internal/v1/tasks/knowledge/validations/{id}/1", run.id())
                    .retrieve()
                    .body(Status.class);
        } catch (RestClientException unknown) {
            // 日志只保留任务关联号。
            log.debug("验证召回待核实，runId={}", run.id());
        }
        validations.apply(run, status);
    }
}
