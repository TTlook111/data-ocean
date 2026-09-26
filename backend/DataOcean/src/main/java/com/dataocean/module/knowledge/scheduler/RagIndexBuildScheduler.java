package com.dataocean.module.knowledge.scheduler;

import com.dataocean.module.knowledge.service.RagIndexBuildService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs only builds that a user has explicitly confirmed. */
@Component
@RequiredArgsConstructor
@Slf4j
public class RagIndexBuildScheduler {
    private final RagIndexBuildService ragIndexBuildService;

    @Scheduled(fixedDelay = 5000)
    public void processConfirmedBuilds() {
        try {
            ragIndexBuildService.processQueuedBuilds();
        } catch (Exception e) {
            log.error("RAG build scheduler failed", e);
        }
    }

    @Scheduled(fixedDelay = 10000)
    public void cleanSupersededBuilds() {
        try {
            ragIndexBuildService.cleanupSupersededBuilds();
        } catch (Exception e) {
            log.error("RAG cleanup scheduler failed", e);
        }
    }
}
